package com.example.myapplication

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/*
 * Layout diagnostics. The phone builds one compact JSON line and sends it to the PC server
 * (command L). The server appends it to diag.jsonl next to server.py.
 *
 * Everything is in design units (dp of the profile's 320x700 or 700x320 board), the same numbers the layout code
 * uses on every phone. Compare "nat" (where the layout was stored) with "set" (where it is drawn).
 */
fun layoutDiagnostics(profile: Profile, density: Float, fontScale: Float, screenW: Int, screenH: Int): String {
    val board = profile.board
    val natural = profile.elems.associate { it.id to board.inside(it.rectOn(board)) }
    val settled = settle(profile.elems, board)

    // components on the same layer that still overlap after settling: should always be 0
    var clashes = 0
    val all = profile.elems
    for (i in all.indices) {
        for (j in i + 1 until all.size) {
            if (settled.getValue(all[i].id).overlaps(settled.getValue(all[j].id))) clashes++
        }
    }

    val elems = JSONArray()
    var moved = 0
    for (e in all) {
        val nat = natural.getValue(e.id)
        val set = settled.getValue(e.id)
        if (nat != set) moved++
        elems.put(
            JSONObject()
                .put("k", e.kind.name)
                .put("l", e.layer)
                .put("nat", rectJson(nat))
                .put("set", rectJson(set))
                .apply { if (e.group.isNotEmpty()) put("g", e.group.take(8)) }
        )
    }

    return JSONObject()
        .put("profile", profile.name)
        .put("model", Build.MODEL)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("screenDp", "${screenW}x${screenH}")
        .put("density", density.toDouble())
        .put("fontScale", fontScale.toDouble())
        .put("design", "${board.w.toInt()}x${board.h.toInt()}")
        .put("clashes", clashes)
        .put("moved", moved)
        .put("elems", elems)
        .toString()
}

private fun rectJson(r: Rect): JSONArray =
    JSONArray(listOf(r.left, r.top, r.w, r.h).map { it.toDouble() })
