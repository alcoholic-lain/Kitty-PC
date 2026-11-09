package com.example.myapplication

import android.content.SharedPreferences
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// ------------------------------------------------------------------ model

enum class Kind(val label: String, val hint: String) {
    PAD("Gamepad button", "A, B, bumpers, triggers, D-pad…"),
    STICK("Analog stick", "Floating: appears under your thumb"),
    KEY("Keyboard key", "Held while pressed (WASD, Shift…)"),
    HOTKEY("Hotkey / combo", "e.g. alt+insert, ctrl+shift+s"),
    MOUSE("Mouse button", "Left / right / middle, held"),
    LOOK("Mouse look area", "Drag to move the mouse / camera"),
    SCREEN("PC screen", "The PC picture. Tap to click, or drag to move the mouse. Corner resizes."),
    TOUCHPAD("Touchpad", "Trackpad: move, tap to click, two-finger scroll and right click"),
    KEYBOARD("Keyboard", "Full on-screen keyboard"),
    TYPEBOX("Type box", "Phone keyboard. What you type appears on the PC"),
}

enum class BtnShape {
    CIRCLE, ROUND, PILL;

    fun toShape(): Shape = when (this) {
        CIRCLE -> CircleShape
        ROUND -> RoundedCornerShape(14.dp)
        PILL -> RoundedCornerShape(50)
    }
}

/**
 * One control on the canvas.
 * x, y = centre as a fraction (0..1) of the canvas; w, h = size in dp.
 * action: PAD = button name, STICK = "L"/"R", KEY = key name, HOTKEY = "alt+insert", MOUSE = "L"/"R"/"M".
 */
data class Elem(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind,
    val label: String,
    val action: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val shape: BtnShape = BtnShape.ROUND,
    val color: Long = 0L,        // 0 = default
    val alpha: Float = 1f,
    val sens: Float = 1f,
    val group: String = "",      // components with the same group id move and resize together; "" = alone
    val layer: Int = 0,          // 0 = back ... 3 = top. Components only push others on their own layer
)

/** Names for the layers, back to front. */
val LAYER_NAMES = listOf("Back", "Normal", "Front", "Top")

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val elems: List<Elem>,
    val landscape: Boolean = false,   // the board is drawn 700x320 and the phone is turned sideways
) {
    /** The design area this profile is laid out on. */
    val board: Board get() = if (landscape) LANDSCAPE_BOARD else PORTRAIT_BOARD
}

fun Elem.isZone() = kind == Kind.STICK || kind == Kind.LOOK

/** XInput bits. LT / RT are analog triggers, marked with negative numbers. */
val PAD_BITS: Map<String, Int> = linkedMapOf(
    "A" to 0x1000, "B" to 0x2000, "X" to 0x4000, "Y" to 0x8000,
    "LB" to 0x0100, "RB" to 0x0200, "LT" to -1, "RT" to -2,
    "L3" to 0x0040, "R3" to 0x0080,
    "Up" to 0x0001, "Down" to 0x0002, "Left" to 0x0004, "Right" to 0x0008,
    "View" to 0x0020, "Menu" to 0x0010, "Xbox" to 0x0400,
)

fun padLabel(name: String) = when (name) {
    "Up" -> "▲"; "Down" -> "▼"; "Left" -> "◀"; "Right" -> "▶"
    else -> name
}

