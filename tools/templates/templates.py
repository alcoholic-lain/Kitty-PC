"""
Single source of truth for the built-in HUD templates.

  * checks every layout with the same rules as Layout.kt (same-layer overlap, inside the board)
  * renders a preview PNG per template (a mockup drawn from the layout data, not a phone screenshot)
  * emits the Kotlin for Templates in Profiles.kt

Boards (design units, dp): portrait 320x700, landscape 700x320.
"""
import sys
from PIL import Image, ImageDraw, ImageFont

PORTRAIT = (320, 700)
LANDSCAPE = (700, 320)

RED, GREEN, BLUE, YELLOW, PURPLE = 0xFFE34B4B, 0xFF5BC236, 0xFF4A90E2, 0xFFF2C230, 0xFF9B6BFF

ARROW = {"up": "▲", "down": "▼", "left": "◀", "right": "▶"}


class T:
    def __init__(self, name, desc, landscape, builtin=False):
        self.name, self.desc, self.landscape, self.builtin = name, desc, landscape, builtin
        self.w, self.h = LANDSCAPE if landscape else PORTRAIT
        self.items = []

    def add(self, kind, label, action, l, t, w, h, shape="ROUND", color=0, layer=1, alpha=1.0, sens=1.0):
        self.items.append(dict(kind=kind, label=label, action=action, l=l, t=t, w=w, h=h,
                               shape=shape, color=color, layer=layer, alpha=alpha, sens=sens))

    # shorthands
    def pad(self, name, l, t, w, h=None, shape="ROUND", color=0):
        label = {"Up": "▲", "Down": "▼", "Left": "◀", "Right": "▶"}.get(name, name)
        self.add("PAD", label, name, l, t, w, h or w, shape, color)

    def key(self, label, k, l, t, w, h, shape="ROUND", color=0):
        self.add("KEY", label, k, l, t, w, h, shape, color)

    def hk(self, label, combo, l, t, w, h, shape="ROUND", color=0):
        self.add("HOTKEY", label, combo, l, t, w, h, shape, color)

    def mouse(self, label, b, l, t, w, h, shape="ROUND", color=0):
        self.add("MOUSE", label, b, l, t, w, h, shape, color)

    def zone(self, kind, label, action, l, t, w, h, sens=1.0):
        # big touch areas sit on the back layer, faint, so buttons can sit on top of them
        self.add(kind, label, action, l, t, w, h, "ROUND", 0, 0, 0.35, sens)


def check(t):
    problems = []
    for i, a in enumerate(t.items):
        if a["l"] < 0 or a["t"] < 0 or a["l"] + a["w"] > t.w + 0.01 or a["t"] + a["h"] > t.h + 0.01:
            problems.append(f"outside board: {a['kind']} '{a['label']}' {a['l']},{a['t']} {a['w']}x{a['h']}")
        if min(a["w"], a["h"]) < 28:
            problems.append(f"too small to hit: '{a['label']}' {a['w']}x{a['h']}")
        for b in t.items[i + 1:]:
            if a["layer"] == b["layer"] and a["l"] < b["l"] + b["w"] and b["l"] < a["l"] + a["w"] \
                    and a["t"] < b["t"] + b["h"] and b["t"] < a["t"] + a["h"]:
                problems.append(f"overlap on layer {a['layer']}: '{a['label']}' and '{b['label']}'")
    return problems


# ------------------------------------------------------------------ portrait

def touchpad():
    t = T("Touchpad", "Trackpad, mouse buttons, arrows, editing keys and a type bar", False, True)
    t.add("TOUCHPAD", "Touchpad", "", 0, 0, 320, 396)
    t.mouse("Left click", "L", 0, 404, 156, 52, color=BLUE)
    t.mouse("Right click", "R", 164, 404, 156, 52)
    # arrow cross (left) and editing keys (right)
    t.key("▲", "up", 62, 466, 56, 48)
    t.key("◀", "left", 0, 518, 56, 48)
    t.key("OK", "enter", 62, 518, 56, 48)
    t.key("▶", "right", 124, 518, 56, 48)
    t.key("▼", "down", 62, 570, 56, 48)
    t.key("Esc", "esc", 188, 466, 64, 48)
    t.key("⌫", "backspace", 256, 466, 64, 48)
    t.hk("Copy", "ctrl+c", 188, 518, 64, 48)
    t.hk("Paste", "ctrl+v", 256, 518, 64, 48)
    t.hk("Cut", "ctrl+x", 188, 570, 64, 48)
    t.key("Shift", "shift", 256, 570, 64, 48)
    t.add("TYPEBOX", "Type", "", 0, 632, 320, 56)
    return t


