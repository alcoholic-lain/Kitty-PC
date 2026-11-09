package com.example.myapplication

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val prefs = getSharedPreferences("remote", Context.MODE_PRIVATE)
        val settings = AppSettings(prefs)
        val profiles = ProfilesState(prefs)
        setContent {
            RemoteTheme(settings.theme, settings.overrides) {
                SharedTransitionLayout {
                    CompositionLocalProvider(LocalComposerSharedTransition provides this) {
                        CompositionLocalProvider(LocalSettings provides settings, LocalProfiles provides profiles) {
                            Scaffold(Modifier.fillMaxSize()) { pad ->
                                var session by remember { mutableStateOf<Session?>(null) }
                                var showSettings by remember { mutableStateOf(false) }

                                // The connection belongs to the session, not to a screen: opening Settings from
                                // the remote must not close it.
                                fun endSession() {
                                    session?.client?.close()
                                    session = null
                                }
                                DisposableEffect(Unit) { onDispose { session?.client?.close() } }

                                val base = Modifier.padding(pad).consumeWindowInsets(pad)
                                // Forms lift above the keyboard. The remote does NOT: its board keeps its size
                                // and the Type bar rides on top of the keyboard instead (see Composer.kt).
                                val form = base.imePadding().padding(16.dp)
                                val cur = session
                                when {
                                    showSettings -> SettingsScreen(form, onBack = { showSettings = false })
                                    cur == null -> ConnectScreen(form, onSettings = { showSettings = true }) { session = it }
                                    else -> RemoteScreen(cur, base, onExit = { endSession() }, onSettings = { showSettings = true })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One open connection: the command link plus what the screen stream needs. */
class Session(val client: UdpClient, val host: String, val token: String, val streamPort: Int)

private const val MAX_RECENT = 8
private const val DEFAULT_IP = "192.168.100.68"

// ---------------------------------------------------------------- connect

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectScreen(modifier: Modifier, onSettings: () -> Unit, onConnect: (Session) -> Unit) {
    val settings = LocalSettings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("remote", Context.MODE_PRIVATE) }

    // saved IPs, most recent first
    val recents = remember {
        mutableStateListOf<String>().apply {
            addAll(prefs.getString("recent_ips", "")!!.split(",").filter { it.isNotBlank() })
        }
    }

    var ip by remember { mutableStateOf(recents.firstOrNull() ?: DEFAULT_IP) }
    var token by remember { mutableStateOf(prefs.getString("token", "change-me") ?: "change-me") }
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val scan: () -> Unit = {
        scope.launch {
            scanning = true
            found = discoverServers(settings.udpPort)
            scanning = false
            if (found.isNotEmpty() && (found.size == 1 || ip.isBlank() || ip.endsWith(".")))
                ip = found[0].ip
        }
    }
    LaunchedEffect(Unit) { scan() }

    fun saveRecents() =
        prefs.edit().putString("recent_ips", recents.joinToString(",")).apply()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Remote", fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (scanning) "Searching for your PC…"
            else if (found.isEmpty()) "No PC found, enter the IP below"
            else "Found on your network",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            found.forEach { f ->
                FilterChip(
                    selected = ip == f.ip,
                    onClick = { ip = f.ip },
                    label = { Text("${f.name}  (${f.ip})") },
                )
            }
        }
        TextButton(onClick = scan, enabled = !scanning) { Text("Rescan") }

        if (recents.isNotEmpty()) {
            Text("Recent", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recents.forEach { r ->
                    FilterChip(selected = ip == r, onClick = { ip = r }, label = { Text(r) })
                }
            }
            TextButton(onClick = { recents.clear(); saveRecents() }) { Text("Clear saved IPs") }
        }

        OutlinedTextField(
            ip, { ip = it }, label = { Text("Desktop IP") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            token, { token = it }, label = { Text("Token") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Connecting over ${if (settings.transport == Transport.TCP) "TCP" else "UDP"} " +
                "port ${settings.commandPort}, screen on port ${settings.streamPort}. Change these in Settings.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Text("UI build 3", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val clean = ip.trim()
                recents.remove(clean)
                recents.add(0, clean)
                while (recents.size > MAX_RECENT) recents.removeAt(recents.lastIndex)
                saveRecents()
                prefs.edit().putString("token", token).apply()
                val client = UdpClient(
                    clean, token, settings.commandPort, settings.transport,
                    frameMs = if (settings.smoothCursor) 8L else 16L,
                )
                client.ui("connect $clean ${settings.transport.name}")
                onConnect(Session(client, clean, token, settings.streamPort))
            },
            enabled = ip.trim().count { it == '.' } == 3 && !ip.trim().endsWith(".") &&
                settings.commandPort in 1..65535,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text("Connect") }
    }
}

// ---------------------------------------------------------------- remote

/**
 * The connected view. Everything you control is a profile (see CustomPad.kt), so this only holds
 * the menu, the connection badge, the full screen state and the Type bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(s: Session, modifier: Modifier, onExit: () -> Unit, onSettings: () -> Unit) {
    val c = s.client
    var fullscreen by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val composer = remember { ComposerState() }

    // back closes the sidebar first, then leaves full screen, then leaves the remote
    // (the Type bar has its own handler, registered later, so it closes first of all)
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    BackHandler(enabled = !drawer.isOpen && !fullscreen, onBack = onExit)

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,   // no edge swipe opening the menu while you play
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Remote",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                )
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = false,
                    onClick = {
                        scope.launch { drawer.close() }
                        onSettings()
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                NavigationDrawerItem(
                    label = { Text("Disconnect") },
                    selected = false,
                    onClick = { c.ui("disconnect"); onExit() },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        },
    ) {
        CompositionLocalProvider(LocalComposer provides composer) {
            Box(modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().padding(if (fullscreen) 0.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!fullscreen) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { scope.launch { drawer.open() } }) { Text("Menu") }
                            LatencyBadge(c.latencyMs)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        CustomPadMode(c, s, fullscreen, onFullscreen = { fullscreen = it })
                    }
                }
                ComposerOverlay(composer, c, s)
            }
        }
    }
}
