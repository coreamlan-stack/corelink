package dev.coreamlan.corelink

import android.graphics.Bitmap
import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.zip.Inflater
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

/** Prototype receiver. The paired TLS socket and authentication are provided by CoreLinkClient. */
@Composable
fun DesktopVideo(open: () -> Result<SSLSocket>, modifier: Modifier = Modifier) {
    var status by remember { mutableStateOf("Waiting for Windows capture…") }
    var imageView by remember { mutableStateOf<ImageView?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val closed = AtomicBoolean(false)
        val socket = AtomicReference<SSLSocket?>(null)
        val job = scope.launch(Dispatchers.IO) {
            while (isActive && !closed.get()) {
              try {
                val connection = open().getOrThrow()
                socket.set(connection)
                if (closed.get()) return@launch
                connection.soTimeout = 15_000
                val input = DataInputStream(connection.inputStream)
                val header = JSONObject(readHeader(input))
                check(header.optString("type") == "video_started" && header.optString("format") == "bgra-zlib") {
                    if (header.optString("type") == "video_unavailable") "Waiting for the PC to share its screen…"
                    else header.optString("message", "Windows capture is not available. Check that the companion and screen capture are running on your PC.")
                }
                val width = header.getInt("width")
                val height = header.getInt("height")
                check(width in 1..3840 && height in 1..2160 && width.toLong() * height <= 8_294_400) { "Unsupported video size" }
                val rawSize = width * height * 4
                withContext(Dispatchers.Main) { status = "" }
                while (true) {
                    val size = input.readInt()
                    check(size in 1..8 * 1024 * 1024) { "Invalid video frame size" }
                    val compressed = ByteArray(size)
                    input.readFully(compressed)
                    val pixels = inflateFrame(compressed, rawSize)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
                    withContext(Dispatchers.Main) { imageView?.setImageBitmap(bitmap) }
                }
            } catch (e: Exception) {
                if (!closed.get() && isActive) withContext(Dispatchers.Main) { status = e.message ?: "Reconnecting to the PC…" }
            } finally {
                runCatching { socket.getAndSet(null)?.close() }
            }
              if (isActive && !closed.get()) delay(1500)
            }
        }
        onDispose { closed.set(true); runCatching { socket.getAndSet(null)?.close() }; job.cancel() }
    }
    Box(modifier) {
        AndroidView(factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; imageView = this } }, modifier = Modifier.fillMaxSize())
        if (status.isNotEmpty()) Text(status)
    }
}

private fun readHeader(input: DataInputStream): String {
    val bytes = ArrayList<Byte>()
    while (bytes.size < 4096) {
        val byte = input.readByte()
        if (byte == '\n'.code.toByte()) return bytes.toByteArray().toString(Charsets.UTF_8)
        bytes.add(byte)
    }
    error("Video response is too large")
}

internal fun inflateFrame(compressed: ByteArray, expected: Int): ByteArray {
    val inflater = Inflater()
    try {
        inflater.setInput(compressed)
        val result = ByteArray(expected)
        var offset = 0
        while (offset < expected && !inflater.finished()) {
            val count = inflater.inflate(result, offset, expected - offset)
            check(count > 0) { "Invalid video frame" }
            offset += count
        }
        if (offset == expected && !inflater.finished()) {
            check(inflater.inflate(ByteArray(1)) == 0) { "Video frame exceeds expected size" }
        }
        check(offset == expected && inflater.finished() && inflater.remaining == 0) { "Invalid video frame length" }
        return result
    } finally { inflater.end() }
}
