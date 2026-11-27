package dev.coreamlan.corelink

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

/** Experimental PCM loopback. Phone media-volume keys control AudioTrack output. */
@Composable
fun DesktopAudio(open: () -> Result<SSLSocket>) {
    var warning by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val closed = AtomicBoolean(false)
        val socket = AtomicReference<SSLSocket?>(null)
        val job = scope.launch(Dispatchers.IO) {
            var track: AudioTrack? = null
            try {
                val connection = open().getOrThrow()
                socket.set(connection)
                if (closed.get()) return@launch
                connection.soTimeout = 15_000
                val input = DataInputStream(connection.inputStream)
                val headerBytes = ArrayList<Byte>()
                while (headerBytes.size < 4096) { val b = input.readByte(); if (b == '\n'.code.toByte()) break; headerBytes.add(b) }
                check(headerBytes.size < 4096) { "Invalid audio response" }
                val header = JSONObject(headerBytes.toByteArray().toString(Charsets.UTF_8))
                check(header.optString("type") == "audio_started") { "Start screen sharing to hear PC audio" }
                val sampleRate = header.getInt("sample_rate"); val channels = header.getInt("channels")
                check(sampleRate in 8000..192000 && channels in 1..2) { "Unsupported PC audio format" }
                val format = when (header.getString("format")) { "float32" -> AudioFormat.ENCODING_PCM_FLOAT; "pcm16" -> AudioFormat.ENCODING_PCM_16BIT; else -> error("Unsupported PC audio format") }
                val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val minBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, format)
                check(minBytes > 0) { "Phone cannot play this audio format" }
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(format).setSampleRate(sampleRate).setChannelMask(channelMask).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(minBytes * 2).build()
                track = audioTrack
                check(audioTrack.state == AudioTrack.STATE_INITIALIZED) { "Phone audio output unavailable" }
                audioTrack.play()
                while (!closed.get()) {
                    val size = input.readInt()
                    check(size in 0..1024 * 1024) { "Invalid audio packet" }
                    if (size == 0) continue
                    val bytes = ByteArray(size); input.readFully(bytes)
                    var offset = 0
                    while (offset < bytes.size && !closed.get()) {
                        val written = audioTrack.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                        check(written > 0) { "Audio playback stopped" }; offset += written
                    }
                }
            } catch (e: Exception) {
                if (!closed.get()) withContext(Dispatchers.Main) { warning = e.message ?: "PC audio disconnected" }
            } finally {
                runCatching { track?.stop() }; runCatching { track?.release() }
                runCatching { socket.getAndSet(null)?.close() }
            }
        }
        onDispose { closed.set(true); runCatching { socket.getAndSet(null)?.close() }; job.cancel() }
    }
    if (warning.isNotEmpty()) Text(warning)
}
