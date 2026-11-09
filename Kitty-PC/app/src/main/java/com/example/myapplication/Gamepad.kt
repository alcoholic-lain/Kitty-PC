package com.example.myapplication

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.util.Locale

// XInput button bits (what the PC-side virtual Xbox 360 pad understands)
private const val DPAD_UP = 0x0001
private const val DPAD_DOWN = 0x0002
private const val DPAD_LEFT = 0x0004
private const val DPAD_RIGHT = 0x0008
private const val START = 0x0010      // Menu
private const val BACK = 0x0020       // View
private const val L3 = 0x0040
private const val R3 = 0x0080
private const val LB = 0x0100
private const val RB = 0x0200
private const val GUIDE = 0x0400      // Xbox / Guide button
private const val BTN_A = 0x1000      // A
private const val BTN_B = 0x2000      // B
private const val BTN_X = 0x4000      // X
private const val BTN_Y = 0x8000     // Y

/**
 * Holds the whole controller state and streams it as one packet:
 * G|buttons|lx|ly|rx|ry|lt|rt  (sent on change, and repeated 4x/s while anything is held,
 * so a lost UDP packet can never leave a button stuck).
 */
class VirtualPad(private val c: UdpClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var mask = 0
    private var lx = 0f; private var ly = 0f
    private var rx = 0f; private var ry = 0f
    private var lt = 0f; private var rt = 0f
    private var dirty = false

    init {
        scope.launch {
            var last = 0L
            while (isActive) {
                delay(16)
                val out: Pair<String, Boolean>? = synchronized(lock) {
                    val now = System.nanoTime()
                    val neutral = mask == 0 && lx == 0f && ly == 0f && rx == 0f && ry == 0f && lt == 0f && rt == 0f
                    if (dirty || (!neutral && now - last > 250_000_000L)) {
                        dirty = false; last = now
                        packet() to neutral
                    } else null
                }
                if (out != null) {
                    c.send(out.first)
                    if (out.second) c.send(out.first)   // neutral sent twice
                }
            }
        }
    }

    private fun packet() =
        "G|%d|%.2f|%.2f|%.2f|%.2f|%.2f|%.2f".format(Locale.US, mask, lx, ly, rx, ry, lt, rt)

    fun button(bit: Int, down: Boolean) = synchronized(lock) {
        mask = if (down) mask or bit else mask and bit.inv()
        dirty = true
    }

    fun stick(left: Boolean, x: Float, y: Float) = synchronized(lock) {
        if (left) { lx = x; ly = y } else { rx = x; ry = y }
        dirty = true
    }

    fun trigger(left: Boolean, v: Float) = synchronized(lock) {
        if (left) lt = v else rt = v
        dirty = true
    }

    fun reset() {
        val p = synchronized(lock) {
            mask = 0; lx = 0f; ly = 0f; rx = 0f; ry = 0f; lt = 0f; rt = 0f; dirty = false
            packet()
        }
        c.send(p); c.send(p)
    }

    fun close() = scope.cancel()
}

// ------------------------------------------------------------------ UI

private data class Spec(val label: String, val bit: Int, val color: Color? = null)

/**
 * Steam Deck style layout, Xbox button names (what Cyberpunk shows on screen):
 *   left stick upper-left, D-pad lower-left | A B X Y upper-right, right stick lower-right.
 * Both sticks are FLOATING: touch anywhere on that half of the screen and the stick
 * appears under your thumb.
 */
