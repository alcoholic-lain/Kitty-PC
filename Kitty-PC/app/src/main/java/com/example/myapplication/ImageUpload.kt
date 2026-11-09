package com.example.myapplication

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/*
 * Sending a picture from the phone to the PC.
 *
 *  1. The picture comes from the gallery (system photo picker) or from the phone's clipboard.
 *  2. It is shrunk to at most MAX_SIDE px on the long side and re-encoded (JPEG, or PNG if it has transparency).
 *  3. It goes over the same TCP port as the screen stream (stream port, default 5006), as an "upload"
 *     request: token, "upload;ext=jpg;paste=1;len=N", then N raw bytes. The server saves it, puts it on the
 *     PC clipboard and, when paste=1, presses Ctrl+V so it lands in whatever app has focus (chat, editor...).
 */

/** The long side of a sent picture, in px. Big enough for any chat or document, small enough to be quick. */
private const val MAX_SIDE = 2048

/** A picture that is ready to send. */
class PendingImage(
    val bytes: ByteArray,
    val ext: String,
    val preview: ImageBitmap,
    val width: Int,
    val height: Int,
) {
    val sizeLabel: String
        get() = "$width×$height · " + if (bytes.size >= 1_000_000) "%.1f MB".format(bytes.size / 1_000_000.0)
        else "${(bytes.size + 512) / 1024} KB"
}

/** First image in the phone's clipboard (a copied photo or screenshot), or null. */
fun clipboardImageUri(context: Context): Uri? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = cm.primaryClip ?: return null
    for (i in 0 until clip.itemCount) {
        val uri = clip.getItemAt(i).uri ?: continue
        val type = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
        if (type != null && type.startsWith("image/")) return uri
    }
    return null
}

/** Text in the phone's clipboard, or null when there is none. */
fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    val text = clip.getItemAt(0).coerceToText(context)?.toString()
    return text?.takeIf { it.isNotEmpty() }
}

/** Reads, rotates (EXIF), shrinks and encodes the picture at [uri]. Null if it can't be read as an image. */
suspend fun readImage(context: Context, uri: Uri): PendingImage? = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver

        // size first, so a 50 MP photo is never decoded at full size
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@withContext null

        // camera photos are often stored sideways with an EXIF flag
        val rotation = try {
            resolver.openInputStream(uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }

        val long = maxOf(bmp.width, bmp.height)
        val scale = if (long > MAX_SIDE) MAX_SIDE.toFloat() / long else 1f
        if (rotation != 0f || scale != 1f) {
            val m = Matrix().apply { postRotate(rotation); postScale(scale, scale) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }

        val png = bmp.hasAlpha()
        val out = ByteArrayOutputStream()
        if (png) bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        else bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)

        PendingImage(out.toByteArray(), if (png) "png" else "jpg", bmp.asImageBitmap(), bmp.width, bmp.height)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

private fun writeStr(out: DataOutputStream, s: String) {
    val b = s.toByteArray()
    out.writeShort(b.size)
    out.write(b)
}

/**
 * Sends [image] to the PC. With [paste] the PC also presses Ctrl+V. Returns the server's short report
 * ("saved, on clipboard, pasted") or the reason it failed.
 */
suspend fun uploadImage(host: String, port: Int, token: String, image: PendingImage, paste: Boolean): Result<String> =
    withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 4000)
                s.soTimeout = 30_000
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 16))
                val inp = DataInputStream(s.getInputStream())
                writeStr(out, token)
                writeStr(out, "upload;ext=${image.ext};paste=${if (paste) 1 else 0};len=${image.bytes.size}")
                out.write(image.bytes)
                out.flush()

                val n = try { inp.readUnsignedShort() } catch (_: Exception) {
                    error("The PC closed the connection. Check the token and that the server is up to date.")
                }
                val b = ByteArray(n)
                inp.readFully(b)
                val reply = String(b)
                if (!reply.startsWith("OK")) error(reply.removePrefix("ERR|").ifBlank { "The PC refused the picture." })
                reply.removePrefix("OK|").ifBlank { "Sent" }
            }
        }.recoverCatching { e ->
            // network trouble (PC off, wrong port, wrong token closes the socket): say it in plain words
            if (e is java.io.IOException) throw IllegalStateException("Couldn't send. Check the PC is on, the token and the screen port.")
            throw e
        }
    }
