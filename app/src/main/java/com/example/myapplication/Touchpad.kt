package com.example.myapplication

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Trackpad, Chromebook style:
 *  - one finger moves the pointer (faster strokes travel further)
 *  - two fingers scroll
 *  - tap = left click, two-finger tap = right click
 *  - double tap and hold = hold the left button and drag, like grabbing a scroll bar
 *    with a graphic tablet. Lift to drop. A quick double tap without moving is a double click.
 * Sensitivity comes from Settings. transparent = true draws no box (used over the screen picture).
 */
@Composable
fun Touchpad(c: UdpClient, modifier: Modifier = Modifier, transparent: Boolean = false) {
    val settings = LocalSettings.current
    val speed = 1.2f * settings.sensitivity
    val shape = RoundedCornerShape(20.dp)
    val lastTapUp = remember { LongArray(1) }     // uptime of the last quick tap, 0 = none
    val base = modifier.clip(shape)
    val styled = if (transparent) {
        base
    } else {
        base
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
    }

    Box(
        styled.pointerInput(speed) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val doubleTap = lastTapUp[0] != 0L && down.uptimeMillis - lastTapUp[0] < 300
                lastTapUp[0] = 0L
                if (doubleTap) c.buttonDown("L")

                var lastTime = down.uptimeMillis
                var moved = 0f
                var maxFingers = 1
                do {
                    val event = awaitPointerEvent()
                    val fingers = event.changes.count { it.pressed }
                    maxFingers = maxOf(maxFingers, fingers)
                    val pan = event.calculatePan()
                    val d = pan.getDistance()
                    moved += d
                    if (fingers >= 2) {
                        c.scroll(-pan.y / 4f)
                    } else if (fingers == 1) {
                        // pointer acceleration: slow = precise, fast = far
                        val gain = speed * (1f + (d / 6f).coerceAtMost(2f))
                        c.move(pan.x * gain, pan.y * gain)
                    }
                    lastTime = event.changes.first().uptimeMillis
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })

                val quick = moved < viewConfiguration.touchSlop && lastTime - down.uptimeMillis < 250
                if (doubleTap) {
                    c.buttonUp("L")                       // drop (or a plain double click if no drag)
                } else if (quick) {
                    c.click(if (maxFingers >= 2) "R" else "L")
                    lastTapUp[0] = lastTime
                }
            }
        }
    )
}
