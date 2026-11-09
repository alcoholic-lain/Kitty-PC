package com.example.myapplication

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AZERTY 60% layout.
 *  - Shift / Ctrl / Alt / AltGr are sticky (one-shot): tap, then tap a key.
 *  - Ctrl or Alt + key sends a real hotkey (Alt then Tab = Alt+Tab).
 *  - AltGr gives @ # { [ | ` \ ^ ] } ~ €
 *  - Fn (stays on until tapped again): 1..= -> F1..F12, I/J/K/L -> arrows,
 *    U/O -> Home/End, P/M -> PgUp/PgDn, Backspace -> Delete.
 * Characters are sent as text, so it works whatever layout the PC uses.
 */
private data class Key(
    val base: String,
    val w: Float = 1f,
    val shift: String? = null,
    val alt: String? = null,
    val fn: Pair<String, String>? = null,   // label to pynput key name
    val special: String? = null,            // key name, or "MOD:<id>"
)

private fun k(base: String, shift: String? = null, alt: String? = null, fn: Pair<String, String>? = null) =
    Key(base, 1f, shift, alt, fn)
private fun sp(label: String, w: Float, name: String, fn: Pair<String, String>? = null) =
    Key(label, w, special = name, fn = fn)
private fun mod(label: String, w: Float, id: String) = Key(label, w, special = "MOD:$id")

private fun f(n: Int) = "F$n" to "f$n"
private val UP = "↑" to "up"; private val DOWN = "↓" to "down"
private val LEFT = "←" to "left"; private val RIGHT = "→" to "right"

private val ROWS = listOf(
    listOf(
        sp("Esc", 1f, "esc"), k("²"),
        k("&", "1", fn = f(1)), k("é", "2", "~", f(2)), k("\"", "3", "#", f(3)),
        k("'", "4", "{", f(4)), k("(", "5", "[", f(5)), k("-", "6", "|", f(6)),
        k("è", "7", "`", f(7)), k("_", "8", "\\", f(8)), k("ç", "9", "^", f(9)),
        k("à", "0", "@", f(10)), k(")", "°", "]", f(11)), k("=", "+", "}", f(12)),
        sp("⌫", 2f, "backspace", "Del" to "delete"),
    ),
    listOf(
        sp("Tab", 1.5f, "tab"),
        k("a"), k("z"), k("e", alt = "€"), k("r"), k("t"), k("y"),
        k("u", fn = "Home" to "home"), k("i", fn = UP), k("o", fn = "End" to "end"),
        k("p", fn = "PgUp" to "page_up"),
        k("^", "¨"), k("$", "£", "¤"), k("*", "µ").copy(w = 1.5f),
    ),
    listOf(
        sp("Caps", 1.75f, "caps_lock"),
        k("q"), k("s"), k("d"), k("f"), k("g"), k("h"),
        k("j", fn = LEFT), k("k", fn = DOWN), k("l", fn = RIGHT),
        k("m", fn = "PgDn" to "page_down"), k("ù", "%"),
        sp("↵", 2.25f, "enter"),
    ),
    listOf(
        mod("⇧", 1.25f, "shift"),
        k("<", ">"), k("w"), k("x"), k("c"), k("v"), k("b"), k("n"),
        k(",", "?"), k(";", "."), k(":", "/"), k("!", "§"),
        mod("⇧", 2.75f, "shift"),
    ),
    listOf(
        mod("Ctrl", 1.25f, "ctrl"), sp("Win", 1.25f, "cmd"), mod("Alt", 1.25f, "alt"),
        sp("", 6.25f, "space"),
        mod("AltGr", 1.25f, "altgr"), mod("Fn", 1.25f, "fn"),
        sp("Menu", 1.25f, "menu"), mod("Ctrl", 1.25f, "ctrl"),
    ),
)

@Composable
fun OnScreenKeyboard(c: UdpClient) {
    var mods by remember { mutableStateOf(setOf<String>()) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val keyH = if (landscape) 46.dp else 44.dp
    val fs = if (landscape) 15.sp else 11.sp

    fun clearOneShot() { mods = mods.filter { it == "fn" }.toSet() }

    fun label(key: Key): String = when {
        key.special?.startsWith("MOD:") == true -> key.base
        "fn" in mods && key.fn != null -> key.fn.first
        "shift" in mods && key.special == null -> key.shift ?: key.base.uppercase()
        else -> key.base
    }

    fun press(key: Key) {
        val s = key.special
        if (s != null && s.startsWith("MOD:")) {
            val id = s.removePrefix("MOD:")
            mods = if (id in mods) mods - id else mods + id
            return
        }
        if ("fn" in mods && key.fn != null) { c.key(key.fn.second); return }

        val ctrl = "ctrl" in mods
        val alt = "alt" in mods
        val shift = "shift" in mods
        val parts = buildList {
            if (ctrl) add("ctrl"); if (alt) add("alt"); if (shift) add("shift")
        }

        if (s != null) {                         // special key
            if (parts.isEmpty()) c.key(s) else c.hotkey((parts + s).joinToString("+"))
        } else if (ctrl || alt) {                // shortcut with a character
            c.hotkey((parts + key.base).joinToString("+"))
        } else {                                 // plain text
            val out = when {
                "altgr" in mods -> key.alt ?: key.base
                shift -> key.shift ?: key.base.uppercase()
                else -> key.base
            }
            c.type(out)
        }
        clearOneShot()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ROWS.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { key ->
                    val modId = key.special?.takeIf { it.startsWith("MOD:") }?.removePrefix("MOD:")
                    val repeat = key.special == "backspace" ||
                        ("fn" in mods && key.fn?.second in setOf("up", "down", "left", "right", "delete"))
                    KeyCap(
                        label(key),
                        Modifier.weight(key.w),
                        height = keyH,
                        repeat = repeat,
                        active = modId != null && modId in mods,
                        fontSize = fs,
                    ) { press(key) }
                }
            }
        }
    }
}
