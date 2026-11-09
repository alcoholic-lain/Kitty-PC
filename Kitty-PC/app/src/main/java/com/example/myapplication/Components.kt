package com.example.myapplication

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/*
 * Shared pieces used by the profile components and the Settings page.
 * Nothing in here knows about a specific profile.
 */

/**
 * Sends only what changed between [old] and [new] to the PC: backspaces for what was removed, then the
 * new text. Newlines become real Enter presses. Autocorrect and swipe typing work because of this.
 */
fun sendTextDiff(c: UdpClient, old: String, new: String) {
    var p = 0
    val max = minOf(old.length, new.length)
    while (p < max && old[p] == new[p]) p++
    repeat(old.length - p) { c.key("backspace") }
    new.substring(p).replace("\r", "").split("\n").forEachIndexed { i, seg ->
        if (i > 0) c.key("enter")
        if (seg.isNotEmpty()) c.type(seg)
    }
}

/** Phone keyboard text box. Every change is sent to the PC at once, so autocorrect works and nothing is limited. */
@Composable
fun TypeBox(c: UdpClient, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            sendTextDiff(c, text, new)
            text = new
        },
        placeholder = { Text("Type here…") },
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            autoCorrectEnabled = true,
            imeAction = ImeAction.Send,
        ),
        // Enter on the phone keyboard presses Enter on the PC and clears the box
        keyboardActions = KeyboardActions(onSend = { c.key("enter"); text = "" }),
    )
}

/** A row of options for one stream setting, e.g. "15 fps". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamChoices(title: String, options: List<Int>, selected: Int, suffix: String, onPick: (Int) -> Unit) {
    Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text("$o$suffix") })
        }
    }
}

/** Green / amber / red dot with the round-trip time. */
@Composable
fun LatencyBadge(ms: Int?) {
    val color = when {
        ms == null -> Palette.Bad
        ms < 40 -> Palette.Good
        ms < 120 -> Palette.Warn
        else -> Palette.Bad
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(
            if (ms == null) "no reply" else "$ms ms",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

/**
 * Where a touch lands on the PC picture, as 0..1 fractions of the PC screen. Null if it is in the bars
 * of a Fit picture. [fill] = the picture is cropped to fill the box instead of fitted inside it.
 */
internal fun tapSpot(off: Offset, frame: ImageBitmap?, box: IntSize, fill: Boolean): Pair<Float, Float>? {
    if (frame == null || box.width == 0 || box.height == 0) return null
    val fw = frame.width.toFloat()
    val fh = frame.height.toFloat()
    val sx = box.width / fw
    val sy = box.height / fh
    val scale = if (fill) maxOf(sx, sy) else minOf(sx, sy)
    val dw = fw * scale
    val dh = fh * scale
    val ox = (box.width - dw) / 2f
    val oy = (box.height - dh) / 2f
    val fx = (off.x - ox) / dw
    val fy = (off.y - oy) / dh
    if (fx < 0f || fx > 1f || fy < 0f || fy > 1f) return null
    return fx to fy
}

/**
 * Hides the status and navigation bars while [active]. Swiping from an edge brings them back briefly.
 * The bars come back when [active] turns off or the screen leaves.
 */
@Composable
fun ImmersiveEffect(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val ctrl = WindowCompat.getInsetsController(window, view)
            if (active) {
                ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                ctrl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
}

/**
 * The PC picture. While not editing, either direct touch (tap = click where you touch, double tap =
 * double click, long press = right click, drag = drag, two fingers = scroll) or the Chromebook trackpad,
 * as chosen in Settings. Fit or Fill is also chosen in Settings.
 */
@Composable
internal fun ScreenElement(screen: ScreenStream?, c: UdpClient, editing: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    val settings = LocalSettings.current
    val frame: ImageBitmap? = screen?.frame
    // The gesture must NOT restart on every new picture (15 a second): a restart cancels the touch in
    // progress, which is why taps never registered. It reads the newest picture through this instead.
    val frameNow by rememberUpdatedState(frame)
    var box by remember { mutableStateOf(IntSize.Zero) }
    val fill = settings.screenFill

    Box(modifier.onSizeChanged { box = it }, contentAlignment = Alignment.Center) {
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = "PC screen",
                modifier = Modifier.fillMaxSize(),
                contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
            )
        } else {
            Text(
                if (screen?.connected == true) "Waiting for the PC picture…"
                else "No PC picture yet\nCheck the screen port in Settings and that the server is running",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(12.dp),
            )
        }
        if (!editing) {
            if (settings.screenTouch) {
                Box(
                    Modifier.fillMaxSize().pointerInput(box, fill) {
                        directTouchGestures(c) { off -> tapSpot(off, frameNow, box, fill) }
                    }
                )
            } else {
                Touchpad(c, Modifier.fillMaxSize(), transparent = true)
            }
        }
    }
}

/**
 * Touch the PC picture directly, like a remote desktop app:
 *  - tap = left click where you touched (two quick taps = double click)
 *  - hold = right click
 *  - drag = press the left button there and drag (windows, selections, scroll bars)
 *  - two fingers = scroll, a two-finger tap = right click
 * [spot] turns a touch point into 0..1 fractions of the PC screen (null = outside the picture).
 */
internal suspend fun PointerInputScope.directTouchGestures(
    c: UdpClient,
    spot: (Offset) -> Pair<Float, Float>?,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var outcome: String? = null
        var lastTime = down.uptimeMillis

        // first moments decide what this touch is: a tap, a hold, a drag or a two-finger scroll
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (outcome == null) {
                val ev = awaitPointerEvent()
                lastTime = ev.changes.first().uptimeMillis
                val pressed = ev.changes.count { it.pressed }
                if (pressed == 0) { outcome = "tap"; break }
                if (pressed >= 2) { outcome = "scroll"; break }
                if ((ev.changes.first().position - down.position).getDistance() > slop) { outcome = "drag"; break }
            }
        }

        when (outcome) {
            "tap" -> spot(down.position)?.let { (fx, fy) ->
                c.moveTo(fx, fy)
                c.ui("screen click ${"%.2f".format(fx)},${"%.2f".format(fy)}")
                c.click("L")
            }
            "scroll" -> {
                var moved = 0f
                do {
                    val ev = awaitPointerEvent()
                    lastTime = ev.changes.first().uptimeMillis
                    val pan = ev.calculatePan()
                    moved += pan.getDistance()
                    c.scroll(-pan.y / 4f)
                    ev.changes.forEach { it.consume() }
                } while (ev.changes.any { it.pressed })
                if (moved < slop * 2 && lastTime - down.uptimeMillis < 350) {
                    spot(down.position)?.let { (fx, fy) -> c.moveTo(fx, fy); c.click("R") }
                }
            }
            "drag" -> {
                val start = spot(down.position)
                if (start != null) { c.moveTo(start.first, start.second); c.buttonDown("L") }
                try {
                    do {
                        val ev = awaitPointerEvent()
                        spot(ev.changes.first().position)?.let { (fx, fy) -> if (start != null) c.moveTo(fx, fy) }
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                } finally {
                    if (start != null) c.buttonUp("L")
                }
            }
            else -> {   // held still: right click, then wait for the finger to lift
                spot(down.position)?.let { (fx, fy) -> c.moveTo(fx, fy); c.click("R") }
                do {
                    val ev = awaitPointerEvent()
                    ev.changes.forEach { it.consume() }
                } while (ev.changes.any { it.pressed })
            }
        }
    }
}
