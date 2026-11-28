package dev.coreamlan.corelink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DesktopAudioTest {
    @Test fun writesFloatPacketsAcrossPartialFrameAlignedWrites() {
        val source = byteArrayOf(0, 0, 0x80.toByte(), 0x3f, 0, 0, 0, 0x3f)
        val received = ArrayList<Byte>()
        writeAudioPacket(source, 4, { false }) { buffer ->
            repeat(4) { received.add(buffer.get()) }
            4
        }
        assertArrayEquals(source, received.toByteArray())
    }

    @Test fun rejectsMisalignedPcmAndReportsAudioWriteErrors() {
        assertThrows(IllegalArgumentException::class.java) { writeAudioPacket(ByteArray(3), 4, { false }) { 0 } }
        assertThrows(IllegalStateException::class.java) { writeAudioPacket(ByteArray(4), 4, { false }) { -3 } }
    }
}
