# Changes

Copy these files over your project (same paths). Nothing else is touched.

## Keyboard (Instagram-like)
- The board no longer resizes when the keyboard opens (the old `imePadding()` on the whole remote re-ran the layout every frame, which was the lag and the shrinking).
- Tapping a Type box opens one bar that rides on top of the keyboard (animated with it). Back / tapping outside closes bar + keyboard.
- Bar has Gallery, Clipboard, Backspace, Tab, Esc and Enter.

## Images
- Gallery (system photo picker) or phone clipboard (image, or text which is typed on the PC).
- Preview sheet: "Send and paste on the PC" or "Send to PC clipboard only".
- Server: same port as the screen stream (default 5006), no new firewall rule. Saved in server/uploads/, put on the PC clipboard (Windows CF_DIB, macOS osascript, Linux xclip / wl-copy), then Ctrl+V.

## Templates / UI
- Templates rebuilt from one spec (tools/templates/templates.py) that checks overlaps and bounds. Controller, FPS, Keyboard, Screen wide are landscape and turn the phone; others portrait. Edit toolbar has a Portrait/Landscape toggle.
- Touch areas on the back layer, buttons on top; pressed feedback; readable labels on coloured buttons; opacity slider in the editor.
- Sticks are now truly floating (centre = where the thumb lands).
- Built-in profiles (Touchpad, Keyboard, Controller, Screen) are replaced once on first launch of this version. Your own profiles are kept. "Reset built-ins to default" in Profiles does it on demand.

## Bugs fixed
- Opening Settings from the remote closed the connection; commands were dead after coming back.
- Left-edge swipe opened the menu while using the left stick.

## Round 2: full screen + clicking
- Taps never registered: the touch handler restarted on every new screen picture (about 15x a second), cancelling the touch. Fixed.
- Full screen now really is full screen: the PC picture fills the whole phone (turns sideways), no board or frame.
- Direct touch like a remote desktop: tap = click, two taps = double click, hold = right click, drag = drag, two fingers = scroll, two-finger tap = right click. Corner bar: Exit / Touch <-> Chromebook Trackpad / Type (folds away after 4 s).
- Fill mode now maps taps correctly (it was only right for Fit).
- Server (Windows): DPI aware + SetCursorPos, so clicks land correctly with display scaling. RESTART THE SERVER.
- Connect screen shows "UI build 3" so you can tell which build is installed.