def keyboard():
    t = T("Keyboard", "Full on-screen keyboard (rotates the phone) with shortcut row", True, True)
    t.add("KEYBOARD", "Keyboard", "", 0, 0, 700, 256, layer=1)
    names = [("Copy", "ctrl+c"), ("Cut", "ctrl+x"), ("Paste", "ctrl+v"), ("Undo", "ctrl+z"),
             ("Select all", "ctrl+a"), ("Alt+Tab", "alt+tab"), ("Close", "alt+f4")]
    for i, (lab, combo) in enumerate(names):
        t.hk(lab, combo, i * 101, 264, 94, 52)
    return t


def controller():
    t = T("Controller", "Gamepad: touch sticks, D-pad, A B X Y, bumpers and triggers", True, True)
    t.zone("STICK", "Left stick", "L", 0, 44, 350, 276)
    t.zone("STICK", "Right stick", "R", 350, 44, 350, 276)
    t.pad("LT", 0, 0, 84, 40); t.pad("LB", 92, 0, 84, 40)
    t.pad("RB", 524, 0, 84, 40); t.pad("RT", 616, 0, 84, 40)
    t.pad("View", 250, 6, 60, 30, "PILL"); t.pad("Xbox", 320, 6, 60, 30, "PILL"); t.pad("Menu", 390, 6, 60, 30, "PILL")
    t.pad("Up", 56, 160, 48); t.pad("Left", 4, 212, 48); t.pad("Right", 108, 212, 48); t.pad("Down", 56, 264, 48)
    t.pad("Y", 596, 160, 48, shape="CIRCLE", color=YELLOW)
    t.pad("X", 544, 212, 48, shape="CIRCLE", color=BLUE)
    t.pad("B", 648, 212, 48, shape="CIRCLE", color=RED)
    t.pad("A", 596, 264, 48, shape="CIRCLE", color=GREEN)
    t.pad("L3", 290, 272, 50, 40, "PILL"); t.pad("R3", 360, 272, 50, 40, "PILL")
    return t


def screen():
    t = T("Screen", "PC screen on top, touchpad, clicks, copy / paste and a type bar", False, True)
    t.add("SCREEN", "Screen", "", 0, 0, 320, 180)
    t.add("TOUCHPAD", "Touchpad", "", 0, 188, 320, 270)
    t.mouse("Left click", "L", 0, 466, 156, 52, color=BLUE)
    t.mouse("Right click", "R", 164, 466, 156, 52)
    for i, (lab, combo) in enumerate([("Copy", "ctrl+c"), ("Cut", "ctrl+x"), ("Paste", "ctrl+v"), ("Undo", "ctrl+z")]):
        t.hk(lab, combo, i * 82, 526, 74, 44)
    t.key("Esc", "esc", 0, 578, 74, 44)
    t.key("Tab", "tab", 82, 578, 74, 44)
    t.hk("Alt+Tab", "alt+tab", 164, 578, 74, 44)
    t.key("Win", "cmd", 246, 578, 74, 44)
    t.add("TYPEBOX", "Type", "", 0, 630, 320, 56)
    return t


# ------------------------------------------------------------------ extras

def screen_wide():
    t = T("Screen (wide)", "Landscape: big PC picture with a side column of controls", True)
    t.add("SCREEN", "Screen", "", 0, 4, 552, 311)
    t.mouse("Left click", "L", 560, 0, 140, 52, color=BLUE)
    t.mouse("Right click", "R", 560, 58, 140, 52)
    t.hk("Copy", "ctrl+c", 560, 116, 66, 44); t.hk("Paste", "ctrl+v", 634, 116, 66, 44)
    t.hk("Cut", "ctrl+x", 560, 166, 66, 44); t.hk("Undo", "ctrl+z", 634, 166, 66, 44)
    t.key("Esc", "esc", 560, 216, 66, 44); t.hk("Alt+Tab", "alt+tab", 634, 216, 66, 44)
    t.add("TYPEBOX", "Type", "", 560, 268, 140, 52)
    return t


def fps():
    t = T("FPS (keyboard + mouse)", "WASD, mouse look, fire / aim, jump, sprint, crouch", True)
    t.zone("LOOK", "Look", "", 350, 44, 350, 276, sens=1.3)
    t.key("Sprint", "shift", 0, 0, 84, 40); t.key("Crouch", "ctrl", 92, 0, 84, 40)
    t.key("Tab", "tab", 290, 6, 56, 30, "PILL"); t.key("Esc", "esc", 354, 6, 56, 30, "PILL")
    t.key("W", "w", 64, 172, 56, 56); t.key("A", "a", 4, 232, 56, 56)
    t.key("S", "s", 64, 232, 56, 56); t.key("D", "d", 124, 232, 56, 56)
    t.key("R", "r", 192, 176, 52, 52); t.key("E", "e", 192, 232, 52, 52)
    t.key("Jump", "space", 632, 56, 60, 60, "CIRCLE")
    t.mouse("Fire", "L", 576, 150, 84, 84, "CIRCLE", PURPLE)
    t.mouse("Aim", "R", 488, 222, 64, 64, "CIRCLE")
    return t


