package com.example.myapplication

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale

/** How commands reach the server. UDP = fast, LAN. TCP = works through any tunnel / port forward. */
enum class Transport { UDP, TCP }

/**
 * Sends commands to the PC server and measures round-trip time.
 * The wire format is the same on both transports: "<token>|<cmd>|<arg>".
 * UDP sends one command per datagram; TCP sends one per frame (2-byte big-endian length + bytes).
 * The class name is historical, it handles both transports.
 */
class UdpClient(
    private val host: String,
    private val token: String,
    private val port: Int = 5005,
    val transport: Transport = Transport.UDP,
    private val frameMs: Long = 16L,   // how often pointer moves are sent (8 = smoother, more packets)
) {
    /** Round-trip time to the PC in ms, or null when the PC isn't answering. */
    var latencyMs by mutableStateOf<Int?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val udp: DatagramSocket? = if (transport == Transport.UDP) DatagramSocket() else null
    @Volatile
    private var tcp: Socket? = null
    private val out = Channel<String>(Channel.UNLIMITED)
    private val lock = Any()
    private var dx = 0f
    private var dy = 0f
    private var scrollAcc = 0f

    @Volatile
    private var lastPong = System.nanoTime()

    init {
        if (transport == Transport.UDP) startUdp() else startTcp()

        // mouse batching (~60 Hz)
        scope.launch {
            while (isActive) {
                delay(frameMs)
                var x = 0f
                var y = 0f
                synchronized(lock) { x = dx; y = dy; dx = 0f; dy = 0f }
                if (x != 0f || y != 0f) send("M|%.1f|%.1f".format(Locale.US, x, y))
            }
        }
        // ping once per second; no answer for 3 s -> offline
        scope.launch {
            while (isActive) {
                send("P|${System.nanoTime()}")
                delay(1000)
                if (System.nanoTime() - lastPong > 3_000_000_000L) latencyMs = null
            }
        }
    }

    private fun onIncoming(text: String) {
        if (text.startsWith("PONG|")) {
            val sent = text.substring(5).toLongOrNull() ?: return
            lastPong = System.nanoTime()
            latencyMs = ((lastPong - sent) / 1_000_000L).toInt()
        }
    }

    // ------------------------------------------------------------ UDP

    private fun startUdp() {
        val sock = udp ?: return
        // sender
        scope.launch {
            val addr = InetAddress.getByName(host)
            for (msg in out) {
                try {
                    val b = "$token|$msg".toByteArray()
                    sock.send(DatagramPacket(b, b.size, addr, port))
                } catch (_: Exception) { }
            }
        }
        // receiver
        scope.launch {
            val buf = ByteArray(256)
            while (isActive) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    onIncoming(String(p.data, 0, p.length))
                } catch (_: SocketException) {
                    break // socket closed
                } catch (_: Exception) { }
            }
        }
    }

    // ------------------------------------------------------------ TCP

    private fun startTcp() {
        scope.launch {
            while (isActive) {
                val s = Socket()
                var reader: Job? = null
                try {
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    s.connect(InetSocketAddress(host, port), 4000)
                    tcp = s
                    while (out.tryReceive().isSuccess) { /* drop stale commands queued while offline */ }
                    reader = launch {
                        try {
                            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 4096))
                            while (isActive) {
                                val n = input.readUnsignedShort()
                                val b = ByteArray(n)
                                input.readFully(b)
                                onIncoming(String(b))
                            }
                        } catch (_: Exception) { }
                        try { s.close() } catch (_: Exception) { }   // makes the writer notice
                    }
                    val os = s.getOutputStream()
                    for (msg in out) {
                        val b = "$token|$msg".toByteArray()
                        val frame = ByteArray(b.size + 2)
                        frame[0] = (b.size shr 8).toByte()
                        frame[1] = (b.size and 0xFF).toByte()
                        System.arraycopy(b, 0, frame, 2, b.size)
                        os.write(frame)
                        os.flush()
                    }
                } catch (_: Exception) {
                    // connect failed or the connection dropped: fall through and retry
                }
                reader?.cancel()
                try { s.close() } catch (_: Exception) { }
                tcp = null
                latencyMs = null
                // throw away commands queued while offline, replaying old mouse moves is worse than losing them
                while (out.tryReceive().isSuccess) { /* drain */ }
                delay(1500)
            }
        }
    }

    // ------------------------------------------------------------ commands

    fun send(body: String) { out.trySend(body) }

    fun move(x: Float, y: Float) = synchronized(lock) { dx += x; dy += y }
    fun click(b: String) = send("C|$b")          // L, R, D
    fun buttonDown(b: String) = send("BD|$b")    // hold a mouse button (drag)
    fun buttonUp(b: String) = send("BU|$b")
    fun moveTo(fx: Float, fy: Float) = send("A|%.4f|%.4f".format(Locale.US, fx, fy))   // 0..1 of the screen
    fun type(s: String) = send("T|$s")
    fun key(k: String) = send("K|$k")
    fun hotkey(combo: String) = send("H|$combo")
    fun diag(json: String) = send("L|${json.replace("\n", " ")}")   // layout diagnostics, saved by the server
    fun ui(msg: String) = send("E|${msg.replace("\n", " ")}")       // one UI action, written to server.log

    // hold / release, used by the controller layout
    fun keyDown(k: String) = send("D|$k")
    fun keyUp(k: String) { send("U|$k"); send("U|$k") }   // twice: a lost "up" would leave a key stuck
    fun releaseAll() = send("X|")

    fun scroll(amount: Float) {                  // accumulate fractions, send whole steps
        scrollAcc += amount
        val steps = scrollAcc.toInt()
        if (steps != 0) { send("S|$steps"); scrollAcc -= steps }
    }

    fun close() {
        scope.cancel()
        try { udp?.close() } catch (_: Exception) { }
        try { tcp?.close() } catch (_: Exception) { }
    }
}

