#!/usr/bin/env python3
"""
Remote-control server: phone -> keyboard / mouse / gamepad on this computer,
and PC screen -> phone (JPEG stream).

Four independent parts:

  transports   how commands arrive  UDP (fast, LAN) and TCP (works through any tunnel).
  stream       PC screen to phone   TCP, JPEG frames, needs mss + pillow.
  core         what commands mean   protocol, token check, held-input safety
  backends     how input is made    Windows (SendInput + virtual Xbox pad), generic
                                    pynput (Linux/X11, macOS), dummy (testing)

Command wire format (identical on every transport):  <token>|<cmd>|<arg>
  UDP: one command per datagram.
  TCP: one command per frame, framed as a 2-byte big-endian length + the same bytes.

Stream wire format:
  phone -> server : 2-byte length + token            (once, on connect)
                    2-byte length + "fps=15;w=1280;q=60"
  server -> phone : 4-byte length + JPEG frame       (repeated)

Upload wire format (same port as the stream, picked by the second string):
  phone -> server : 2-byte length + token
                    2-byte length + "upload;ext=jpg;paste=1;len=N"
                    N raw bytes (the picture)
  server -> phone : 2-byte length + "OK|saved, on PC clipboard, pasted"  or  "ERR|reason"
  The picture is saved in uploads/ next to this file and put on the PC clipboard; with paste=1 the
  server then presses Ctrl+V (Cmd+V on macOS) so it lands in the focused app.

Settings come from config.json next to this file. Command-line flags override it.
Ctrl+C does nothing on purpose: stop the server with `kps -o` (or kill the process).

Run:   uv run server.py
"""
import argparse
import hmac
import io
import json
import os
import platform
import signal
import socket
import struct
import sys
import threading
import time
from pathlib import Path

CONFIG_FILE = Path(__file__).with_name("config.json")
DIAG_FILE = Path(__file__).with_name("diag.jsonl")      # layout diagnostics sent by the phone

DEFAULTS = {
    "host": "0.0.0.0",
    "udp_port": 5005,
    "tcp_port": 5005,
    "stream_port": 5006,
    "token": "change-me",
    "transports": "udp,tcp",
    "backend": "auto",
    "idle_release": 5.0,
    "stream": True,
    "stream_fps": 15,
    "stream_width": 1280,
    "stream_quality": 60,
}

HOSTNAME = socket.gethostname()


UPLOAD_DIR = Path(__file__).with_name("uploads")        # pictures sent from the phone
MAX_UPLOAD = 40 * 1024 * 1024                           # refuse anything bigger than 40 MB

LOG_FILE = Path(__file__).with_name("server.log")      # `kps -l` streams this file


def log(*a):
    line = f"{time.strftime('%H:%M:%S')} " + " ".join(str(x) for x in a)
    print(line, flush=True)
    with LOG_FILE.open("a", encoding="utf-8") as f:
        f.write(line + "\n")


def diag_log(text):
    """Append one layout-diagnostics line from the phone to diag.jsonl, one JSON object per line."""
    stamp = time.strftime("%Y-%m-%d %H:%M:%S")
    try:
        payload = json.loads(text)
    except ValueError:
        payload = text                      # keep it even if it is not valid JSON
    with DIAG_FILE.open("a", encoding="utf-8") as f:
        f.write(json.dumps({"at": stamp, "diag": payload}) + "\n")
    log("layout diagnostics saved to", DIAG_FILE.name)


def load_config(path=CONFIG_FILE):
    cfg = dict(DEFAULTS)
    try:
        with open(path, encoding="utf-8") as f:
            cfg.update(json.load(f))
    except FileNotFoundError:
        log(f"no {path.name}, using defaults")
    except (OSError, ValueError) as e:
        sys.exit(f"cannot read {path}: {e}")
    return cfg


# ============================================================ input backends

