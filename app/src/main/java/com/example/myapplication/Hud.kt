package com.example.myapplication

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.min

/*
 * The HUD board. Layout rules are in Layout.kt; this file draws, and turns touches into moves and resizes.
 *
 * The board is always laid out in a fixed design space (the profile's board: 320x700 portrait or
 * 700x320 landscape, in dp) and scaled uniformly to fit the area. A phone's density therefore changes
 * only the size on screen, never the spacing or which components overlap.
 * The keyboard never resizes the board: it only covers what is behind it.
 */

/** A move or resize in progress: where every component started, how far the finger has gone, and where it is now. */
private data class Gesture(
    val ids: Set<String>,
    val base: Map<String, Rect>,
    val acc: Offset,
    val live: Map<String, Rect>,
    val guides: Guides,
    val resizing: Boolean,
)

/** The HUD. Scales the design space to fit whatever area it is given. */
@Composable
internal fun Hud(
    modifier: Modifier,
    elems: List<Elem>,
    board: Board,
    editing: Boolean,
    selectedId: String?,
    picked: Set<String>,
    pad: VirtualPad,
    c: UdpClient,
    screen: ScreenStream?,
    onSelect: (String) -> Unit,
    onChange: (List<Elem>) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val outer = LocalDensity.current
        // uniform scale that fits the design space in this area; never zero, so the density stays valid
        val fit = min(maxWidth.value / board.w, maxHeight.value / board.h).coerceAtLeast(0.05f)
        CompositionLocalProvider(LocalDensity provides Density(outer.density * fit, outer.fontScale)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(board.w.dp, board.h.dp)) {
                    HudBoard(
                        elems = elems, editing = editing, selectedId = selectedId, picked = picked,
                        pad = pad, c = c, screen = screen,
                        board = board,
                        onSelect = onSelect, onChange = onChange,
                    )
                }
            }
        }
    }
}

/** The board in design units. Components are drawn back to front by layer. */
@Composable
private fun HudBoard(
    elems: List<Elem>,
    editing: Boolean,
    selectedId: String?,
    picked: Set<String>,
    pad: VirtualPad,
    c: UdpClient,
    screen: ScreenStream?,
    board: Board,
    onSelect: (String) -> Unit,
    onChange: (List<Elem>) -> Unit,
) {
    // the layout rules are only re-run when the components or the board change, not on every frame
    val settled = remember(elems, board) { settle(elems, board) }
    var gesture by remember { mutableStateOf<Gesture?>(null) }
    val shown = gesture?.live ?: settled

    fun moveBy(id: String, d: Offset) {
        val cur = gesture?.takeIf { !it.resizing }
            ?: Gesture(unitIds(elems, id), settled, Offset.Zero, settled, Guides(), resizing = false)
        val acc = cur.acc + d
        val (live, guides) = moveUnit(cur.base, cur.ids, acc.x, acc.y, board)
        gesture = cur.copy(acc = acc, live = live, guides = guides)
    }

    fun resizeBy(id: String, d: Offset) {
        val cur = gesture?.takeIf { it.resizing }
            ?: Gesture(unitIds(elems, id), settled, Offset.Zero, settled, Guides(), resizing = true)
        val acc = cur.acc + d
        gesture = cur.copy(acc = acc, live = resizeUnit(cur.base, cur.ids, acc.x, acc.y, board))
    }

    fun endGesture() {
        val g = gesture ?: return
        val first = g.ids.first()
        val names = g.ids.joinToString(" + ") { id -> elems.first { it.id == id }.let { "${it.kind.name}'${it.label}'" } }
        c.ui("${if (g.resizing) "resize" else "move"} $names ${g.base.getValue(first).fmt()} -> ${g.live.getValue(first).fmt()}")
        onChange(g.ids.map { id -> elems.first { it.id == id }.placedAt(g.live.getValue(id), board) })
        gesture = null
    }

    elems.sortedBy { it.layer }.forEach { e ->
        ElementView(
            e = e,
            rect = shown.getValue(e.id),
            pad = pad,
            c = c,
            screen = screen,
            editing = editing,
            selected = e.id == selectedId,
            picked = e.id in picked,
            onSelect = { onSelect(e.id) },
            onMove = { d -> moveBy(e.id, d) },
            onResize = { d -> resizeBy(e.id, d) },
            onEnd = { endGesture() },
        )
    }
    if (editing) BoardOverlay(guides = gesture?.guides ?: Guides(), frames = groupFrames(elems, shown))
}

