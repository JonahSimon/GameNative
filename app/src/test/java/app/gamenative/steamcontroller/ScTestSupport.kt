package app.gamenative.steamcontroller

import com.winlator.inputcontrols.GamepadState
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode

/**
 * Test fakes for the mapping engine. [RecordingSink] captures every output the [ProfileInterpreter] emits
 * so tests can assert behaviour headlessly; [TraceReader] loads a captured golden trace (real controller
 * reports) for replay. See docs/AUTOMATION-PLAN.md.
 */

/** Records all interpreter outputs instead of doing IO. */
class RecordingSink : ScOutputSink {
    data class MouseBtn(val button: Pointer.Button, val pressed: Boolean)
    data class KeyEv(val key: XKeycode, val pressed: Boolean)

    /** Union of all virtual-pad button bits ever seen pressed. */
    var gamepadButtonsSeen: Int = 0
        private set
    val dpadSeen = BooleanArray(4)
    var maxThumbLX = 0f; var minThumbLX = 0f
    var maxThumbRX = 0f; var minThumbRX = 0f
    var maxTriggerL = 0f; var maxTriggerR = 0f
    // Most-recent per-frame stick values (for tests that need the current deflection, e.g. recenter-on-lift).
    var lastThumbLX = 0f; var lastThumbRX = 0f; var lastThumbRY = 0f
    var gamepadFrames = 0; private set

    var mouseDx = 0L; var mouseDy = 0L
    var mouseMoves = 0; private set

    // Last absolute-mouse target (screen fraction 0..1) + count, for AbsoluteMouse pad tests.
    var lastAbsX = -1f; var lastAbsY = -1f
    var mouseAbsMoves = 0; private set

    val mouseButtons = ArrayList<MouseBtn>()
    val keys = ArrayList<KeyEv>()

    override fun gamepad(state: GamepadState) {
        gamepadFrames++
        gamepadButtonsSeen = gamepadButtonsSeen or (state.buttons.toInt() and 0xFFFF)
        for (i in 0..3) if (state.dpad[i]) dpadSeen[i] = true
        maxThumbLX = maxOf(maxThumbLX, state.thumbLX); minThumbLX = minOf(minThumbLX, state.thumbLX)
        maxThumbRX = maxOf(maxThumbRX, state.thumbRX); minThumbRX = minOf(minThumbRX, state.thumbRX)
        lastThumbLX = state.thumbLX; lastThumbRX = state.thumbRX; lastThumbRY = state.thumbRY
        maxTriggerL = maxOf(maxTriggerL, state.triggerL); maxTriggerR = maxOf(maxTriggerR, state.triggerR)
    }

    override fun mouseMove(dx: Int, dy: Int) {
        mouseMoves++; mouseDx += dx; mouseDy += dy
    }

    override fun mouseMoveAbs(nx: Float, ny: Float) {
        mouseAbsMoves++; lastAbsX = nx; lastAbsY = ny
    }

    override fun mouseButton(button: Pointer.Button, pressed: Boolean) {
        mouseButtons.add(MouseBtn(button, pressed))
    }

    override fun key(key: XKeycode, pressed: Boolean) {
        keys.add(KeyEv(key, pressed))
    }

    fun keyPresses(key: XKeycode) = keys.count { it.key == key && it.pressed }
    fun mouseButtonPresses(button: Pointer.Button) = mouseButtons.count { it.button == button && it.pressed }
}

object TraceReader {
    /** Load a length-prefixed trace (1-byte length + report bytes per frame) into decoded states. */
    fun loadStates(resourcePath: String): List<TritonState> {
        val raw = readResource(resourcePath)
        val out = ArrayList<TritonState>()
        var i = 0
        while (i < raw.size) {
            val n = raw[i].toInt() and 0xFF; i++
            if (i + n > raw.size) break
            val frame = raw.copyOfRange(i, i + n); i += n
            TritonProtocol.decodeBleState(frame, frame.size)?.let { out.add(it) }
        }
        return out
    }

    private fun readResource(path: String): ByteArray =
        (javaClass.classLoader ?: ClassLoader.getSystemClassLoader())
            .getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("test resource not found: $path")
}
