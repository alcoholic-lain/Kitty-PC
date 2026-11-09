package com.example.myapplication

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Board rules for the HUD. Pure Kotlin, no Compose, so the rules are easy to read and test.
 *
 *  - A "unit" is one component, or a group of components that move and resize together.
 *  - Layers: components only push and block each other within the same layer. A component on a
 *    higher layer (say the keyboard) can sit over others without shoving them.
 *  - Positions lock to a 12-column grid and a 16 dp row grid.
 *  - A moved or resized unit locks onto the edges and centres of other units within SNAP_DP.
 *  - Units in the same layer never overlap. A move that would overlap goes to the nearest free spot,
 *    or stays where it was.
 *  - When the HUD is shown, a stored layout that overlaps (within a layer) is spread out on screen.
 *    Saved positions only change when you edit.
 */

/** How close (dp) an edge must be to lock onto another component's edge or centre. */
const val SNAP_DP = 10f

private const val COLUMNS = 12
private const val ROW_DP = 16f
private const val MIN_SIZE = 24f

/** A rectangle on the board, in dp, on a layer. */
data class Rect(val left: Float, val top: Float, val w: Float, val h: Float, val layer: Int = 0) {
    val right: Float get() = left + w
    val bottom: Float get() = top + h
    val centerX: Float get() = left + w / 2f
    val centerY: Float get() = top + h / 2f

    /**
     * True if the two rectangles share an area on the same layer. Touching edges do not count,
     * and components on different layers never block each other.
     */
    fun overlaps(o: Rect): Boolean =
        layer == o.layer && left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/** Guide lines to draw while a unit is moved: x positions (vertical lines) and y positions (horizontal). */
data class Guides(val xs: List<Float> = emptyList(), val ys: List<Float> = emptyList())

/** The HUD design area in dp. */
class Board(val w: Float, val h: Float) {
    val colW: Float get() = w / COLUMNS
    val rowH: Float get() = ROW_DP