fun newElem(kind: Kind): Elem = when (kind) {
    Kind.PAD -> Elem(kind = kind, label = "A", action = "A", x = .5f, y = .5f, w = 52f, h = 52f, shape = BtnShape.CIRCLE)
    Kind.STICK -> Elem(kind = kind, label = "", action = "L", x = .3f, y = .55f, w = 300f, h = 200f)
    Kind.KEY -> Elem(kind = kind, label = "Space", action = "space", x = .5f, y = .5f, w = 90f, h = 46f)
    Kind.HOTKEY -> Elem(kind = kind, label = "Hotkey", action = "alt+tab", x = .5f, y = .5f, w = 110f, h = 46f)
    Kind.MOUSE -> Elem(kind = kind, label = "Click", action = "L", x = .5f, y = .5f, w = 60f, h = 60f, shape = BtnShape.CIRCLE)
    Kind.LOOK -> Elem(kind = kind, label = "Look", action = "", x = .7f, y = .5f, w = 300f, h = 200f, sens = 1.2f)
    Kind.SCREEN -> Elem(kind = kind, label = "Screen", action = "", x = .5f, y = .5f, w = 360f, h = 220f)
    Kind.TOUCHPAD -> Elem(kind = kind, label = "Touchpad", action = "", x = .5f, y = .5f, w = 340f, h = 240f)
    Kind.KEYBOARD -> Elem(kind = kind, label = "Keyboard", action = "", x = .5f, y = .5f, w = 320f, h = 420f, layer = 2)
    Kind.TYPEBOX -> Elem(kind = kind, label = "Type", action = "", x = .5f, y = .5f, w = 300f, h = 56f)
}

// ------------------------------------------------------------------ json

private fun Elem.toJson() = JSONObject().apply {
    put("id", id); put("kind", kind.name); put("label", label); put("action", action)
    put("x", x.toDouble()); put("y", y.toDouble()); put("w", w.toDouble()); put("h", h.toDouble())
    put("shape", shape.name); put("color", color); put("alpha", alpha.toDouble()); put("sens", sens.toDouble())
    put("group", group); put("layer", layer)
}

private fun elemFromJson(o: JSONObject) = Elem(
    id = o.optString("id", UUID.randomUUID().toString()),
    kind = Kind.valueOf(o.getString("kind")),
    label = o.optString("label", ""),
    action = o.optString("action", ""),
    x = o.optDouble("x", 0.5).toFloat(), y = o.optDouble("y", 0.5).toFloat(),
    w = o.optDouble("w", 52.0).toFloat(), h = o.optDouble("h", 52.0).toFloat(),
    shape = runCatching { BtnShape.valueOf(o.optString("shape", "ROUND")) }.getOrDefault(BtnShape.ROUND),
    color = o.optLong("color", 0L),
    alpha = o.optDouble("alpha", 1.0).toFloat(),
    sens = o.optDouble("sens", 1.0).toFloat(),
    group = o.optString("group", ""),
    layer = o.optInt("layer", 0),
)

private fun Profile.toJson() = JSONObject().apply {
    put("id", id); put("name", name); put("landscape", landscape)
    put("elems", JSONArray(elems.map { it.toJson() }))
}

private fun profileFromJson(o: JSONObject): Profile {
    val arr = o.getJSONArray("elems")
    return Profile(
        id = o.getString("id"),
        name = o.getString("name"),
        elems = (0 until arr.length()).map { elemFromJson(arr.getJSONObject(it)) },
        landscape = o.optBoolean("landscape", false),
    )
}

// ------------------------------------------------------------------ storage

class ProfilesState(private val prefs: SharedPreferences) {
    /** Where profile actions are logged. The Controller screen sets it to the phone's UI log. */
    var logger: (String) -> Unit = {}

    var profiles by mutableStateOf(load())
        private set
    var activeId by mutableStateOf(prefs.getString("active", null) ?: profiles.first().id)
        private set

    val active: Profile get() = profiles.firstOrNull { it.id == activeId } ?: profiles.first()

    init {
        // The built-in layouts were redesigned. Once, swap saved copies of them for the new ones.
        // Profiles you made yourself (any other name) are never touched.
        if (prefs.getInt("tpl_version", 0) < TEMPLATE_VERSION) {
            profiles = profiles.map { p ->
                Templates.builtIn.firstOrNull { it.name == p.name }?.toProfile(p.id) ?: p
            }
            val missing = Templates.builtIn.filter { t -> profiles.none { it.name == t.name } }
            profiles = profiles + missing.map { it.toProfile() }
            prefs.edit().putInt("tpl_version", TEMPLATE_VERSION).apply()
            persist()
        }
    }