class Backend:
    """Interface every platform backend implements."""

    name = "base"
    has_gamepad = False
    pad_held = False            # a gamepad button or stick is currently active

    def __init__(self):
        self.held = set()           # keyboard keys currently held down
        self.buttons_down = set()   # mouse buttons currently held down
        self._fx = 0.0              # fractional mouse movement carried to the next packet
        self._fy = 0.0
        self.geometry = None       # (left, top, width, height) of the primary screen, or None

    def _whole(self, dx, dy):
        """Carry sub-pixel remainders so slow finger movement is not rounded away."""
        self._fx += dx
        self._fy += dy
        ix, iy = int(self._fx), int(self._fy)
        self._fx -= ix
        self._fy -= iy
        return ix, iy

    def move(self, dx, dy): raise NotImplementedError
    def click(self, button, count=1): raise NotImplementedError
    def button_down(self, button): raise NotImplementedError
    def button_up(self, button): raise NotImplementedError
    def scroll(self, steps): raise NotImplementedError
    def type_text(self, text): raise NotImplementedError
    def key_tap(self, name): raise NotImplementedError
    def key_down(self, name): raise NotImplementedError
    def key_up(self, name): raise NotImplementedError
    def hotkey(self, combo): raise NotImplementedError

    def move_to_pixel(self, x, y): raise NotImplementedError

    def move_to_fraction(self, fx, fy):
        """Put the pointer at a fraction (0..1) of the primary screen. Used for tap-to-click."""
        if not self.geometry:
            return
        left, top, w, h = self.geometry
        fx = min(max(fx, 0.0), 1.0)
        fy = min(max(fy, 0.0), 1.0)
        self.move_to_pixel(left + fx * (w - 1), top + fy * (h - 1))

    def release_all(self):
        for n in list(self.held):
            self.key_up(n)
        for b in list(self.buttons_down):
            self.button_up(b)

    def pad_state(self, arg): pass
    def pad_reset(self): pass


class DummyBackend(Backend):
    """Prints what it would do. For testing the protocol and transports."""

    name = "dummy"

    def move(self, dx, dy):
        ix, iy = self._whole(dx, dy)
        print(f"ACT move {ix} {iy}", flush=True)

    def click(self, button, count=1): print(f"ACT click {button} {count}", flush=True)

    def button_down(self, button):
        self.buttons_down.add(button)
        print(f"ACT bdown {button}", flush=True)

    def button_up(self, button):
        self.buttons_down.discard(button)
        print(f"ACT bup {button}", flush=True)

    def scroll(self, steps): print(f"ACT scroll {steps}", flush=True)
    def type_text(self, text): print(f"ACT type {text}", flush=True)
    def key_tap(self, name): print(f"ACT key {name}", flush=True)

    def key_down(self, name):
        self.held.add(name)
        print(f"ACT down {name}", flush=True)

    def key_up(self, name):
        self.held.discard(name)
        print(f"ACT up {name}", flush=True)

    def hotkey(self, combo): print(f"ACT hotkey {combo}", flush=True)

    def move_to_pixel(self, x, y): print(f"ACT moveto {int(x)} {int(y)}", flush=True)

    def pad_state(self, arg):
        print(f"ACT pad {arg}", flush=True)
        parts = arg.split("|")
        self.pad_held = int(parts[0]) != 0 or any(float(x) != 0 for x in parts[1:])

    def pad_reset(self):
        self.pad_held = False
        print("ACT pad reset", flush=True)