/** One component on the HUD, drawn at [rect]. Gestures are reported to the board, which does the snapping. */
@Composable
private fun ElementView(
    e: Elem,
    rect: Rect,
    pad: VirtualPad,
    c: UdpClient,
    screen: ScreenStream?,
    editing: Boolean,
    selected: Boolean,
    picked: Boolean,
    onSelect: () -> Unit,
    onMove: (Offset) -> Unit,
    onResize: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    val density = LocalDensity.current.density
    val curE by rememberUpdatedState(e)
    val pick by rememberUpdatedState(onSelect)
    val move by rememberUpdatedState(onMove)
    val resize by rememberUpdatedState(onResize)
    val end by rememberUpdatedState(onEnd)

    val shape = e.shape.toShape()
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val custom = e.color != 0L
    val base = if (custom) Color(e.color.toInt()) else scheme.surface
    val marked = selected || picked

    // feedback while a finger is down. This only watches; the gesture below still gets the touch.
    var pressed by remember { mutableStateOf(false) }
    val pressable = !editing && (e.kind == Kind.PAD || e.kind == Kind.KEY || e.kind == Kind.MOUSE || e.kind == Kind.HOTKEY)
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(70), label = "press")
    val fill = when {
        !pressed -> base
        custom -> lerp(base, Color.White, 0.35f)
        else -> scheme.primaryContainer
    }
    val border = when {
        marked -> accent
        editing -> accent.copy(alpha = 0.5f)
        custom -> lerp(base, Color.Black, 0.3f)
        else -> scheme.outline
    }
    // dark text on light colours, light text on dark ones
    val labelColor = if (custom) (if (base.luminance() > 0.55f) Color(0xFF111111) else Color.White) else scheme.onSurface

    Box(
        Modifier
            .offset(x = rect.left.dp, y = rect.top.dp)
            .size(rect.w.dp, rect.h.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(fill.copy(alpha = e.alpha))
            .border(if (marked) 2.dp else 1.dp, border, shape)
            .pointerInput(pressable) {
                if (pressable) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        pressed = true
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                        } while (ev.changes.any { it.pressed })
                        pressed = false
                    }
                }
            }
            .pointerInput(e, editing) {
                if (editing) {
                    editGesture(
                        onSelect = { pick() },
                        onDrag = { d -> move(Offset(d.x / density, d.y / density)) },
                        onDrop = { end() },
                    )
                } else if (!curE.kind.ownsTouch()) {
                    playGesture(curE, pad, c)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            // the picture and the touch components handle their own touches while playing
            e.kind == Kind.SCREEN -> ScreenElement(screen, c, editing)
            editing -> ElementLabel(e.label, labelColor, e.isZone())
            e.kind == Kind.TOUCHPAD -> {
                Touchpad(c, Modifier.fillMaxSize(), transparent = true)
                ElementLabel(e.label, scheme.onSurfaceVariant, faint = true)
            }
            e.kind == Kind.KEYBOARD -> OnScreenKeyboard(c)
            e.kind == Kind.TYPEBOX -> TypeLauncher()
            else -> ElementLabel(e.label, labelColor, e.isZone())
        }
        if (editing && selected) {
            ResizeHandle(
                onResize = { d -> resize(Offset(d.x / density, d.y / density)) },
                onDrop = { end() },
            )
        }
    }
}

/** A rectangle as "left,top width x height" in design units, for the UI log. */
private fun Rect.fmt(): String = "${left.toInt()},${top.toInt()} ${w.toInt()}x${h.toInt()}"

/** Outline of every group, so a group reads as one thing. */
private fun groupFrames(elems: List<Elem>, shown: Map<String, Rect>): List<Rect> =
    elems.filter { it.group.isNotEmpty() }
        .groupBy { it.group }
        .values
        .map { members -> bounds(members.map { shown.getValue(it.id) }) }

@Composable
private fun ElementLabel(text: String, color: Color, faint: Boolean = false) {
    Text(
        text,
        color = if (faint) MaterialTheme.colorScheme.onSurfaceVariant else color,
        fontSize = if (faint) 12.sp else 14.sp,
        fontWeight = if (faint) FontWeight.Normal else FontWeight.Medium,
        maxLines = 1,
    )
}

/** Group outlines and the guide lines where a moved unit locked on. */
@Composable
private fun BoardOverlay(guides: Guides, frames: List<Rect>) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 1.dp.toPx()
        frames.forEach { f ->
            drawRect(
                color = color.copy(alpha = 0.6f),
                topLeft = Offset(f.left.dp.toPx(), f.top.dp.toPx()),
                size = Size(f.w.dp.toPx(), f.h.dp.toPx()),
                style = Stroke(width = stroke),
            )
        }
        guides.xs.forEach { x ->
            drawLine(color, Offset(x.dp.toPx(), 0f), Offset(x.dp.toPx(), size.height), strokeWidth = stroke)
        }
        guides.ys.forEach { y ->
            drawLine(color, Offset(0f, y.dp.toPx()), Offset(size.width, y.dp.toPx()), strokeWidth = stroke)
        }
    }
}

/** Blue corner handle, shown on the selected component in edit mode. */
@Composable
private fun BoxScope.ResizeHandle(onResize: (Offset) -> Unit, onDrop: () -> Unit) {
    val move by rememberUpdatedState(onResize)
    val drop by rememberUpdatedState(onDrop)
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(2.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    do {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.first()
                        move(ch.positionChange())
                        ch.consume()
                    } while (ev.changes.any { it.pressed })
                    drop()
                }
            }
    )
}
