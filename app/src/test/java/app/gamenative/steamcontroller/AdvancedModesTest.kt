package app.gamenative.steamcontroller

import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Unit tests for the step-7 stick modes (mouse / flickstick / response curve) and the OnRelease activator. */
@RunWith(RobolectricTestRunner::class)
class AdvancedModesTest {

    private fun stickState(lx: Int = 0, ly: Int = 0) = TritonState().apply { leftStickX = lx; leftStickY = ly }

    @Test
    fun `gyro pad-touch gate aims only while the pad is touched`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(gyro = GyroMode.Mouse(sensitivity = 5f, gate = GyroGate.RIGHT_PAD_TOUCH)), haptics = null)
        // Gyro rotating but pad NOT touched -> gated off, no aim.
        interp.apply(TritonState().apply { gyroZ = 500; gyroX = 200 })
        assertEquals(0, sink.mouseMoves)
        // Same rotation while the right pad is touched -> aim fires.
        interp.apply(TritonState().apply { gyroZ = 500; gyroX = 200; buttons = TritonProtocol.BTN_RPAD_TOUCH })
        assertTrue("gyro aims while pad touched", sink.mouseMoves > 0)
    }

    @Test
    fun `joystick_mouse drives the pointer from stick deflection`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(leftStick = StickMode.Mouse(sensitivity = 10f, deadzone = 0.1f)), haptics = null)
        interp.apply(stickState(lx = 32767, ly = 0)) // full right
        assertTrue("cursor moved right", sink.mouseDx > 0)
        assertEquals("no vertical for pure-X", 0L, sink.mouseDy)
    }

    @Test
    fun `joystick_mouse respects the deadzone`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(leftStick = StickMode.Mouse(sensitivity = 10f, deadzone = 0.5f)), haptics = null)
        interp.apply(stickState(lx = 3000, ly = 0)) // ~0.09, inside the 0.5 deadzone
        assertEquals(0, sink.mouseMoves)
    }

    @Test
    fun `flickstick maps horizontal deflection to yaw only`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(leftStick = StickMode.FlickStick(sensitivity = 20f, deadzone = 0.2f)), haptics = null)
        interp.apply(stickState(lx = 32767, ly = 32767)) // pushed up-right; flickstick uses X only
        assertTrue(sink.mouseDx > 0)
        assertEquals(0L, sink.mouseDy)
    }

    @Test
    fun `aggressive response curve attenuates mid deflection vs linear`() {
        val lin = RecordingSink()
        ProfileInterpreter(lin, ScProfile(leftStick = StickMode.JoystickMove(Stick.LEFT, invertY = false, curve = ResponseCurve.LINEAR)), haptics = null)
            .apply(stickState(lx = 16000)) // ~half deflection
        val agg = RecordingSink()
        ProfileInterpreter(agg, ScProfile(leftStick = StickMode.JoystickMove(Stick.LEFT, invertY = false, curve = ResponseCurve.AGGRESSIVE)), haptics = null)
            .apply(stickState(lx = 16000))
        assertTrue("aggressive curve < linear at mid", agg.maxThumbLX in 0f..lin.maxThumbLX && agg.maxThumbLX < lin.maxThumbLX)
    }

    private fun buttonA(down: Boolean) = TritonState().apply { buttons = if (down) TritonProtocol.BTN_A else 0 }

    @Test
    fun `OnRelease activator fires on the release edge, not on press`() {
        val sink = RecordingSink()
        val profile = ScProfile(buttons = mapOf(TritonProtocol.BTN_A to Binding(ScOutput.Key(XKeycode.KEY_F1), Activator.OnRelease)))
        val interp = ProfileInterpreter(sink, profile, haptics = null)
        interp.apply(buttonA(true))  // press -> nothing yet
        assertEquals(0, sink.keyPresses(XKeycode.KEY_F1))
        interp.apply(buttonA(false)) // release -> pulse
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F1))
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_F1 && !it.pressed })
    }
}