class PynputBackend(Backend):
    """Generic backend: Windows, macOS, Linux on X11. (Wayland blocks pynput; use a uinput backend.)"""

    name = "pynput"

    def __init__(self):
        super().__init__()
        from pynput.keyboard import Controller as Kb, Key
        from pynput.mouse import Button, Controller as Mouse
        self.mouse, self.kb = Mouse(), Kb()
        self.special = {k.name: k for k in Key}
        self.special.setdefault("win", Key.cmd)
        self.buttons = {"L": Button.left, "R": Button.right, "M": Button.middle}

    def _key(self, name):
        return self.special.get(name, name)

    def move(self, dx, dy):
        ix, iy = self._whole(dx, dy)
        if ix or iy:
            self.mouse.move(ix, iy)

    def click(self, button, count=1): self.mouse.click(self.buttons[button], count)

    def button_down(self, button):
        if button not in self.buttons_down:
            self.mouse.press(self.buttons[button])
            self.buttons_down.add(button)

    def button_up(self, button):
        if button in self.buttons_down:
            self.mouse.release(self.buttons[button])
            self.buttons_down.discard(button)

    def scroll(self, steps): self.mouse.scroll(0, steps)

    def move_to_pixel(self, x, y): self.mouse.position = (int(x), int(y))
    def type_text(self, text): self.kb.type(text)

    def key_tap(self, name):
        k = self._key(name)
        self.kb.press(k)
        self.kb.release(k)

    def key_down(self, name):
        if name not in self.held:
            self.kb.press(self._key(name))
            self.held.add(name)

    def key_up(self, name):
        if name in self.held:
            self.kb.release(self._key(name))
            self.held.discard(name)

    def hotkey(self, combo):
        keys = [self._key(p) for p in combo.lower().split("+")]
        for k in keys:
            self.kb.press(k)
        for k in reversed(keys):
            self.kb.release(k)

    def release_all(self):
        for n in list(self.held):
            try:
                self.kb.release(self._key(n))
            except Exception:
                pass
        self.held.clear()
        for b in list(self.buttons_down):
            try:
                self.mouse.release(self.buttons[b])
            except Exception:
                pass
        self.buttons_down.clear()


class WindowsBackend(PynputBackend):
    """pynput for keys, SendInput-style relative mouse (games see it), virtual Xbox 360 pad."""

    name = "windows"
    _BTN = {"L": (0x0002, 0x0004), "R": (0x0008, 0x0010), "M": (0x0020, 0x0040)}

    def __init__(self):
        import ctypes
        # Be DPI aware BEFORE anything reads or sets pixel positions. Otherwise, with display scaling
        # (125% / 150%), taps on the phone's picture land in the wrong place on the PC.
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except Exception:
            try:
                ctypes.windll.user32.SetProcessDPIAware()
            except Exception:
                pass
        super().__init__()
        self._user32 = ctypes.windll.user32
        self.pad = None
        try:
            import vgamepad as vg          # pip install vgamepad (sets up the ViGEmBus driver)
            self.pad = vg.VX360Gamepad()
            self.has_gamepad = True
            log("virtual gamepad ready")
        except Exception as e:
            log("gamepad disabled (needs vgamepad + ViGEmBus):", e)

    def move_to_pixel(self, x, y):
        self._user32.SetCursorPos(int(x), int(y))           # absolute, in real screen pixels

    def move(self, dx, dy):
        ix, iy = self._whole(dx, dy)
        if ix or iy:
            self._user32.mouse_event(0x0001, ix, iy, 0, 0)   # MOUSEEVENTF_MOVE = relative

    def button_down(self, button):
        if button not in self.buttons_down:
            self._user32.mouse_event(self._BTN[button][0], 0, 0, 0, 0)
            self.buttons_down.add(button)

    def button_up(self, button):
        if button in self.buttons_down:
            self._user32.mouse_event(self._BTN[button][1], 0, 0, 0, 0)
            self.buttons_down.discard(button)

    def pad_state(self, arg):
        if not self.pad:
            return
        m, lx, ly, rx, ry, lt, rt = arg.split("|")      # buttons|lx|ly|rx|ry|lt|rt
        self.pad.report.wButtons = int(m) & 0xFFFF
        self.pad.left_joystick_float(float(lx), float(ly))
        self.pad.right_joystick_float(float(rx), float(ry))
        self.pad.left_trigger_float(float(lt))
        self.pad.right_trigger_float(float(rt))
        self.pad.update()
        self.pad_held = int(m) != 0 or any(float(x) != 0 for x in (lx, ly, rx, ry, lt, rt))

    def pad_reset(self):
        self.pad_held = False
        if self.pad:
            self.pad.reset()
            self.pad.update()