    private fun load(): List<Profile> {
        val raw = prefs.getString("profiles", null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                val list = (0 until arr.length()).map { profileFromJson(arr.getJSONObject(it)) }
                if (list.isNotEmpty()) return list
            } catch (_: Exception) { }
        }
        // first run: the four built-in profiles
        return Templates.builtIn.map { it.toProfile() }
    }

    private fun persist() {
        prefs.edit()
            .putString("profiles", JSONArray(profiles.map { it.toJson() }).toString())
            .putString("active", activeId)
            .apply()
    }

    private fun uniqueName(base: String): String {
        if (profiles.none { it.name == base }) return base
        var i = 2
        while (profiles.any { it.name == "$base $i" }) i++
        return "$base $i"
    }

    fun select(id: String) {
        activeId = id
        persist()
        logger("profile select '${profiles.firstOrNull { it.id == id }?.name}'")
    }

    fun setElems(id: String, elems: List<Elem>) {
        val cur = profiles.firstOrNull { it.id == id } ?: return
        if (cur.elems == elems) return
        profiles = profiles.map { if (it.id == id) it.copy(elems = elems) else it }
        persist()
    }

    fun addFromTemplate(t: Template) {
        logger("profile new from template '${t.name}'")
        val p = t.toProfile().copy(name = uniqueName(t.name))
        profiles = profiles + p
        activeId = p.id
        persist()
    }

    fun duplicate(id: String) {
        logger("profile duplicate '${profiles.firstOrNull { it.id == id }?.name}'")
        val p = profiles.firstOrNull { it.id == id } ?: return
        val copy = Profile(
            name = uniqueName(p.name + " copy"),
            elems = p.elems.map { it.copy(id = UUID.randomUUID().toString()) },
            landscape = p.landscape,
        )
        profiles = profiles + copy
        activeId = copy.id
        persist()
    }

    fun rename(id: String, name: String) {
        logger("profile rename to '$name'")
        profiles = profiles.map { if (it.id == id) it.copy(name = name) else it }
        persist()
    }

    /** Adds back any built-in profile (Touchpad, Keyboard, Controller, Screen) that is missing. */
    fun restoreBuiltIns() {
        logger("profile restore built-ins")
        val missing = Templates.builtIn.filter { t -> profiles.none { it.name == t.name } }
        if (missing.isEmpty()) return
        profiles = profiles + missing.map { it.toProfile() }
        persist()
    }

    /** Puts every built-in profile back to its original layout (your own profiles stay as they are). */
    fun resetBuiltIns() {
        logger("profile reset built-ins")
        profiles = profiles.map { p -> Templates.builtIn.firstOrNull { it.name == p.name }?.toProfile(p.id) ?: p }
        val missing = Templates.builtIn.filter { t -> profiles.none { it.name == t.name } }
        profiles = profiles + missing.map { it.toProfile() }
        persist()
    }

    /** Turns a profile's board between portrait and landscape. Components keep their relative spot. */
    fun setLandscape(id: String, landscape: Boolean) {
        logger("profile orientation ${if (landscape) "landscape" else "portrait"}")
        profiles = profiles.map { if (it.id == id) it.copy(landscape = landscape) else it }
        persist()
    }

    fun delete(id: String) {
        logger("profile delete '${profiles.firstOrNull { it.id == id }?.name}'")
        if (profiles.size <= 1) return
        profiles = profiles.filter { it.id != id }
        if (activeId == id) activeId = profiles.first().id
        persist()
    }
}

// ------------------------------------------------------------------ templates

/** Bump this when the built-in layouts change, so saved copies are replaced once. */
private const val TEMPLATE_VERSION = 2

/** Design boards, in dp. Portrait for a phone held upright, landscape for a phone held sideways. */
val PORTRAIT_BOARD = Board(320f, 700f)
val LANDSCAPE_BOARD = Board(700f, 320f)

class Template(
    val name: String,
    val desc: String,
    val landscape: Boolean,
    val build: () -> List<Elem>,
) {
    fun toProfile(id: String = UUID.randomUUID().toString()) =
        Profile(id = id, name = name, elems = build(), landscape = landscape)
}

