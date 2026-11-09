package com.example.myapplication

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tap key. With repeat = true, holding it auto-repeats (backspace, arrows). */
@Composable
fun KeyCap(
    label: String,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    repeat: Boolean = false,
    active: Boolean = false,
    fontSize: TextUnit = 14.sp,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    var pressed by remember { mutableStateOf(false) }
    val action by rememberUpdatedState(onClick)
    val scope = rememberCoroutineScope()

    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(if (pressed || active) colors.primaryContainer else colors.surface)
            .border(1.dp, colors.outline, shape)
            .pointerInput(repeat) {
                detectTapGestures(onPress = {
                    pressed = true
                    action()
                    val job = if (repeat) scope.launch {
                        delay(400)
                        while (true) { action(); delay(60) }
                    } else null
                    try { awaitRelease() } finally { job?.cancel(); pressed = false }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = fontSize, color = colors.onSurface, maxLines = 1)
    }
}

/** Key that is held down while the finger is on it (games). */
@Composable
fun HoldButton(
    label: String,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp),
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var pressed by remember { mutableStateOf(false) }
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)

    Box(
        modifier
            .clip(shape)
            .background(if (pressed) colors.primaryContainer else colors.surface)
            .border(1.dp, colors.outline, shape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    down()
                    try { awaitRelease() } finally { pressed = false; up() }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, color = colors.onSurface)
    }
}
