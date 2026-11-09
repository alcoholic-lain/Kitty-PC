package com.example.myapplication

import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Receives JPEG frames of the PC screen from the server's stream port (TCP).
 * Handshake: 2-byte length + token, then 2-byte length + "fps=15;w=1280;q=60".
 * Then repeated: 4-byte length + JPEG bytes.
 * Create a new one when any setting changes; close() it when the Screen view closes.
 */
class ScreenStream(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val fps: Int,
    private val width: Int,
    private val quality: Int,
) {
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var connected by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var socket: Socket? = null

    private fun writeStr(out: DataOutputStream, s: String) {
        val b = s.toByteArray()
        out.writeShort(b.size)
        out.write(b)
    }

    init {
        scope.launch {
            while (isActive) {
                try {
                    val s = Socket()
                    socket = s
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(host, port), 4000)
                    val out = DataOutputStream(s.getOutputStream())
                    writeStr(out, token)
                    writeStr(out, "fps=$fps;w=$width;q=$quality")
                    out.flush()

                    val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
                    connected = true
                    while (isActive) {
                        val n = input.readInt()
                        if (n <= 0 || n > 8_000_000) break
                        val bytes = ByteArray(n)
                        input.readFully(bytes)
                        BitmapFactory.decodeByteArray(bytes, 0, n)?.let { frame = it.asImageBitmap() }
                    }
                } catch (_: Exception) {
                    // not reachable, wrong token, or the PC stopped streaming: retry below
                }
                connected = false
                try { socket?.close() } catch (_: Exception) { }
                socket = null
                delay(1500)
            }
        }
    }

    fun close() {
        scope.cancel()
        try { socket?.close() } catch (_: Exception) { }
    }
}