/**
 * Turns "left, top, width, height in dp on the design board" into a stored Elem (centre as a fraction
 * of the board). Layers: 0 = back (touch areas), 1 = normal (buttons), 2 = front (keyboard).
 */
private class BoardSheet(val board: Board) {
    fun c(
        kind: Kind, label: String, action: String,
        left: Float, top: Float, w: Float, h: Float,
        shape: BtnShape = BtnShape.ROUND, color: Long = 0L,
        layer: Int = 1, alpha: Float = 1f, sens: Float = 1f,
    ) = Elem(
        kind = kind, label = label, action = action,
        x = (left + w / 2f) / board.w, y = (top + h / 2f) / board.h,
        w = w, h = h, shape = shape, color = color, alpha = alpha, sens = sens, layer = layer,
    )

    fun build(block: BoardSheet.() -> List<Elem>): List<Elem> = block()
}

private val PORT = BoardSheet(PORTRAIT_BOARD)
private val LAND = BoardSheet(LANDSCAPE_BOARD)

object Templates {
    // The four built-in profiles. "Reset built-ins" in the Profiles sheet puts them back.
    val touchpad = Template("Touchpad", "Trackpad, mouse buttons, arrows, editing keys and a type bar", false) { touchpadLayout() }
    val keyboard = Template("Keyboard", "Full on-screen keyboard (turns the phone sideways) with a shortcut row", true) { keyboardLayout() }
    val controller = Template("Controller", "Gamepad: touch sticks, D-pad, A B X Y, bumpers and triggers", true) { controllerLayout() }
    val screen = Template("Screen", "PC screen on top, touchpad, clicks, copy / paste and a type bar", false) { screenLayout() }
    val builtIn = listOf(touchpad, keyboard, controller, screen)

    val all = builtIn + listOf(
        Template("Screen (wide)", "Landscape: big PC picture with a side column of controls", true) { screenWideLayout() },
        Template("FPS (keyboard + mouse)", "WASD, mouse look, fire / aim, jump, sprint, crouch", true) { fpsLayout() },
        Template("Media (VLC)", "Play, seek, volume, fullscreen, subtitles and a touchpad", false) { mediaLayout() },
        Template("Presentation", "Next / previous slide, start, end, blank screen, laser pointer", false) { presentationLayout() },
        Template("Capture & streaming", "Screenshot, snip, NVIDIA / Xbox Game Bar shortcuts", false) { captureLayout() },
        Template("Blank", "Start from nothing and add your own buttons", false) { emptyList() },
    )

    // Generated from tools/templates/templates.py, which checks every layout for overlaps and for leaving the board.

    /** Trackpad, mouse buttons, arrows, editing keys and a type bar. Board 320x700 dp. */
    fun touchpadLayout(): List<Elem> = PORT.build { listOf(
        c(Kind.TOUCHPAD, "Touchpad", "", 0f, 0f, 320f, 396f),
        c(Kind.MOUSE, "Left click", "L", 0f, 404f, 156f, 52f, color = 0xFF4A90E2),
        c(Kind.MOUSE, "Right click", "R", 164f, 404f, 156f, 52f),
        c(Kind.KEY, "▲", "up", 62f, 466f, 56f, 48f),
        c(Kind.KEY, "◀", "left", 0f, 518f, 56f, 48f),
        c(Kind.KEY, "OK", "enter", 62f, 518f, 56f, 48f),
        c(Kind.KEY, "▶", "right", 124f, 518f, 56f, 48f),
        c(Kind.KEY, "▼", "down", 62f, 570f, 56f, 48f),
        c(Kind.KEY, "Esc", "esc", 188f, 466f, 64f, 48f),
        c(Kind.KEY, "⌫", "backspace", 256f, 466f, 64f, 48f),
        c(Kind.HOTKEY, "Copy", "ctrl+c", 188f, 518f, 64f, 48f),
        c(Kind.HOTKEY, "Paste", "ctrl+v", 256f, 518f, 64f, 48f),
        c(Kind.HOTKEY, "Cut", "ctrl+x", 188f, 570f, 64f, 48f),
        c(Kind.KEY, "Shift", "shift", 256f, 570f, 64f, 48f),
        c(Kind.TYPEBOX, "Type", "", 0f, 632f, 320f, 56f),
    ) }