def media():
    t = T("Media (VLC)", "Play, seek, volume, fullscreen, subtitles and a touchpad", False)
    t.hk("▶  Play / Pause", "space", 0, 0, 320, 150, color=PURPLE)
    row1 = [("Prev", "p"), ("−10 s", "alt+left"), ("+10 s", "alt+right"), ("Next", "n")]
    row2 = [("Vol −", "ctrl+down"), ("Vol +", "ctrl+up"), ("Mute", "m"), ("Full", "f")]
    for i, (lab, c) in enumerate(row1):
        t.hk(lab, c, i * 82, 158, 74, 84)
    for i, (lab, c) in enumerate(row2):
        t.hk(lab, c, i * 82, 250, 74, 84)
    for i, (lab, c) in enumerate([("Subs", "v"), ("Audio", "b"), ("Stop", "s")]):
        t.hk(lab, c, i * 109, 342, 102, 84)
    t.add("TOUCHPAD", "Touchpad", "", 0, 434, 320, 200)
    t.add("TYPEBOX", "Type", "", 0, 642, 320, 48)
    return t


def presentation():
    t = T("Presentation", "Next / previous slide, start, end, blank screen, laser pointer", False)
    t.hk("Start (F5)", "f5", 0, 0, 156, 56); t.hk("End (Esc)", "esc", 164, 0, 156, 56)
    t.hk("Next  ▶", "right", 0, 64, 320, 230, color=PURPLE)
    t.hk("◀  Previous", "left", 0, 302, 320, 150)
    t.hk("Black", "b", 0, 460, 156, 60); t.hk("White", "w", 164, 460, 156, 60)
    t.add("TOUCHPAD", "Pointer", "", 0, 528, 320, 160)
    return t