data class Found(val ip: String, val name: String)

/**
 * Finds running servers on the LAN (UDP only). Two strategies at once, so it works even when
 * the router blocks broadcasts between Wi-Fi and Ethernet:
 *  1. broadcast to the subnet's broadcast address (and 255.255.255.255)
 *  2. direct probe to every address in the phone's own /24
 */
suspend fun discoverServers(port: Int = 5005, timeoutMs: Long = 2500): List<Found> =
    withContext(Dispatchers.IO) {
        val msg = "DISCOVER".toByteArray()
        val targets = LinkedHashSet<InetAddress>()

        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a !is Inet4Address || !a.isSiteLocalAddress) continue
                    ia.broadcast?.let { targets += it }
                    val o = a.address
                    for (i in 1..254) {
                        if (i == (o[3].toInt() and 0xFF)) continue
                        targets += InetAddress.getByAddress(byteArrayOf(o[0], o[1], o[2], i.toByte()))
                    }
                }
            }
        } catch (_: Exception) { }
        targets += InetAddress.getByName("255.255.255.255")

        val found = LinkedHashMap<String, Found>()
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 200

            fun blast() {
                for (t in targets) {
                    try { s.send(DatagramPacket(msg, msg.size, t, port)) } catch (_: Exception) { }
                }
            }

            val start = System.currentTimeMillis()
            val end = start + timeoutMs
            var resent = false
            blast()
            val buf = ByteArray(256)
            while (System.currentTimeMillis() < end) {
                if (!resent && System.currentTimeMillis() - start > 800) { blast(); resent = true }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val text = String(p.data, 0, p.length)
                    val ip = p.address.hostAddress
                    if (ip != null && text.startsWith("HERE|"))
                        found[ip] = Found(ip, text.removePrefix("HERE|"))
                } catch (_: SocketTimeoutException) { }
            }
        }
        found.values.toList()
    }