    /** Full on-screen keyboard (rotates the phone) with shortcut row. Board 700x320 dp. */
    fun keyboardLayout(): List<Elem> = LAND.build { listOf(
        c(Kind.KEYBOARD, "Keyboard", "", 0f, 0f, 700f, 256f),
        c(Kind.HOTKEY, "Copy", "ctrl+c", 0f, 264f, 94f, 52f),
        c(Kind.HOTKEY, "Cut", "ctrl+x", 101f, 264f, 94f, 52f),
        c(Kind.HOTKEY, "Paste", "ctrl+v", 202f, 264f, 94f, 52f),
        c(Kind.HOTKEY, "Undo", "ctrl+z", 303f, 264f, 94f, 52f),
        c(Kind.HOTKEY, "Select all", "ctrl+a", 404f, 264f, 94f, 52f),
        c(Kind.HOTKEY, "Alt+Tab", "alt+tab", 505f, 264f, 94f, 52f),
        c(Kind.HOTKEY, "Close", "alt+f4", 606f, 264f, 94f, 52f),
    ) }

    /** Gamepad: touch sticks, D-pad, A B X Y, bumpers and triggers. Board 700x320 dp. */
    fun controllerLayout(): List<Elem> = LAND.build { listOf(
        c(Kind.STICK, "Left stick", "L", 0f, 44f, 350f, 276f, layer = 0, alpha = 0.35f),
        c(Kind.STICK, "Right stick", "R", 350f, 44f, 350f, 276f, layer = 0, alpha = 0.35f),
        c(Kind.PAD, "LT", "LT", 0f, 0f, 84f, 40f),
        c(Kind.PAD, "LB", "LB", 92f, 0f, 84f, 40f),
        c(Kind.PAD, "RB", "RB", 524f, 0f, 84f, 40f),
        c(Kind.PAD, "RT", "RT", 616f, 0f, 84f, 40f),
        c(Kind.PAD, "View", "View", 250f, 6f, 60f, 30f, shape = BtnShape.PILL),
        c(Kind.PAD, "Xbox", "Xbox", 320f, 6f, 60f, 30f, shape = BtnShape.PILL),
        c(Kind.PAD, "Menu", "Menu", 390f, 6f, 60f, 30f, shape = BtnShape.PILL),
        c(Kind.PAD, "▲", "Up", 56f, 160f, 48f, 48f),
        c(Kind.PAD, "◀", "Left", 4f, 212f, 48f, 48f),
        c(Kind.PAD, "▶", "Right", 108f, 212f, 48f, 48f),
        c(Kind.PAD, "▼", "Down", 56f, 264f, 48f, 48f),
        c(Kind.PAD, "Y", "Y", 596f, 160f, 48f, 48f, shape = BtnShape.CIRCLE, color = 0xFFF2C230),
        c(Kind.PAD, "X", "X", 544f, 212f, 48f, 48f, shape = BtnShape.CIRCLE, color = 0xFF4A90E2),
        c(Kind.PAD, "B", "B", 648f, 212f, 48f, 48f, shape = BtnShape.CIRCLE, color = 0xFFE34B4B),
        c(Kind.PAD, "A", "A", 596f, 264f, 48f, 48f, shape = BtnShape.CIRCLE, color = 0xFF5BC236),
        c(Kind.PAD, "L3", "L3", 290f, 272f, 50f, 40f, shape = BtnShape.PILL),
        c(Kind.PAD, "R3", "R3", 360f, 272f, 50f, 40f, shape = BtnShape.PILL),
    ) }