def capture():
    t = T("Capture & streaming", "Screenshot, snip, NVIDIA / Xbox Game Bar shortcuts", False)
    items = [("Screenshot", "print_screen"), ("Snip", "cmd+shift+s"),
             ("NVIDIA shot", "alt+f1"), ("Save replay", "alt+f10"),
             ("Record", "cmd+alt+r"), ("Game bar", "cmd+g"),
             ("Overlay", "alt+z"), ("Alt+Insert", "alt+insert")]
    for i, (lab, c) in enumerate(items):
        t.hk(lab, c, (i % 2) * 164, (i // 2) * 120, 156, 112)
    return t


ALL = [touchpad(), keyboard(), controller(), screen(),
       screen_wide(), fps(), media(), presentation(), capture()]


# ------------------------------------------------------------------ kotlin

def kt_func_name(t):
    return {
        "Touchpad": "touchpadLayout", "Keyboard": "keyboardLayout", "Controller": "controllerLayout",
        "Screen": "screenLayout", "Screen (wide)": "screenWideLayout", "FPS (keyboard + mouse)": "fpsLayout",
        "Media (VLC)": "mediaLayout", "Presentation": "presentationLayout", "Capture & streaming": "captureLayout",
    }[t.name]


def kt_str(s):
    return s.replace("\\", "\\\\").replace('"', '\\"')


def emit_kotlin():
    out = []
    for t in ALL:
        sheet = "LAND" if t.landscape else "PORT"
        out.append(f"    /** {t.desc}. Board {t.w}x{t.h} dp. */")
        out.append(f"    fun {kt_func_name(t)}(): List<Elem> = {sheet}.build {{ listOf(")
        for a in t.items:
            parts = [f'Kind.{a["kind"]}', f'"{kt_str(a["label"])}"', f'"{kt_str(a["action"])}"',
                     f'{a["l"]}f', f'{a["t"]}f', f'{a["w"]}f', f'{a["h"]}f']
            named = []
            if a["shape"] != "ROUND": named.append(f'shape = BtnShape.{a["shape"]}')
            if a["color"]: named.append(f'color = 0x{a["color"]:08X}')
            if a["layer"] != 1: named.append(f'layer = {a["layer"]}')
            if a["alpha"] != 1.0: named.append(f'alpha = {a["alpha"]}f')
            if a["sens"] != 1.0: named.append(f'sens = {a["sens"]}f')
            out.append(f'        c({", ".join(parts + named)}),')
        out.append("    ) }")
        out.append("")
    return "\n".join(out)


# ------------------------------------------------------------------ preview

BG, SURFACE, OUTLINE, TEXT, MUTED = (14, 15, 18), (24, 26, 31), (44, 47, 54), (230, 232, 236), (154, 160, 170)
ACCENT = (138, 180, 255)
S = 2  # preview scale


def font(size, bold=False):
    p = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
    return ImageFont.truetype(p, size)


def mix(a, b, f):
    return tuple(int(a[i] + (b[i] - a[i]) * f) for i in range(3))


def lum(c):
    return (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) / 255


def render(t):
    pad = 12
    img = Image.new("RGB", ((t.w + pad * 2) * S, (t.h + pad * 2) * S + 0), BG)
    d = ImageDraw.Draw(img, "RGBA")
    d.rounded_rectangle([pad * S - 4, pad * S - 4, (pad + t.w) * S + 4, (pad + t.h) * S + 4], 6, outline=(30, 32, 38), width=1)
    for a in sorted(t.items, key=lambda x: x["layer"]):
        x0, y0 = (pad + a["l"]) * S, (pad + a["t"]) * S
        x1, y1 = x0 + a["w"] * S, y0 + a["h"] * S
        r = {"CIRCLE": min(a["w"], a["h"]) * S / 2, "PILL": min(a["w"], a["h"]) * S / 2}.get(a["shape"], 14 * S * 0.5)
        col = a["color"]
        base = ((col >> 16) & 255, (col >> 8) & 255, col & 255) if col else SURFACE
        fill = base + (int(255 * a["alpha"]),)
        edge = mix(base, (0, 0, 0), 0.3) if col else OUTLINE
        kind = a["kind"]
        if kind == "SCREEN":
            d.rounded_rectangle([x0, y0, x1, y1], r, fill=(32, 36, 46), outline=ACCENT, width=2)
            f = font(13 * S // 2 * 1)
            d.text(((x0 + x1) / 2, (y0 + y1) / 2), "PC screen", fill=MUTED, font=f, anchor="mm")
            continue
        if kind == "KEYBOARD":
            d.rounded_rectangle([x0, y0, x1, y1], r, fill=fill, outline=edge, width=2)
            rows = [15, 14, 13, 13, 8]
            ky = y0 + 5 * S
            kh = (y1 - y0 - 10 * S) / 5
            for n in rows:
                kw = (x1 - x0 - 10 * S) / n
                for k in range(n):
                    d.rounded_rectangle([x0 + 5 * S + k * kw + 1, ky + 1, x0 + 5 * S + (k + 1) * kw - 1, ky + kh - 2], 5,
                                        fill=(32, 35, 41), outline=OUTLINE)
                ky += kh
            continue
        d.rounded_rectangle([x0, y0, x1, y1], r, fill=fill, outline=edge, width=2)
        if kind == "TOUCHPAD":
            d.text(((x0 + x1) / 2, (y0 + y1) / 2), a["label"], fill=MUTED, font=font(13 * S // 2), anchor="mm")
            continue
        if kind == "TYPEBOX":
            d.text((x0 + 14 * S // 1, (y0 + y1) / 2), "⌨  Tap to type…", fill=MUTED, font=font(13 * S // 2), anchor="lm")
            continue
        if kind in ("STICK", "LOOK"):
            d.text(((x0 + x1) / 2, (y0 + y1) / 2), a["label"], fill=MUTED, font=font(13 * S // 2), anchor="mm")
            continue
        tc = ((17, 17, 17) if lum(base) > 0.55 else (255, 255, 255)) if col else TEXT
        fs = 15 if min(a["w"], a["h"]) >= 44 else 12
        d.text(((x0 + x1) / 2, (y0 + y1) / 2), a["label"], fill=tc, font=font(fs * S // 2 + 2, True), anchor="mm")
    return img


if __name__ == "__main__":
    bad = False
    for t in ALL:
        p = check(t)
        n = len(t.items)
        print(f"{t.name:28s} {'landscape' if t.landscape else 'portrait ':9s} {n:3d} items  {'OK' if not p else 'PROBLEMS'}")
        for line in p:
            print("   ", line)
            bad = True
    if "--kotlin" in sys.argv:
        open("/home/claude/tpl/templates.kt.txt", "w").write(emit_kotlin())
    if "--render" in sys.argv:
        for t in ALL:
            name = t.name.lower().replace(" ", "_").replace("(", "").replace(")", "").replace("&", "and").replace("+", "plus")
            render(t).save(f"/home/claude/tpl/{name}.png")
    sys.exit(1 if bad else 0)