def make_backend(choice):
    if choice == "auto":
        choice = "windows" if platform.system() == "Windows" else "pynput"
    try:
        return {"dummy": DummyBackend, "pynput": PynputBackend, "windows": WindowsBackend}[choice]()
    except ImportError as e:
        sys.exit(f"cannot start the '{choice}' backend: {e}\n"
                 "install the dependencies (uv sync) or pick another with --backend")


# ============================================================ core protocol

class Handler:
    """Transport-independent: parse one command, check the token, drive the backend."""

    def __init__(self, backend, token, idle_release=5.0):
        self.b = backend
        self.token = token.encode()
        self.idle_release = idle_release
        self.last_rx = time.time()
        self.lock = threading.Lock()
        threading.Thread(target=self._watchdog, daemon=True).start()

    def _watchdog(self):
        # the phone vanished while something was held -> let go so nothing stays stuck
        while True:
            time.sleep(1)
            if time.time() - self.last_rx > self.idle_release and (
                    self.b.held or self.b.buttons_down or self.b.pad_held):
                log("client silent, releasing held inputs")
                self.release_everything()

    def release_everything(self):
        with self.lock:
            self.b.release_all()
            self.b.pad_reset()

    def handle(self, data, reply):
        if data.strip() == b"DISCOVER":                 # LAN auto-discovery (UDP broadcast)
            reply(f"HERE|{HOSTNAME}".encode())
            return
        tok, cmd, arg = (data.decode("utf-8", "replace").split("|", 2) + ["", ""])[:3]
        if not hmac.compare_digest(tok.encode(), self.token):
            return
        self.last_rx = time.time()
        with self.lock:
            try:
                self._dispatch(cmd, arg, reply)
            except Exception as e:
                log("bad packet:", data[:80], repr(e))

    def _dispatch(self, cmd, arg, reply):
        b = self.b
        if cmd == "P":                                   # latency ping: echo the timestamp
            reply(f"PONG|{arg}".encode())
        elif cmd == "M":
            dx, dy = arg.split("|")
            b.move(float(dx), float(dy))
        elif cmd == "C":
            if arg == "D":
                b.click("L", 2)
            else:
                b.click(arg)
        elif cmd == "A":                                 # move to a spot on the screen (fractions)
            fx, fy = arg.split("|")
            b.move_to_fraction(float(fx), float(fy))
        elif cmd == "BD":                                # hold a mouse button (drag)
            b.button_down(arg)
        elif cmd == "BU":                                # let a mouse button go
            b.button_up(arg)
        elif cmd == "S":
            b.scroll(int(float(arg)))
        elif cmd == "T":
            b.type_text(arg)
        elif cmd == "K":
            b.key_tap(arg)
        elif cmd == "H":
            b.hotkey(arg)
        elif cmd == "D":
            b.key_down(arg)
        elif cmd == "U":
            b.key_up(arg)
        elif cmd == "G":
            b.pad_state(arg)
        elif cmd == "E":                                 # UI event from the phone (what you did): log it
            log("UI", arg)
        elif cmd == "L":                                 # layout diagnostics from the phone: save, don't act
            diag_log(arg)
        elif cmd == "X":
            b.release_all()
            b.pad_reset()


# ============================================================ command transports

def serve_udp(handler, host, port):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((host, port))
    log(f"UDP     listening on {host}:{port}")
    while True:
        try:
            data, addr = s.recvfrom(2048)
        except ConnectionResetError:        # Windows: ICMP "port unreachable" from a vanished client
            continue
        except OSError:
            return

        def reply(b, addr=addr):
            try:
                s.sendto(b, addr)
            except OSError:
                pass

        handler.handle(data, reply)


def _recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return bytes(buf)


def _tcp_client(handler, conn, addr):
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    conn.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    send_lock = threading.Lock()

    def reply(b):
        try:
            with send_lock:
                conn.sendall(struct.pack("!H", len(b)) + b)
        except OSError:
            pass

    log(f"TCP     client connected {addr[0]}:{addr[1]}")
    try:
        while True:
            (n,) = struct.unpack("!H", _recv_exact(conn, 2))
            if n == 0 or n > 4096:
                break
            handler.handle(_recv_exact(conn, n), reply)
    except (ConnectionError, OSError):
        pass
    finally:
        conn.close()
        log(f"TCP     client left {addr[0]}:{addr[1]}")
        handler.release_everything()