    /** PC screen on top, touchpad, clicks, copy / paste and a type bar. Board 320x700 dp. */
    fun screenLayout(): List<Elem> = PORT.build { listOf(
        c(Kind.SCREEN, "Screen", "", 0f, 0f, 320f, 180f),
        c(Kind.TOUCHPAD, "Touchpad", "", 0f, 188f, 320f, 270f),
        c(Kind.MOUSE, "Left click", "L", 0f, 466f, 156f, 52f, color = 0xFF4A90E2),
        c(Kind.MOUSE, "Right click", "R", 164f, 466f, 156f, 52f),
        c(Kind.HOTKEY, "Copy", "ctrl+c", 0f, 526f, 74f, 44f),
        c(Kind.HOTKEY, "Cut", "ctrl+x", 82f, 526f, 74f, 44f),
        c(Kind.HOTKEY, "Paste", "ctrl+v", 164f, 526f, 74f, 44f),
        c(Kind.HOTKEY, "Undo", "ctrl+z", 246f, 526f, 74f, 44f),
        c(Kind.KEY, "Esc", "esc", 0f, 578f, 74f, 44f),
        c(Kind.KEY, "Tab", "tab", 82f, 578f, 74f, 44f),
        c(Kind.HOTKEY, "Alt+Tab", "alt+tab", 164f, 578f, 74f, 44f),
        c(Kind.KEY, "Win", "cmd", 246f, 578f, 74f, 44f),
        c(Kind.TYPEBOX, "Type", "", 0f, 630f, 320f, 56f),
    ) }

    /** Landscape: big PC picture with a side column of controls. Board 700x320 dp. */
    fun screenWideLayout(): List<Elem> = LAND.build { listOf(
        c(Kind.SCREEN, "Screen", "", 0f, 4f, 552f, 311f),
        c(Kind.MOUSE, "Left click", "L", 560f, 0f, 140f, 52f, color = 0xFF4A90E2),
        c(Kind.MOUSE, "Right click", "R", 560f, 58f, 140f, 52f),
        c(Kind.HOTKEY, "Copy", "ctrl+c", 560f, 116f, 66f, 44f),
        c(Kind.HOTKEY, "Paste", "ctrl+v", 634f, 116f, 66f, 44f),
        c(Kind.HOTKEY, "Cut", "ctrl+x", 560f, 166f, 66f, 44f),
        c(Kind.HOTKEY, "Undo", "ctrl+z", 634f, 166f, 66f, 44f),
        c(Kind.KEY, "Esc", "esc", 560f, 216f, 66f, 44f),
        c(Kind.HOTKEY, "Alt+Tab", "alt+tab", 634f, 216f, 66f, 44f),
        c(Kind.TYPEBOX, "Type", "", 560f, 268f, 140f, 52f),
    ) }

    /** WASD, mouse look, fire / aim, jump, sprint, crouch. Board 700x320 dp. */
    fun fpsLayout(): List<Elem> = LAND.build { listOf(
        c(Kind.LOOK, "Look", "", 350f, 44f, 350f, 276f, layer = 0, alpha = 0.35f, sens = 1.3f),
        c(Kind.KEY, "Sprint", "shift", 0f, 0f, 84f, 40f),
        c(Kind.KEY, "Crouch", "ctrl", 92f, 0f, 84f, 40f),
        c(Kind.KEY, "Tab", "tab", 290f, 6f, 56f, 30f, shape = BtnShape.PILL),
        c(Kind.KEY, "Esc", "esc", 354f, 6f, 56f, 30f, shape = BtnShape.PILL),
        c(Kind.KEY, "W", "w", 64f, 172f, 56f, 56f),
        c(Kind.KEY, "A", "a", 4f, 232f, 56f, 56f),
        c(Kind.KEY, "S", "s", 64f, 232f, 56f, 56f),
        c(Kind.KEY, "D", "d", 124f, 232f, 56f, 56f),
        c(Kind.KEY, "R", "r", 192f, 176f, 52f, 52f),
        c(Kind.KEY, "E", "e", 192f, 232f, 52f, 52f),
        c(Kind.KEY, "Jump", "space", 632f, 56f, 60f, 60f, shape = BtnShape.CIRCLE),
        c(Kind.MOUSE, "Fire", "L", 576f, 150f, 84f, 84f, shape = BtnShape.CIRCLE, color = 0xFF9B6BFF),
        c(Kind.MOUSE, "Aim", "R", 488f, 222f, 64f, 64f, shape = BtnShape.CIRCLE),
    ) }

