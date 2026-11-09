package com.example.myapplication

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings page: connection, controls, screen stream, colours and theme. Saved through AppSettings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(modifier: Modifier, onBack: () -> Unit) {
    val s = LocalSettings.current
    BackHandler(onBack = onBack)
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }

        SettingsSection("Connection") {
            Text("Transport", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = s.transport == Transport.UDP,
                    onClick = { s.saveTransport(Transport.UDP) },
                    label = { Text("UDP · direct / LAN") },
                )
                FilterChip(
                    selected = s.transport == Transport.TCP,
                    onClick = { s.saveTransport(Transport.TCP) },
                    label = { Text("TCP · tunnel") },
                )
            }
            PortField("UDP port", s.udpPort) { s.saveUdpPort(it) }
            PortField("TCP port", s.tcpPort) { s.saveTcpPort(it) }
            PortField("Screen stream port (TCP)", s.streamPort) { s.saveStreamPort(it) }
            Text(
                "These must match config.json on the PC. Auto-discovery only works over UDP.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }

        SettingsSection("Controls") {
            Text("Sensitivity  ${"%.1f".format(s.sensitivity)}×")
            Slider(
                value = s.sensitivity,
                onValueChange = { s.saveSensitivity(it) },
                valueRange = 0.3f..3f,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Smooth cursor")
                    Text(
                        "Send pointer moves every 8 ms instead of 16 ms",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                Switch(checked = s.smoothCursor, onCheckedChange = { s.saveSmoothCursor(it) })
            }
        }

        SettingsSection("Screen") {
            Text("Control with", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = s.screenTouch,
                    onClick = { s.saveScreenTouch(true) },
                    label = { Text("Tap to click") },
                )
                FilterChip(
                    selected = !s.screenTouch,
                    onClick = { s.saveScreenTouch(false) },
                    label = { Text("Trackpad") },
                )
            }
            Text("Picture", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !s.screenFill,
                    onClick = { s.saveScreenFill(false) },
                    label = { Text("Fit: whole PC screen") },
                )
                FilterChip(
                    selected = s.screenFill,
                    onClick = { s.saveScreenFill(true) },
                    label = { Text("Fill: no bars, edges cropped") },
                )
            }
            StreamChoices("Frames per second", listOf(5, 10, 15, 30), s.streamFps, " fps") { s.saveStreamFps(it) }
            StreamChoices("Quality", listOf(40, 60, 80), s.streamQuality, "%") { s.saveStreamQuality(it) }
            StreamChoices("Width", listOf(720, 1080, 1280, 1920), s.streamWidth, " px") { s.saveStreamWidth(it) }
        }

        SettingsSection("Colours") {
            Text(
                "Tap a colour to use it. Reset goes back to the theme's own colour.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            listOf(
                "bg" to "Background",
                "surface" to "Buttons and panels",
                "text" to "Text",
                "outline" to "Outlines (leave empty to follow the accent)",
                "accent" to "Accent: headings, switches, selections and outlines",
            ).forEach { (role, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f))
                    TextButton(onClick = { s.saveColor(role, null) }) { Text("Reset") }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SWATCHES.forEach { col ->
                        val picked = s.overrides.byRole(role) == col
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(col)
                                .border(if (picked) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { s.saveColor(role, col) }
                        )
                    }
                }
            }
        }

        SettingsSection("Theme") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                THEMES.forEach { (key, label) ->
                    FilterChip(
                        selected = s.theme == key,
                        onClick = { s.saveTheme(key) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun PortField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.takeIf { it in 1..65535 }?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