def serve_tcp(handler, host, port):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((host, port))
    srv.listen(8)
    log(f"TCP     listening on {host}:{port}")
    while True:
        try:
            conn, addr = srv.accept()
        except OSError:
            return
        threading.Thread(target=_tcp_client, args=(handler, conn, addr), daemon=True).start()


TRANSPORTS = {"udp": serve_udp, "tcp": serve_tcp}


# ============================================================ screen stream

class Streamer:
    """Captures the primary screen and encodes each frame as JPEG."""

    def __init__(self, fps, width, quality):
        self.fps = max(1, int(fps))
        self.width = int(width)
        self.quality = int(quality)
        self.ok = False
        try:
            import mss                     # noqa: F401  pip install mss pillow
            from PIL import Image          # noqa: F401
            self.ok = True
        except ImportError as e:
            log("screen stream disabled (pip install mss pillow):", e)

    def frame_source(self, width, quality):
        """Called once per client thread: mss must be used from the thread that made it."""
        import mss
        from PIL import Image
        sct = mss.mss()
        monitor = sct.monitors[1]           # primary monitor

        def grab():
            shot = sct.grab(monitor)
            img = Image.frombytes("RGB", shot.size, shot.rgb)
            if img.width > width:
                img = img.resize((width, round(img.height * width / img.width)))
            buf = io.BytesIO()
            img.save(buf, "JPEG", quality=quality)
            return buf.getvalue()

        return grab


def _read_str(conn):
    (n,) = struct.unpack("!H", _recv_exact(conn, 2))
    return _recv_exact(conn, n)


def _parse_settings(raw, defaults):
    """'fps=15;w=1280;q=60' from the phone. Anything missing or odd falls back to config.json."""
    out = {"fps": defaults.fps, "width": defaults.width, "quality": defaults.quality}
    for part in raw.decode("ascii", "replace").split(";"):
        k, _, v = part.partition("=")
        try:
            if k == "fps":
                out["fps"] = int(v)
            elif k == "w":
                out["width"] = int(v)
            elif k == "q":
                out["quality"] = int(v)
        except ValueError:
            pass
    out["fps"] = min(max(out["fps"], 1), 30)
    out["width"] = min(max(out["width"], 320), 3840)
    out["quality"] = min(max(out["quality"], 20), 95)
    return out


def _stream_client(streamer, token, conn, addr, handler=None):
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    conn.settimeout(30)                     # a stalled phone must not hold a thread forever
    try:
        tok = _read_str(conn)
        if not hmac.compare_digest(tok, token.encode()):
            log(f"STREAM  rejected {addr[0]} (bad token)")
            return
        raw = _read_str(conn)
        if raw.startswith(b"upload;"):
            params = dict(p.partition("=")[::2] for p in raw.decode("ascii", "replace").split(";")[1:])
            if handler is not None:
                handle_upload(handler, conn, addr, params)
            return
        if not streamer.ok:
            return                          # no screen capture here (pip install mss pillow)
        conn.settimeout(None)
        cfg = _parse_settings(raw, streamer)
        grab = streamer.frame_source(cfg["width"], cfg["quality"])
        log(f"STREAM  client {addr[0]} watching: {cfg['fps']} fps, {cfg['width']} px, q{cfg['quality']}")
        period = 1.0 / cfg["fps"]
        while True:
            t0 = time.time()
            jpg = grab()
            conn.sendall(struct.pack("!I", len(jpg)) + jpg)
            time.sleep(max(0.0, period - (time.time() - t0)))
    except (ConnectionError, OSError, struct.error):
        pass
    except Exception as e:                  # capture failed (e.g. secure desktop): report, drop client
        log("STREAM  error:", repr(e))
    finally:
        conn.close()
        log(f"STREAM  client left {addr[0]}")


