package dev.coreamlan.corelink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

class DesktopVideoTest {
    private fun compress(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    @Test fun decodesCompleteFrame() {
        val frame = ByteArray(4096) { (it % 251).toByte() }
        assertArrayEquals(frame, inflateFrame(compress(frame), frame.size))
    }

    @Test fun rejectsTruncatedAndOversizedFrames() {
        val compressed = compress(ByteArray(64) { 42 })
        assertThrows(Exception::class.java) { inflateFrame(compressed.copyOf(compressed.size - 1), 64) }
        assertThrows(Exception::class.java) { inflateFrame(compressed, 32) }
    }
}
