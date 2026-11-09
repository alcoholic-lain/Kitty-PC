package com.example.myapplication

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/*
 * Typing on the phone, the way chat apps do it.
 *
 * The old Type box lived inside the scaled board, and the whole screen was resized every frame while the
 * keyboard slid up. That is what made it slow and made everything shrink. Now:
 *  - the board never changes size when the keyboard opens; the keyboard simply covers part of it;
 *  - tapping a Type box opens ONE bar that rides on top of the keyboard (imePadding), animated with it;
 *  - the bar also has the gallery and clipboard buttons for sending a picture to the PC;
 *  - back or tapping outside closes the bar and the keyboard together.
 */

/** Open / closed state of the bar, plus a picture waiting to be sent. Shared through [LocalComposer]. */
class ComposerState {
    var open by mutableStateOf(false)
        private set
    var pending by mutableStateOf<PendingImage?>(null)
    var busy by mutableStateOf(false)

    fun show() { open = true }
    fun hide() { open = false }
}

val LocalComposer = staticCompositionLocalOf { ComposerState() }
val LocalComposerSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }
private const val TYPE_INPUT_SHARED_KEY = "type-input-field"

/** What a Type box looks like on the board: a tap target that opens the composer. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TypeLauncher(background: Color, shape: androidx.compose.ui.graphics.Shape, alpha: Float) {
    val composer = LocalComposer.current
    val sharedTransition = LocalComposerSharedTransition.current
    AnimatedVisibility(
        visible = !composer.open,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
    ) {
        val visibilityScope = this
        Box(
            Modifier
                .fillMaxSize()
                .clickable { composer.show() },
            contentAlignment = Alignment.CenterStart,
        ) {
            val sharedModifier = if (sharedTransition != null) {
                with(sharedTransition) {
                    Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState(TYPE_INPUT_SHARED_KEY),
                        animatedVisibilityScope = visibilityScope,
                        boundsTransform = { _, _ -> tween(170, easing = FastOutSlowInEasing) },
                    )
                }
            } else Modifier
            Box(
                Modifier
                    .fillMaxSize()
                    .then(sharedModifier)
                    .clip(shape)
                    .background(background.copy(alpha = alpha))
                    .border(2.dp, MaterialTheme.colorScheme.primary, shape),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "Tap to type…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
    }
}

/** The composer bar, the dimmed backdrop and the picture preview. Put it last inside a Box that fills the screen. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun BoxScope.ComposerOverlay(state: ComposerState, c: UdpClient, s: Session) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val imeVisible = WindowInsets.isImeVisible
    var text by remember { mutableStateOf("") }
    var imeSeen by remember { mutableStateOf(false) }

    fun type(new: String) {
        sendTextDiff(c, text, new)
        text = new
    }

    fun take(uri: android.net.Uri) {
        scope.launch {
            val img = readImage(context, uri)
            if (img == null) Toast.makeText(context, "Couldn't read that picture", Toast.LENGTH_SHORT).show()
            else state.pending = img
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) take(uri)
    }

    // each time the bar opens: start empty, take focus, bring the keyboard up
    LaunchedEffect(state.open) {
        if (state.open) {
            text = ""
            imeSeen = false
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }
    // the keyboard was dismissed (back gesture, keyboard's own hide key): close the bar with it
    LaunchedEffect(imeVisible) {
        if (state.open) {
            if (imeVisible) imeSeen = true else if (imeSeen) state.hide()
        }
    }
    BackHandler(enabled = state.open) { state.hide() }

    // dim backdrop: tapping it closes the bar
    AnimatedVisibility(visible = state.open, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.hide() }
        )
    }

    AnimatedVisibility(
        visible = state.open,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = fadeIn(tween(90)),
        exit = fadeOut(tween(70)),
    ) {
        val visibilityScope = this
        val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(horizontal = 12.dp)
                .padding(top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    label = { Text("Gallery") },
                )
                AssistChip(
                    onClick = {
                        val uri = clipboardImageUri(context)
                        val clipText = if (uri == null) clipboardText(context) else null
                        when {
                            uri != null -> take(uri)
                            clipText != null -> type(text + clipText)
                            else -> Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                        }
                    },
                    label = { Text("Clipboard") },
                )
                AssistChip(onClick = { c.key("backspace") }, label = { Text("Backspace") })
                AssistChip(onClick = { c.key("tab") }, label = { Text("Tab") })
                AssistChip(onClick = { c.key("esc") }, label = { Text("Esc") })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val sharedTransition = LocalComposerSharedTransition.current
                val sharedModifier = if (sharedTransition != null) {
                    with(sharedTransition) {
                        Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState(TYPE_INPUT_SHARED_KEY),
                            animatedVisibilityScope = visibilityScope,
                            boundsTransform = { _, _ -> tween(170, easing = FastOutSlowInEasing) },
                        )
                    }
                } else Modifier
                OutlinedTextField(
                    value = text,
                    onValueChange = { type(it) },
                    placeholder = { Text("Type here, it appears on the PC…") },
                    modifier = Modifier.weight(1f).focusRequester(focus).then(sharedModifier),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        autoCorrectEnabled = true,
                        imeAction = ImeAction.Send,
                    ),
                    // Send on the phone keyboard = Enter on the PC, and the box starts again
                    keyboardActions = KeyboardActions(onSend = { c.key("enter"); text = "" }),
                )
                FilledTonalButton(onClick = { c.key("enter"); text = "" }) { Text("Enter") }
            }
        }
    }

    // picture preview: lives outside the bar, so it survives the keyboard closing while the picker is open
    val img = state.pending
    if (img != null) {
        // fully expanded and scrollable, so the buttons stay reachable with the phone sideways too
        ModalBottomSheet(
            onDismissRequest = { if (!state.busy) state.pending = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Send to PC", fontSize = 16.sp)
                Image(
                    bitmap = img.preview,
                    contentDescription = "Picture to send",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Text(img.sizeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

                fun send(paste: Boolean) {
                    state.busy = true
                    c.ui("image send ${img.width}x${img.height} ${img.bytes.size} bytes paste=$paste")
                    scope.launch {
                        val r = uploadImage(s.host, s.streamPort, s.token, img, paste)
                        state.busy = false
                        r.onSuccess {
                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                            state.pending = null
                        }.onFailure {
                            Toast.makeText(context, it.message ?: "Upload failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                Button(
                    onClick = { send(true) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text(if (state.busy) "Sending…" else "Send and paste on the PC") }
                OutlinedButton(
                    onClick = { send(false) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("Send to the PC clipboard only") }
                TextButton(
                    onClick = { state.pending = null },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel") }
            }
        }
    }
}