def serve_stream(streamer, token, host, port, handler=None):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((host, port))
    srv.listen(4)
    log(f"STREAM  listening on {host}:{port}")
    while True:
        try:
            conn, addr = srv.accept()
        except OSError:
            return
        threading.Thread(target=_stream_client, args=(streamer, token, conn, addr, handler), daemon=True).start()


# ============================================================ picture upload

def set_clipboard_image(path):
    """Put the picture at `path` on this computer's clipboard. Returns (ok, short message)."""
    try:
        from PIL import Image
    except ImportError:
        return False, "pillow is not installed"
    system = platform.system()
    try:
        img = Image.open(path)
        img.load()
        if system == "Windows":
            return _clipboard_windows(img)
        png = path.with_suffix(".clip.png")
        img.convert("RGBA").save(png, "PNG")
        try:
            if system == "Darwin":
                import subprocess
                script = f'set the clipboard to (read (POSIX file "{png}") as «class PNGf»)'
                subprocess.run(["osascript", "-e", script], check=True, timeout=10)
                return True, "on clipboard"
            import shutil
            import subprocess
            if shutil.which("wl-copy"):
                with open(png, "rb") as f:
                    subprocess.run(["wl-copy", "--type", "image/png"], stdin=f, check=True, timeout=10)
                return True, "on clipboard"
            if shutil.which("xclip"):
                subprocess.run(["xclip", "-selection", "clipboard", "-t", "image/png", "-i", str(png)],
                               check=True, timeout=10)
                return True, "on clipboard"
            return False, "no clipboard tool (install xclip or wl-clipboard)"
        finally:
            try:
                png.unlink()
            except OSError:
                pass
    except Exception as e:
        return False, f"clipboard failed: {e}"


def _clipboard_windows(img):
    """Copies the picture as a device-independent bitmap, which every Windows app can paste."""
    import ctypes
    from ctypes import wintypes
    out = io.BytesIO()
    img.convert("RGB").save(out, "BMP")
    dib = out.getvalue()[14:]                       # drop the 14-byte BMP file header
    CF_DIB, GMEM_MOVEABLE = 8, 0x0002
    k32, u32 = ctypes.windll.kernel32, ctypes.windll.user32
    k32.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
    k32.GlobalAlloc.restype = wintypes.HGLOBAL
    k32.GlobalLock.argtypes = [wintypes.HGLOBAL]
    k32.GlobalLock.restype = wintypes.LPVOID
    k32.GlobalUnlock.argtypes = [wintypes.HGLOBAL]
    k32.GlobalFree.argtypes = [wintypes.HGLOBAL]
    u32.OpenClipboard.argtypes = [wintypes.HWND]
    u32.SetClipboardData.argtypes = [wintypes.UINT, wintypes.HANDLE]
    u32.SetClipboardData.restype = wintypes.HANDLE
    for _ in range(10):                             # another app may hold the clipboard for a moment
        if u32.OpenClipboard(None):
            break
        time.sleep(0.05)
    else:
        return False, "clipboard is busy"
    try:
        u32.EmptyClipboard()
        h = k32.GlobalAlloc(GMEM_MOVEABLE, len(dib))
        if not h:
            return False, "out of memory"
        ptr = k32.GlobalLock(h)
        ctypes.memmove(ptr, dib, len(dib))
        k32.GlobalUnlock(h)
        if not u32.SetClipboardData(CF_DIB, h):
            k32.GlobalFree(h)
            return False, "could not set the clipboard"
        return True, "on clipboard"
    finally:
        u32.CloseClipboard()