@Composable
fun Gamepad(c: UdpClient, modifier: Modifier = Modifier) {
    val pad = remember(c) { VirtualPad(c) }
    val view = LocalView.current
    DisposableEffect(pad) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false; pad.reset(); pad.close() }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val h = maxHeight
        val w = maxWidth
        val cluster = minOf(h * 0.52f, w * 0.2f, 150.dp)
        val shoulderH = 40.dp

        // --- bottom layer: two floating stick zones (each covers half the screen)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FloatingStick(Modifier.weight(1f).fillMaxHeight(), ghostX = 0.5f, ghostY = 0.42f) { x, y ->
                pad.stick(true, x, y)
            }
            FloatingStick(Modifier.weight(1f).fillMaxHeight(), ghostX = 0.5f, ghostY = 0.72f) { x, y ->
                pad.stick(false, x, y)
            }
        }

        // --- everything below sits on top, so buttons win over the stick zones

        // triggers / bumpers
        Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadKey("LT", Modifier.width(80.dp).height(shoulderH), RoundedCornerShape(12.dp), fontSize = 14.sp,
                onDown = { pad.trigger(true, 1f) }, onUp = { pad.trigger(true, 0f) })
            PadKey("LB", Modifier.width(80.dp).height(shoulderH), RoundedCornerShape(12.dp), fontSize = 14.sp,
                onDown = { pad.button(LB, true) }, onUp = { pad.button(LB, false) })
        }
        Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadKey("RB", Modifier.width(80.dp).height(shoulderH), RoundedCornerShape(12.dp), fontSize = 14.sp,
                onDown = { pad.button(RB, true) }, onUp = { pad.button(RB, false) })
            PadKey("RT", Modifier.width(80.dp).height(shoulderH), RoundedCornerShape(12.dp), fontSize = 14.sp,
                onDown = { pad.trigger(false, 1f) }, onUp = { pad.trigger(false, 0f) })
        }

        // View / Xbox / Menu
        Row(Modifier.align(Alignment.TopCenter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("View" to BACK, "Xbox" to GUIDE, "Menu" to START).forEach { (label, bit) ->
                PadKey(label, Modifier.width(62.dp).height(30.dp), RoundedCornerShape(50), fontSize = 11.sp,
                    onDown = { pad.button(bit, true) }, onUp = { pad.button(bit, false) })
            }
        }

        // stick clicks, bottom centre
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PadKey("L3", Modifier.size(44.dp), fontSize = 12.sp,
                onDown = { pad.button(L3, true) }, onUp = { pad.button(L3, false) })
            PadKey("R3", Modifier.size(44.dp), fontSize = 12.sp,
                onDown = { pad.button(R3, true) }, onUp = { pad.button(R3, false) })
        }

        // D-pad: lower-left
        Cross(
            Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 8.dp).size(cluster),
            top = Spec("▲", DPAD_UP), left = Spec("◀", DPAD_LEFT),
            right = Spec("▶", DPAD_RIGHT), bottom = Spec("▼", DPAD_DOWN),
            circle = false, pad = pad,
        )

        // A B X Y: upper-right
        Cross(
            Modifier.align(Alignment.TopEnd).padding(top = shoulderH + 8.dp, end = 24.dp).size(cluster),
            top = Spec("Y", BTN_Y, Color(0xFFF2C230)), left = Spec("X", BTN_X, Color(0xFF4A90E2)),
            right = Spec("B", BTN_B, Color(0xFFE34B4B)), bottom = Spec("A", BTN_A, Color(0xFF5BC236)),
            circle = true, pad = pad,
        )
    }
}

@Composable
private fun Cross(
    modifier: Modifier,
    top: Spec, left: Spec, right: Spec, bottom: Spec,
    circle: Boolean, pad: VirtualPad,
) {
    val shape: Shape = if (circle) CircleShape else RoundedCornerShape(14.dp)
    @Composable
    fun RowScope.Btn(s: Spec) = PadKey(
        s.label, Modifier.weight(1f).fillMaxHeight(), shape, s.color ?: MaterialTheme.colorScheme.onSurface,
        fontSize = 22.sp, onDown = { pad.button(s.bit, true) }, onUp = { pad.button(s.bit, false) },
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(Modifier.weight(1f)); Btn(top); Spacer(Modifier.weight(1f))
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn(left); Spacer(Modifier.weight(1f)); Btn(right)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(Modifier.weight(1f)); Btn(bottom); Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Floating analog stick. Touch anywhere in this area: the stick centre is created where
 * your finger lands, and movement is measured from there. A faint ghost shows the rest spot.
 * Output: x/y in -1..1, y up = +1.
 */
@Composable
private fun FloatingStick(
    modifier: Modifier,
    ghostX: Float,
    ghostY: Float,
    onMove: (Float, Float) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var active by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var knob by remember { mutableStateOf(Offset.Zero) }   // normalised -1..1
    val move by rememberUpdatedState(onMove)

    Box(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val travel = 56.dp.toPx()
                    origin = down.position
                    knob = Offset.Zero
                    active = true
                    down.consume()
                    move(0f, 0f)
                    do {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        if (ch != null && ch.pressed) {
                            var v = (ch.position - origin) / travel
                            val len = v.getDistance()
                            if (len > 1f) v /= len
                            knob = v
                            move(v.x, -v.y)
                            ch.consume()
                        }
                    } while (ch != null && ch.pressed)
                    active = false
                    knob = Offset.Zero
                    move(0f, 0f)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val travel = 56.dp.toPx()
            val centre = if (active) origin else Offset(size.width * ghostX, size.height * ghostY)
            val a = if (active) 1f else 0.3f
            drawCircle(colors.outline.copy(alpha = a), radius = travel * 1.2f, center = centre, style = Stroke(2.dp.toPx()))
            drawCircle(colors.primary.copy(alpha = 0.85f * a), radius = travel * 0.5f, center = centre + knob * travel)
        }
    }
}

/** Press-and-hold pad button with haptic tick. */
@Composable
private fun PadKey(
    label: String,
    modifier: Modifier,
    shape: Shape = CircleShape,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: TextUnit = 20.sp,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)

    Box(
        modifier
            .clip(shape)
            .background(if (pressed) colors.primaryContainer else colors.surface)
            .border(1.dp, if (pressed) colors.primary else colors.outline, shape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    down()
                    try { awaitRelease() } finally { pressed = false; up() }
                })
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = fontSize, color = color, maxLines = 1) }
}