    /** Play, seek, volume, fullscreen, subtitles and a touchpad. Board 320x700 dp. */
    fun mediaLayout(): List<Elem> = PORT.build { listOf(
        c(Kind.HOTKEY, "▶  Play / Pause", "space", 0f, 0f, 320f, 150f, color = 0xFF9B6BFF),
        c(Kind.HOTKEY, "Prev", "p", 0f, 158f, 74f, 84f),
        c(Kind.HOTKEY, "−10 s", "alt+left", 82f, 158f, 74f, 84f),
        c(Kind.HOTKEY, "+10 s", "alt+right", 164f, 158f, 74f, 84f),
        c(Kind.HOTKEY, "Next", "n", 246f, 158f, 74f, 84f),
        c(Kind.HOTKEY, "Vol −", "ctrl+down", 0f, 250f, 74f, 84f),
        c(Kind.HOTKEY, "Vol +", "ctrl+up", 82f, 250f, 74f, 84f),
        c(Kind.HOTKEY, "Mute", "m", 164f, 250f, 74f, 84f),
        c(Kind.HOTKEY, "Full", "f", 246f, 250f, 74f, 84f),
        c(Kind.HOTKEY, "Subs", "v", 0f, 342f, 102f, 84f),
        c(Kind.HOTKEY, "Audio", "b", 109f, 342f, 102f, 84f),
        c(Kind.HOTKEY, "Stop", "s", 218f, 342f, 102f, 84f),
        c(Kind.TOUCHPAD, "Touchpad", "", 0f, 434f, 320f, 200f),
        c(Kind.TYPEBOX, "Type", "", 0f, 642f, 320f, 48f),
    ) }

    /** Next / previous slide, start, end, blank screen, laser pointer. Board 320x700 dp. */
    fun presentationLayout(): List<Elem> = PORT.build { listOf(
        c(Kind.HOTKEY, "Start (F5)", "f5", 0f, 0f, 156f, 56f),
        c(Kind.HOTKEY, "End (Esc)", "esc", 164f, 0f, 156f, 56f),
        c(Kind.HOTKEY, "Next  ▶", "right", 0f, 64f, 320f, 230f, color = 0xFF9B6BFF),
        c(Kind.HOTKEY, "◀  Previous", "left", 0f, 302f, 320f, 150f),
        c(Kind.HOTKEY, "Black", "b", 0f, 460f, 156f, 60f),
        c(Kind.HOTKEY, "White", "w", 164f, 460f, 156f, 60f),
        c(Kind.TOUCHPAD, "Pointer", "", 0f, 528f, 320f, 160f),
    ) }

    /** Screenshot, snip, NVIDIA / Xbox Game Bar shortcuts. Board 320x700 dp. */
    fun captureLayout(): List<Elem> = PORT.build { listOf(
        c(Kind.HOTKEY, "Screenshot", "print_screen", 0f, 0f, 156f, 112f),
        c(Kind.HOTKEY, "Snip", "cmd+shift+s", 164f, 0f, 156f, 112f),
        c(Kind.HOTKEY, "NVIDIA shot", "alt+f1", 0f, 120f, 156f, 112f),
        c(Kind.HOTKEY, "Save replay", "alt+f10", 164f, 120f, 156f, 112f),
        c(Kind.HOTKEY, "Record", "cmd+alt+r", 0f, 240f, 156f, 112f),
        c(Kind.HOTKEY, "Game bar", "cmd+g", 164f, 240f, 156f, 112f),
        c(Kind.HOTKEY, "Overlay", "alt+z", 0f, 360f, 156f, 112f),
        c(Kind.HOTKEY, "Alt+Insert", "alt+insert", 164f, 360f, 156f, 112f),
    ) }

}