def handle_upload(handler, conn, addr, params):
    """The picture follows the settings string. Reply with one short line."""
    def reply(text):
        b = text.encode("utf-8", "replace")[:500]
        try:
            conn.sendall(struct.pack("!H", len(b)) + b)
        except OSError:
            pass

    try:
        ext = params.get("ext", "jpg").lower()
        if ext not in ("jpg", "png"):
            ext = "jpg"
        size = int(params.get("len", "0"))
        if size <= 0 or size > MAX_UPLOAD:
            reply("ERR|picture is empty or too big")
            return
        data = _recv_exact(conn, size)
        UPLOAD_DIR.mkdir(exist_ok=True)
        path = UPLOAD_DIR / f"phone-{time.strftime('%Y%m%d-%H%M%S')}-{int(time.time() * 1000) % 1000:03d}.{ext}"
        path.write_bytes(data)
        ok, why = set_clipboard_image(path)
        parts = [f"saved {path.name}", why if ok else f"not on clipboard ({why})"]
        if ok and params.get("paste") == "1":
            time.sleep(0.15)                        # let the clipboard settle before pasting
            with handler.lock:
                handler.b.hotkey("cmd+v" if platform.system() == "Darwin" else "ctrl+v")
            parts.append("pasted")
        log(f"UPLOAD  {addr[0]}: {size} bytes, " + ", ".join(parts))
        reply("OK|" + ", ".join(parts))
    except (ConnectionError, OSError, ValueError, struct.error) as e:
        log("UPLOAD  failed:", repr(e))
        reply("ERR|upload failed: " + str(e))


def primary_geometry():
    """(left, top, width, height) of the primary monitor, from mss. None if it can't be read."""
    try:
        import mss
        with mss.mss() as sct:
            m = sct.monitors[1]
            return (m["left"], m["top"], m["width"], m["height"])
    except Exception as e:
        log("screen size unknown, tap-to-click disabled:", repr(e))
        return None


# ============================================================ main

def main():
    cfg = load_config()
    ap = argparse.ArgumentParser(description="Remote-control server (settings in config.json)")
    ap.add_argument("--host", default=cfg["host"], help="address to listen on")
    ap.add_argument("--udp-port", type=int, default=cfg["udp_port"])
    ap.add_argument("--tcp-port", type=int, default=cfg["tcp_port"])
    ap.add_argument("--stream-port", type=int, default=cfg["stream_port"])
    ap.add_argument("--token", default=os.environ.get("REMOTE_TOKEN", cfg["token"]),
                    help="shared secret (or set REMOTE_TOKEN)")
    ap.add_argument("--transports", default=cfg["transports"], help="comma list: udp,tcp")
    ap.add_argument("--backend", default=cfg["backend"], choices=["auto", "windows", "pynput", "dummy"])
    ap.add_argument("--idle-release", type=float, default=cfg["idle_release"],
                    help="seconds of silence before held keys are released")
    ap.add_argument("--no-stream", action="store_true", help="do not start the screen stream")
    a = ap.parse_args()

    # Ctrl+C is ignored on purpose. Stop the server with `kps -o` instead.
    signal.signal(signal.SIGINT, signal.SIG_IGN)

    names = [t.strip() for t in a.transports.split(",") if t.strip()]
    bad = [t for t in names if t not in TRANSPORTS]
    if bad or not names:
        sys.exit(f"unknown transport(s): {bad or names}. choose from {', '.join(TRANSPORTS)}")

    backend = make_backend(a.backend)
    backend.geometry = primary_geometry()
    log(f"backend: {backend.name}  ({platform.system()})  host name: {HOSTNAME}")
    if a.token == DEFAULTS["token"]:
        log("WARNING: using the default token. Anyone who can reach this port can control "
            "this computer. Set token in config.json.")

    handler = Handler(backend, a.token, a.idle_release)
    ports = {"udp": a.udp_port, "tcp": a.tcp_port}
    for n in names:
        threading.Thread(target=TRANSPORTS[n], args=(handler, a.host, ports[n]), daemon=True).start()

    # The stream port also takes pictures sent from the phone, so it always listens. The screen picture
    # itself is only served when streaming is on and mss + pillow are installed.
    streamer = Streamer(cfg["stream_fps"], cfg["stream_width"], cfg["stream_quality"])
    if not cfg["stream"] or a.no_stream:
        streamer.ok = False
    threading.Thread(target=serve_stream, args=(streamer, a.token, a.host, a.stream_port, handler),
                     daemon=True).start()

    while True:
        time.sleep(1)


if __name__ == "__main__":
    main()