    /** Keeps [r] fully on the board. */
    fun inside(r: Rect): Rect = r.copy(
        left = r.left.coerceIn(0f, max(0f, w - r.w)),
        top = r.top.coerceIn(0f, max(0f, h - r.h)),
    )
}

/** Where a stored element sits on [b]. Stored position is the centre as a fraction of the board. */
fun Elem.rectOn(b: Board): Rect = Rect(x * b.w - w / 2f, y * b.h - h / 2f, w, h, layer)

/** The stored form of [r] on [b]. */
fun Elem.placedAt(r: Rect, b: Board): Elem =
    copy(x = r.centerX / b.w, y = r.centerY / b.h, w = r.w, h = r.h)

/** Components that move and resize together with [id]: its group, or just itself. */
fun unitIds(elems: List<Elem>, id: String): Set<String> {
    val group = elems.firstOrNull { it.id == id }?.group.orEmpty()
    return if (group.isEmpty()) setOf(id) else elems.filter { it.group == group }.map { it.id }.toSet()
}

/** The smallest rectangle that contains all of [rs]. It takes the layer of the first one. */
fun bounds(rs: List<Rect>): Rect {
    val left = rs.minOf { it.left }
    val top = rs.minOf { it.top }
    val right = rs.maxOf { it.right }
    val bottom = rs.maxOf { it.bottom }
    return Rect(left, top, right - left, bottom - top, rs.first().layer)
}

/**
 * Positions every component for display, as id to rectangle. Units keep their shape.
 * Earlier units keep their place; a later unit that overlaps one on its layer moves to the nearest free spot.
 */
fun settle(elems: List<Elem>, b: Board): Map<String, Rect> {
    val natural = elems.associate { it.id to b.inside(it.rectOn(b)) }
    val units = elems.map { unitIds(elems, it.id) }.distinct()
    val boxes = units.map { ids -> bounds(ids.map { natural.getValue(it) }) }

    val placed = mutableListOf<Rect>()
    boxes.forEachIndexed { i, want ->
        // a moved unit also keeps clear of the natural spots of the ones still waiting (on its layer)
        val waiting = boxes.drop(i + 1)
        val r = when {
            placed.none { want.overlaps(it) } -> want
            else -> nearestFree(want, placed + waiting, b) ?: nearestFree(want, placed, b) ?: want
        }
        placed += r
    }

    val out = mutableMapOf<String, Rect>()
    units.forEachIndexed { i, ids -> out.putAll(shiftUnit(natural, ids, boxes[i], placed[i])) }
    return out
}

/** The free spot closest to [target] that overlaps none of [blockers] on its layer, or null if there is none. */
fun nearestFree(target: Rect, blockers: List<Rect>, b: Board): Rect? {
    var best: Rect? = null
    var bestDist = Float.MAX_VALUE
    var y = 0f
    while (y + target.h <= b.h + 0.5f) {
        var x = 0f
        while (x + target.w <= b.w + 0.5f) {
            val cand = target.copy(left = x, top = y)
            val dx = cand.left - target.left
            val dy = cand.top - target.top
            val dist = dx * dx + dy * dy
            if (dist < bestDist && blockers.none { cand.overlaps(it) }) {
                best = cand
                bestDist = dist
            }
            x += b.colW
        }
        y += b.rowH
    }
    return best
}

/** [target] if it is free, otherwise the nearest free spot, otherwise [fallback]. */
fun freeSpot(target: Rect, others: List<Rect>, b: Board, fallback: Rect): Rect =
    if (others.none { target.overlaps(it) }) target
    else nearestFree(target, others, b) ?: fallback

/**
 * Moves [raw] so its edges or centre lock onto [others] when close, otherwise onto the grid.
 * Returns the snapped rectangle and the guide lines to draw.
 */
fun snapMove(raw: Rect, others: List<Rect>, b: Board): Pair<Rect, Guides> {
    val xTargets = others.flatMap { listOf(it.left, it.centerX, it.right) }
    val yTargets = others.flatMap { listOf(it.top, it.centerY, it.bottom) }
    val ax = lockAxis(listOf(raw.left, raw.centerX, raw.right), xTargets)
    val ay = lockAxis(listOf(raw.top, raw.centerY, raw.bottom), yTargets)

    val left = if (ax != null) raw.left + ax.first else (raw.left / b.colW).roundToInt() * b.colW
    val top = if (ay != null) raw.top + ay.first else (raw.top / b.rowH).roundToInt() * b.rowH

    val snapped = b.inside(raw.copy(left = left, top = top))
    return snapped to Guides(xs = listOfNotNull(ax?.second), ys = listOfNotNull(ay?.second))
}

/**
 * Resizes [origin] from its top-left corner. Width and height snap to the grid, and the right and
 * bottom edges lock onto other units. Returns [origin] if the result would leave the board or overlap.
 */
fun snapResize(origin: Rect, dw: Float, dh: Float, others: List<Rect>, b: Board): Rect {
    val step = b.colW / 2f
    var w = max(MIN_SIZE, origin.w + dw)
    var h = max(MIN_SIZE, origin.h + dh)
    w = max(step, (w / step).roundToInt() * step)
    h = max(b.rowH, (h / b.rowH).roundToInt() * b.rowH)

    lockAxis(listOf(origin.left + w), others.flatMap { listOf(it.left, it.centerX, it.right) })
        ?.let { w += it.first }
    lockAxis(listOf(origin.top + h), others.flatMap { listOf(it.top, it.centerY, it.bottom) })
        ?.let { h += it.first }
    w = max(MIN_SIZE, w)
    h = max(MIN_SIZE, h)

    val r = origin.copy(w = w, h = h)
    val fits = r.right <= b.w && r.bottom <= b.h
    return if (fits && others.none { r.overlaps(it) }) r else origin
}

/**
 * Moves the unit [ids] by (dx, dy) dp. The whole unit is snapped as one shape, kept clear of the
 * other components on its layer, and its members keep their positions relative to each other.
 */
fun moveUnit(
    base: Map<String, Rect>,
    ids: Set<String>,
    dx: Float,
    dy: Float,
    b: Board,
): Pair<Map<String, Rect>, Guides> {
    val box = bounds(ids.map { base.getValue(it) })
    val others = base.filterKeys { it !in ids }.values.toList()
    val (snapped, guides) = snapMove(box.copy(left = box.left + dx, top = box.top + dy), others, b)
    val target = freeSpot(snapped, others, b, fallback = box)
    return (base + shiftUnit(base, ids, box, target)) to guides
}

/**
 * Resizes the unit [ids] as one shape: its bounding box is resized like a single component,
 * and every member is scaled to fit inside the new box.
 */
fun resizeUnit(
    base: Map<String, Rect>,
    ids: Set<String>,
    dw: Float,
    dh: Float,
    b: Board,
): Map<String, Rect> {
    val box = bounds(ids.map { base.getValue(it) })
    val others = base.filterKeys { it !in ids }.values.toList()
    val nb = snapResize(box, dw, dh, others, b)
    val sx = nb.w / box.w
    val sy = nb.h / box.h
    val scaled = ids.associateWith { id ->
        val m = base.getValue(id)
        m.copy(
            left = nb.left + (m.left - box.left) * sx,
            top = nb.top + (m.top - box.top) * sy,
            w = m.w * sx,
            h = m.h * sy,
        )
    }
    return base + scaled
}

/** The rectangles of [ids] moved so that the box [from] becomes the box [to]. Only the ids are returned. */
private fun shiftUnit(base: Map<String, Rect>, ids: Set<String>, from: Rect, to: Rect): Map<String, Rect> {
    val dx = to.left - from.left
    val dy = to.top - from.top
    return ids.associateWith { id ->
        val m = base.getValue(id)
        m.copy(left = m.left + dx, top = m.top + dy)
    }
}

/**
 * The shift that moves one of [moving] onto one of [targets], within SNAP_DP, and the target line.
 * Null when nothing is close enough.
 */
private fun lockAxis(moving: List<Float>, targets: List<Float>): Pair<Float, Float>? {
    var best: Pair<Float, Float>? = null
    for (m in moving) {
        for (t in targets) {
            val d = t - m
            if (abs(d) <= SNAP_DP && (best == null || abs(d) < abs(best.first))) best = d to t
        }
    }
    return best
}
