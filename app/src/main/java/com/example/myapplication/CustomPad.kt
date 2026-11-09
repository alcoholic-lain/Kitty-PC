package com.example.myapplication

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.widget.Toast
import java.util.UUID

/** The profile store (Profiles.kt), provided by MainActivity. */
val LocalProfiles = compositionLocalOf<ProfilesState> { error("ProfilesState not provided") }

/*
 * The Controller tab. Each profile is a HUD: components on a snapping board (Hud.kt, Layout.kt).
 *  - Play: each component works as its kind says (Gestures.kt).
 *  - Edit: the board takes the whole screen. One toolbar at the bottom holds the edit actions;
 *    the component list and the profile actions open in sheets.
 *  - Select: tap components to pick them, then Group. A group moves and resizes as one.
 *  - Full screen: the HUD fills the phone and the system bars hide. Exit is in the corner.
 */

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Which bottom sheet is open while editing. */
private enum class Sheet { ADD, PROFILES }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CustomPadMode(c: UdpClient, s: Session, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    val store = LocalProfiles.current
    val settings = LocalSettings.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val active = store.active
    val hasScreen = active.elems.any { it.kind == Kind.SCREEN }
    val pad = remember(c) { VirtualPad(c) }
    DisposableEffect(pad) { onDispose { pad.reset(); pad.close() } }
    // the picture stream only runs while the profile has a screen component
    val screen = remember(s, hasScreen, settings.streamFps, settings.streamWidth, settings.streamQuality) {
        if (hasScreen) ScreenStream(s.host, s.streamPort, s.token, settings.streamFps, settings.streamWidth, settings.streamQuality)
        else null
    }
    DisposableEffect(screen) { onDispose { screen?.close() } }
    // profile actions and the screen's own actions go to the server's UI log
    DisposableEffect(c) {
        store.logger = { msg -> c.ui(msg) }
        onDispose { store.logger = {} }
    }
    ImmersiveEffect(fullscreen)

    // a profile drawn sideways turns the phone sideways; the others stay upright
    // (full screen is always sideways: the PC picture is wide)
    DisposableEffect(active.landscape, fullscreen) {
        val activity = context.findActivity()
        activity?.requestedOrientation =
            if (active.landscape || fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    var editing by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var renaming by remember { mutableStateOf(false) }
    val selected = active.elems.firstOrNull { it.id == selectedId }

    fun clearSelection() {
        selectedId = null
        picked = emptySet()
    }

    fun replaceMany(list: List<Elem>) {
        val cur = store.active
        val byId = list.associateBy { it.id }
        store.setElems(cur.id, cur.elems.map { byId[it.id] ?: it })
    }

    fun replace(e: Elem) = replaceMany(listOf(e))

    fun addElem(k: Kind) {
        c.ui("add ${k.name}")
        val e = newElem(k)
        store.setElems(store.active.id, store.active.elems + e)
        selectedId = e.id
    }

    fun groupPicked() {
        c.ui("group ${picked.size} components")
        val gid = UUID.randomUUID().toString()
        replaceMany(store.active.elems.filter { it.id in picked }.map { it.copy(group = gid) })
        picked = emptySet()
    }

    fun ungroupPicked() {
        c.ui("ungroup ${picked.size} components")
        val cur = store.active
        val groups = cur.elems.filter { it.id in picked }.map { it.group }.filter { it.isNotEmpty() }.toSet()
        replaceMany(cur.elems.filter { it.group in groups }.map { it.copy(group = "") })
        picked = emptySet()
    }

    if (fullscreen) {
        // the whole phone is the PC picture; no board, no frame
        FullScreenRemote(screen, c) { c.ui("fullscreen off"); onFullscreen(false) }
    } else {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // profiles, edit switch, full screen: one row
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                store.profiles.forEach { p ->
                    FilterChip(
                        selected = p.id == active.id,
                        onClick = { store.select(p.id); clearSelection() },
                        label = { Text(p.name) },
                    )
                }
                FilterChip(
                    selected = editing,
                    onClick = { c.ui("edit mode ${!editing}"); editing = !editing; selecting = false; clearSelection() },
                    label = { Text(if (editing) "Done" else "Edit") },
                )
                if (hasScreen) {
                    AssistChip(
                        onClick = { c.ui("fullscreen on"); editing = false; clearSelection(); onFullscreen(true) },
                        label = { Text("Full screen") },
                    )
                }
            }

            Hud(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                elems = active.elems, board = active.board, editing = editing, selectedId = selectedId, picked = picked,
                pad = pad, c = c, screen = screen,
                onSelect = { id ->
                    if (selecting) {
                        picked = if (id in picked) picked - id else picked + id
                    } else {
                        selectedId = id
                    }
                },
                onChange = { replaceMany(it) },
            )

            // edit toolbar: one row at the bottom, so the board keeps the rest of the screen
            if (editing) {
                val grouped = active.elems.any { it.id in picked && it.group.isNotEmpty() }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(onClick = { sheet = Sheet.ADD }, label = { Text("+ Add") })
                    FilterChip(
                        selected = selecting,
                        onClick = { c.ui("select mode ${!selecting}"); selecting = !selecting; clearSelection() },
                        label = { Text("Select") },
                    )
                    if (picked.size >= 2) {
                        AssistChip(onClick = { groupPicked() }, label = { Text("Group ${picked.size}") })
                    }
                    if (grouped) {
                        AssistChip(onClick = { ungroupPicked() }, label = { Text("Ungroup") })
                    }
                    if (picked.isNotEmpty()) {
                        AssistChip(onClick = { picked = emptySet() }, label = { Text("Clear") })
                    }
                    AssistChip(
                        onClick = { clearSelection(); store.setLandscape(active.id, !active.landscape) },
                        label = { Text(if (active.landscape) "↻ Landscape" else "↻ Portrait") },
                    )
                    AssistChip(onClick = { sheet = Sheet.PROFILES }, label = { Text("Profiles") })
                    AssistChip(
                        onClick = {
                            c.diag(layoutDiagnostics(active, density.density, density.fontScale, config.screenWidthDp, config.screenHeightDp))
                            Toast.makeText(context, "Layout diagnostics sent", Toast.LENGTH_SHORT).show()
                        },
                        label = { Text("Diagnostics") },
                    )
                }
            }
        }
    }

    // bottom sheets: the component list and the profile actions
    when (sheet) {
        Sheet.ADD -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(
                Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Add a component", fontSize = 16.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Kind.values().forEach { k ->
                        AssistChip(onClick = { addElem(k); sheet = null }, label = { Text(shortName(k)) })
                    }
                }
            }
        }
        Sheet.PROFILES -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(
                Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Profiles", fontSize = 16.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { store.addFromTemplate(Templates.all.first { it.name == "Blank" }); clearSelection(); sheet = null }, label = { Text("New blank") })
                    AssistChip(onClick = { sheet = null; renaming = true }, label = { Text("Rename") })
                    AssistChip(onClick = { store.duplicate(active.id); clearSelection(); sheet = null }, label = { Text("Duplicate") })
                    AssistChip(onClick = { store.delete(active.id); clearSelection(); sheet = null }, label = { Text("Delete this profile") })
                    AssistChip(onClick = { store.restoreBuiltIns(); sheet = null }, label = { Text("Restore built-ins") })
                    AssistChip(onClick = { store.resetBuiltIns(); clearSelection(); sheet = null }, label = { Text("Reset built-ins to default") })
                }
                Text("New profile from a template", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Templates.all.forEach { t ->
                        AssistChip(onClick = { store.addFromTemplate(t); clearSelection(); sheet = null }, label = { Text(t.name + if (t.landscape) "  ↔" else "") })
                    }
                }
            }
        }
        null -> Unit
    }

    if (editing && !fullscreen && !selecting && selected != null) {
        ElementEditor(
            e = selected,
            onChange = { replace(it) },
            onDelete = {
                c.ui("delete ${selected.kind.name} '${selected.label}'")
                store.setElems(store.active.id, store.active.elems.filter { it.id != selected.id })
                selectedId = null
            },
            onClose = {
                c.ui("edit done ${selected?.kind?.name} '${selected?.label}' layer=${selected?.layer} w=${selected?.w?.toInt()} h=${selected?.h?.toInt()}")
                selectedId = null
            },
        )
    }
    if (renaming) {
        RenameDialog(
            current = active.name,
            onDone = { store.rename(active.id, it); renaming = false },
            onCancel = { renaming = false },
        )
    }
}

