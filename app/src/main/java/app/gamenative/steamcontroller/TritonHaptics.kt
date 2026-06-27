package app.gamenative.steamcontroller

import kotlin.math.abs

/**
 * Regenerates the trackpad "feel" Steam Input gives — required because disabling lizard mode (to read
 * raw reports) turns off the firmware's automatic click-feel. Recipe + tuning from docs/HAPTICS-RESEARCH.md
 * (captured from Steam Input via USBPcap and feel-confirmed on hardware):
 *   - per-pad CLICK  = output report 0x82 <side> 02 <gain>   (command 2 = CLICK)
 *   - slide DETENT   = output report 0x82 <side> 01 <gain>   (command 1 = TICK)
 *   - side ids: 0 = LEFT pad, 1 = RIGHT pad (2 is skipped; 3/4 = rumble motors)
 *   - the 0x81 pulse is NON-directional (fires both pads) -> not used for per-pad feedback
 * Haptic reports go out the interrupt-OUT endpoint (see TritonUsb.writeOutputReport).
 */
class TritonHaptics(private val writeOut: (ByteArray) -> Unit) {
    companion object {
        const val ID_OUT_HAPTIC_COMMAND = 0x82
        const val SIDE_LEFT_PAD = 0
        const val SIDE_RIGHT_PAD = 1
        const val CMD_TICK = 1
        const val CMD_CLICK = 2
    }

    private fun command(side: Int, cmd: Int, gain: Int): ByteArray =
        byteArrayOf(ID_OUT_HAPTIC_COMMAND.toByte(), side.toByte(), cmd.toByte(), gain.toByte())

    fun click(side: Int, gain: Int) = writeOut(command(side, CMD_CLICK, gain))
    fun tick(side: Int, gain: Int) = writeOut(command(side, CMD_TICK, gain))

    // per-pad slide accumulator: [touched, lastX, lastY, accum]
    private val pads = arrayOf(PadState(), PadState())
    private class PadState {
        var touched = false; var lastX = 0; var lastY = 0; var accum = 0
    }

    /**
     * Feed each decoded report here. Fires a click on a fresh pad-click and detent ticks as the thumb
     * slides (jitter-filtered). [prevButtons] is the previous report's button mask for edge detection;
     * [cfg] supplies the profile-driven feel parameters (gains, detent step, jitter floor, enables).
     */
    fun update(s: TritonState, prevButtons: Int, cfg: HapticSettings) {
        if (!cfg.enabled) return
        if (cfg.leftPadEnabled) {
            handlePad(SIDE_LEFT_PAD, pads[0], s, prevButtons,
                TritonProtocol.BTN_LPAD_TOUCH, TritonProtocol.BTN_LPAD_CLICK, s.leftPadX, s.leftPadY, cfg)
        }
        if (cfg.rightPadEnabled) {
            handlePad(SIDE_RIGHT_PAD, pads[1], s, prevButtons,
                TritonProtocol.BTN_RPAD_TOUCH, TritonProtocol.BTN_RPAD_CLICK, s.rightPadX, s.rightPadY, cfg)
        }
    }

    private fun handlePad(
        side: Int, st: PadState, s: TritonState, prev: Int,
        touchBit: Int, clickBit: Int, x: Int, y: Int, cfg: HapticSettings
    ) {
        val rising = s.buttons and prev.inv()
        if (rising and clickBit != 0) click(side, cfg.clickGain)  // press-down click
        if (s.has(touchBit)) {
            if (!st.touched) {
                st.touched = true; st.lastX = x; st.lastY = y; st.accum = 0
            } else {
                val d = abs(x - st.lastX) + abs(y - st.lastY)
                st.lastX = x; st.lastY = y                       // track every report (reject slow drift)
                if (d >= cfg.moveNoise) {                        // ignore stationary jitter
                    st.accum += d
                    if (st.accum >= cfg.detentStep) { tick(side, cfg.tickGain); st.accum -= cfg.detentStep }
                }
            }
        } else {
            st.touched = false
        }
    }
}
