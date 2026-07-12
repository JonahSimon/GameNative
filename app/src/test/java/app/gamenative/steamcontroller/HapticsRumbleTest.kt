package app.gamenative.steamcontroller

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Byte layout of the Triton rumble output report (SDL MsgHapticRumble, id 0x80, packed LE). */
class HapticsRumbleTest {
    @Test fun rumbleReport_packsMotorsLittleEndian() {
        val r = TritonHaptics.rumbleReport(0x1234, 0xABCD)
        assertEquals("10-byte report", 10, r.size)
        assertArrayEquals(
            byteArrayOf(
                0x80.toByte(),                 // id
                0,                             // type
                0, 0,                          // intensity
                0x34, 0x12,                    // left.speed (low-freq motor), LE
                0,                             // left.gain
                0xCD.toByte(), 0xAB.toByte(),  // right.speed (high-freq motor), LE
                0,                             // right.gain
            ),
            r,
        )
    }

    @Test fun rumbleReport_zeroIsAllZeroExceptId() {
        val r = TritonHaptics.rumbleReport(0, 0)
        assertEquals(0x80.toByte(), r[0])
        for (i in 1 until r.size) assertEquals("byte $i", 0.toByte(), r[i])
    }

    @Test fun rumbleReport_fullScaleClampsToTwoBytes() {
        val r = TritonHaptics.rumbleReport(0xFFFF, 0xFFFF)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), r.copyOfRange(4, 6))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), r.copyOfRange(7, 9))
    }
}