// ---------------------------------------------------------------- editor

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ElementEditor(e: Elem, onChange: (Elem) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    val colours = listOf(
        "Theme" to 0L, "Red" to 0xFFE34B4BL, "Green" to 0xFF5BC236L, "Blue" to 0xFF4A90E2L,
        "Yellow" to 0xFFF2C230L, "Purple" to 0xFF9B6BFFL, "White" to 0xFFFFFFFFL, "Black" to 0xFF000000L,
    )
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDelete) { Text("Delete", color = Palette.Bad) } },
        title = { Text("Edit ${e.kind.label.lowercase()}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = e.label,
                    onValueChange = { onChange(e.copy(label = it)) },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Type", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Kind.values().forEach { k ->
                        FilterChip(
                            selected = e.kind == k,
                            onClick = { onChange(e.copy(kind = k, action = newElem(k).action)) },
                            label = { Text(shortName(k)) },
                        )
                    }
                }

                when (e.kind) {
                    Kind.PAD -> {
                        Text("Gamepad button", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PAD_BITS.keys.forEach { name ->
                                FilterChip(
                                    selected = e.action == name,
                                    onClick = { onChange(e.copy(action = name, label = padLabel(name))) },
                                    label = { Text(padLabel(name)) },
                                )
                            }
                        }
                    }
                    Kind.STICK -> {
                        Text("Stick", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("L" to "Left", "R" to "Right").forEach { (code, name) ->
                                FilterChip(
                                    selected = e.action == code,
                                    onClick = { onChange(e.copy(action = code)) },
                                    label = { Text(name) },
                                )
                            }
                        }
                    }
                    Kind.MOUSE -> {
                        Text("Mouse button", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("L" to "Left", "R" to "Right", "M" to "Middle").forEach { (code, name) ->
                                FilterChip(
                                    selected = e.action == code,
                                    onClick = { onChange(e.copy(action = code)) },
                                    label = { Text(name) },
                                )
                            }
                        }
                    }
                    Kind.KEY -> OutlinedTextField(
                        value = e.action,
                        onValueChange = { onChange(e.copy(action = it)) },
                        label = { Text("Key, e.g. space, w, shift, enter") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Kind.HOTKEY -> OutlinedTextField(
                        value = e.action,
                        onValueChange = { onChange(e.copy(action = it)) },
                        label = { Text("Shortcut, e.g. ctrl+shift+s, alt+tab") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Kind.LOOK -> {
                        Text("Drag inside to move the mouse. Speed:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Slider(value = e.sens, onValueChange = { onChange(e.copy(sens = it)) }, valueRange = 0.3f..3f)
                    }
                    Kind.SCREEN, Kind.TOUCHPAD, Kind.KEYBOARD, Kind.TYPEBOX -> Text(
                        e.kind.hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }

                Text("Layer", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LAYER_NAMES.forEachIndexed { i, name ->
                        FilterChip(
                            selected = e.layer == i,
                            onClick = { onChange(e.copy(layer = i)) },
                            label = { Text(name) },
                        )
                    }
                }

                Text("Width ${e.w.toInt()} dp", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Slider(value = e.w, onValueChange = { onChange(e.copy(w = it)) }, valueRange = 24f..800f)
                Text("Height ${e.h.toInt()} dp", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Slider(value = e.h, onValueChange = { onChange(e.copy(h = it)) }, valueRange = 24f..800f)

                Text("Opacity ${(e.alpha * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Slider(value = e.alpha, onValueChange = { onChange(e.copy(alpha = it)) }, valueRange = 0.1f..1f)

                Text("Shape", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BtnShape.values().forEach { sh ->
                        FilterChip(
                            selected = e.shape == sh,
                            onClick = { onChange(e.copy(shape = sh)) },
                            label = { Text(sh.name.lowercase()) },
                        )
                    }
                }

                Text("Fill colour", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    colours.forEach { (name, value) ->
                        FilterChip(
                            selected = e.color == value,
                            onClick = { onChange(e.copy(color = value)) },
                            label = { Text(name) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun RenameDialog(current: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onDone(name.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        title = { Text("Profile name") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
    )
}

private fun shortName(k: Kind): String = when (k) {
    Kind.PAD -> "Button"
    Kind.KEY -> "Key"
    Kind.HOTKEY -> "Shortcut"
    Kind.STICK -> "Stick"
    Kind.LOOK -> "Look"
    Kind.MOUSE -> "Mouse"
    Kind.SCREEN -> "Screen"
    Kind.TOUCHPAD -> "Touchpad"
    Kind.KEYBOARD -> "Keyboard"
    Kind.TYPEBOX -> "Type box"
}
