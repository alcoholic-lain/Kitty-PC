package com.example.myapplication

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp

/*
 * How a component reacts to touch while the profile is being played.
 * Components that have their own touch handling (Screen, Touchpad, Keyboard, Type box)
 * are listed in [ownsTouch] and get no gesture here.
 */

/** Components that handle their own touches inside, so the HUD does not add a gesture. */
fun Kind.ownsTouch(): Boolean = this == Kind.SCREEN || this == Kind.TOUCHPAD ||
    this == Kind.KEYBOARD || this == Kind.TYPEBOX

/** Edit mode: a short tap selects the component, a drag moves it. */
internal suspend fun PointerInputScope.editGesture(
    onSelect: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown()
        var total = Offset.Zero
        var dragging = false
        do {
            val ev = awaitPointerEvent()
            val ch = ev.changes.first()
            val d = ch.positionChange()
            total += d
            if (total.getDistance() > viewConfiguration.touchSlop) {
                dragging = true
                onDrag(d)
            }
            ch.consume()
        } while (ev.changes.any { it.pressed })
        if (dragging) onDrop() else onSelect()
    }
}

/** Play mode: what the component does while you touch it. */
internal suspend fun PointerInputScope.playGesture(e: Elem, pad: VirtualPad, c: UdpClient) {
    when (e.kind) {
        Kind.STICK -> stickGesture(e, pad)
        Kind.LOOK -> lookGesture(e, c)
        Kind.HOTKEY -> awaitEachGesture {
            awaitFirstDown()
            c.ui("hotkey '${e.label}' ${e.action}")
            c.hotkey(e.action)
            drainUntilUp()
        }
        Kind.PAD, Kind.KEY, Kind.MOUSE -> awaitEachGesture {
            awaitFirstDown()
            c.ui("down ${e.kind.name} '${e.label}' ${e.action}")
            pressKey(e, pad, c, true)
            drainUntilUp()
            c.ui("up ${e.kind.name} '${e.label}' ${e.action}")
            pressKey(e, pad, c, false)
        }
        Kind.SCREEN, Kind.TOUCHPAD, Kind.KEYBOARD, Kind.TYPEBOX -> Unit
    }
}

private fun pressKey(e: Elem, pad: VirtualPad, c: UdpClient, down: Boolean) {
    when (e.kind) {
        Kind.PAD -> PAD_BITS[e.action]?.let { bit ->
            if (bit < 0) pad.trigger(e.action == "LT", if (down) 1f else 0f) else pad.button(bit, down)
        }
        Kind.KEY -> if (down) c.keyDown(e.action) else c.keyUp(e.action)
        Kind.MOUSE -> if (down) c.buttonDown(e.action) else c.buttonUp(e.action)
        else -> Unit
    }
}

private suspend fun AwaitPointerEventScope.drainUntilUp() {
    do {
        val ev = awaitPointerEvent()
        ev.changes.forEach { it.consume() }
    } while (ev.changes.any { it.pressed })
}

/**
 * Floating stick: wherever the thumb lands is the centre, and dragging away from that spot is the
 * deflection. Full deflection is a drag of about a third of the area's short side (44 to 80 dp).
 */
private suspend fun PointerInputScope.stickGesture(e: Elem, pad: VirtualPad) {
    val radius = (minOf(e.w, e.h) / 3f).coerceIn(44f, 80f).dp.toPx()
    val left = e.action != "R"
    awaitEachGesture {
        val origin = awaitFirstDown().position
        do {
            val ev = awaitPointerEvent()
            val ch = ev.changes.first()
            var v = (ch.position - origin) / radius
            val len = v.getDistance()
            if (len > 1f) v /= len
            pad.stick(left, v.x, -v.y)
            ev.changes.forEach { it.consume() }
        } while (ev.changes.any { it.pressed })
        pad.stick(left, 0f, 0f)
    }
}

/** Look area: dragging moves the PC mouse (the camera in games), scaled by the component's speed. */
private suspend fun PointerInputScope.lookGesture(e: Elem, c: UdpClient) {
    awaitEachGesture {
        awaitFirstDown()
        do {
            val ev = awaitPointerEvent()
            val pan = ev.calculatePan()
            c.move(pan.x * e.sens, pan.y * e.sens)
            ev.changes.forEach { it.consume() }
        } while (ev.changes.any { it.pressed })
    }
}
