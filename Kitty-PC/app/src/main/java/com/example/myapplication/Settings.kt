package com.example.myapplication

import android.content.SharedPreferences
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Everything the user changes on the Settings page (ports, transport, sensitivity, theme).
 * Saved in the "remote" SharedPreferences. Ports must match config.json on the PC.
 */
class AppSettings(private val p: SharedPreferences) {
    var transport by mutableStateOf(
        if (p.getString("transport", "udp") == "tcp") Transport.TCP else Transport.UDP
    )
        private set
    var udpPort by mutableStateOf(p.getInt("udp_port", 5005))
        private set
    var tcpPort by mutableStateOf(p.getInt("tcp_port", 5005))
        private set
    var streamPort by mutableStateOf(p.getInt("stream_port", 5006))
        private set
    var sensitivity by mutableStateOf(p.getFloat("sensitivity", 1f))
        private set
    var smoothCursor by mutableStateOf(p.getBoolean("smooth", true))
        private set
    var theme by mutableStateOf(p.getString("theme", "dark") ?: "dark")
        private set

    /** Port the command link uses for the chosen transport. */
    val commandPort: Int get() = if (transport == Transport.TCP) tcpPort else udpPort

    fun saveTransport(t: Transport) {
        transport = t
        p.edit().putString("transport", if (t == Transport.TCP) "tcp" else "udp").apply()
    }

    fun saveUdpPort(v: Int) { udpPort = v; p.edit().putInt("udp_port", v).apply() }
    fun saveTcpPort(v: Int) { tcpPort = v; p.edit().putInt("tcp_port", v).apply() }
    fun saveStreamPort(v: Int) { streamPort = v; p.edit().putInt("stream_port", v).apply() }

    fun saveSensitivity(v: Float) { sensitivity = v; p.edit().putFloat("sensitivity", v).apply() }

    fun saveSmoothCursor(v: Boolean) { smoothCursor = v; p.edit().putBoolean("smooth", v).apply() }

    var streamFps by mutableStateOf(p.getInt("stream_fps", 15))
        private set
    var streamWidth by mutableStateOf(p.getInt("stream_width", 1280))
        private set
    var streamQuality by mutableStateOf(p.getInt("stream_quality", 60))
        private set
    var screenTouch by mutableStateOf(p.getBoolean("screen_touch", true))
        private set

    fun saveStreamFps(v: Int) { streamFps = v; p.edit().putInt("stream_fps", v).apply() }
    fun saveStreamWidth(v: Int) { streamWidth = v; p.edit().putInt("stream_width", v).apply() }
    fun saveStreamQuality(v: Int) { streamQuality = v; p.edit().putInt("stream_quality", v).apply() }
    fun saveScreenTouch(v: Boolean) { screenTouch = v; p.edit().putBoolean("screen_touch", v).apply() }

    /** false = Fit (whole PC screen, bars if needed), true = Fill (cropped, no bars). */
    var screenFill by mutableStateOf(p.getBoolean("screen_fill", false))
        private set

    fun saveScreenFill(v: Boolean) { screenFill = v; p.edit().putBoolean("screen_fill", v).apply() }

    /** Colour overrides chosen on the Settings page. Roles: bg, surface, text, outline, accent. */
    var overrides by mutableStateOf(loadOverrides())
        private set

    private fun loadOverrides() = ThemeOverrides(
        bg = colourPref("bg"),
        surface = colourPref("surface"),
        text = colourPref("text"),
        outline = colourPref("outline"),
        accent = colourPref("accent"),
    )

    private fun colourPref(role: String): Color? =
        if (p.contains("col_$role")) Color(p.getInt("col_$role", 0)) else null

    /** null clears the override so the theme's own colour is used again. */
    fun saveColor(role: String, colour: Color?) {
        val e = p.edit()
        if (colour == null) e.remove("col_$role") else e.putInt("col_$role", colour.toArgb())
        e.apply()
        overrides = loadOverrides()
    }

    fun saveTheme(name: String) { theme = name; p.edit().putString("theme", name).apply() }
}

/** Theme choices shown in Settings: key to label. */
val THEMES = listOf("dark" to "Dark", "midnight" to "Midnight (AMOLED)", "light" to "Light")

val LocalSettings = compositionLocalOf<AppSettings> { error("AppSettings not provided") }

/** Swatches offered on the Settings page. */
val SWATCHES = listOf(
    Color(0xFF0E0F12), Color(0xFF000000), Color(0xFF181A1F), Color(0xFF2C2F36),
    Color(0xFFFFFFFF), Color(0xFFE6E8EC), Color(0xFF1A1C20), Color(0xFF8AB4FF),
    Color(0xFF4CAF50), Color(0xFFE5484D), Color(0xFFFFB300), Color(0xFFB39DDB),
)
