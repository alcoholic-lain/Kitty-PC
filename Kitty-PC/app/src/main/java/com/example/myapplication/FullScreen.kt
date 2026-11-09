package com.example.myapplication

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Full screen: the PC picture fills the whole phone, nothing else. The phone turns sideways (see
 * CustomPad.kt) and you use the PC like a remote desktop: touch it directly, or switch to the
 * Chromebook trackpad. A small bar in the corner holds Exit, the touch mode and Type; it folds away
 * after a few seconds so it never covers the picture.
 */
@Composable
fun FullScreenRemote(screen: ScreenStream?, c: UdpClient, onExit: () -> Unit) {
    val settings = LocalSettings.current
    val composer = LocalComposer.current
    var expanded by remember { mutableStateOf(true) }
    // any change in the bar restarts the countdown
    LaunchedEffect(expanded, settings.screenTouch) {
        if (expanded) {
            delay(4000)
            expanded = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ScreenElement(screen, c, editing = false, modifier = Modifier.fillMaxSize())

        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .displayCutoutPadding()
                .padding(8.dp)
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (expanded) {
                AssistChip(onClick = onExit, label = { Text("Exit") })
                AssistChip(
                    onClick = { settings.saveScreenTouch(!settings.screenTouch) },
                    label = { Text(if (settings.screenTouch) "Touch" else "Trackpad") },
                )
                AssistChip(onClick = { composer.show() }, label = { Text("Type") })
            } else {
                AssistChip(onClick = { expanded = true }, label = { Text("More") })
            }
        }
    }
}
