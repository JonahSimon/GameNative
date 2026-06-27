package app.gamenative.steamcontroller

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * No-root USB-Host transport for the Steam Controller Puck. Opens the device, claims the vendor
 * controller interface(s) (the Proteus dongle exposes up to 4 slots, interfaces 2-5; each has an
 * interrupt-IN report endpoint + interrupt-OUT haptic endpoint), reads raw 64-byte reports, and writes
 * feature reports (SET_REPORT control transfer, for lizard-off / IMU) and output reports (bulk transfer
 * on the OUT endpoint, for haptics). Transfer conventions mirror SDL's HIDDeviceUSB (the Android HID
 * reference); the code is original.
 */
class TritonUsb(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
) {
    companion object {
        private const val TAG = "TritonUsb"
        private const val SET_REPORT_REQUEST_TYPE =
            UsbConstants.USB_TYPE_CLASS or 0x01 /*recipient interface*/ or UsbConstants.USB_DIR_OUT
        private const val HID_SET_REPORT = 0x09
        private const val HID_FEATURE = 3
    }

    private class Slot(val iface: UsbInterface, val inEp: UsbEndpoint, val outEp: UsbEndpoint?)

    private var connection: UsbDeviceConnection? = null
    private val slots = ArrayList<Slot>()
    @Volatile private var active: Slot? = null     // the live controller slot, once detected

    fun open(): Boolean {
        val conn = usbManager.openDevice(device) ?: run {
            Log.w(TAG, "openDevice failed"); return false
        }
        connection = conn
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
            }
            // controller vendor interface = has an interrupt-IN report endpoint
            if (inEp != null && conn.claimInterface(iface, true)) {
                slots.add(Slot(iface, inEp, outEp))
            }
        }
        if (slots.isEmpty()) {
            Log.w(TAG, "no claimable controller interface found"); close(); return false
        }
        Log.i(TAG, "claimed ${slots.size} controller interface(s)")
        return true
    }

    /** Send lizard-off + IMU-enable to every slot so the live one starts streaming full reports. */
    fun initAllSlots() {
        for (s in slots) {
            writeFeatureTo(s, TritonProtocol.lizardOffReport())
            writeFeatureTo(s, TritonProtocol.imuEnableReport())
        }
    }

    /** Resend lizard-off to the active slot (firmware watchdog re-enables it within ~3 s). */
    fun heartbeat() {
        val s = active ?: return
        writeFeatureTo(s, TritonProtocol.lizardOffReport())
    }

    /**
     * Read one report into [buf]. Before a slot is known to be live, polls all slots and latches the
     * first that produces data. Returns the number of bytes read, or <= 0 on timeout.
     */
    fun read(buf: ByteArray, timeoutMs: Int): Int {
        val conn = connection ?: return -1
        active?.let { return conn.bulkTransfer(it.inEp, buf, buf.size, timeoutMs) }
        // No slot latched yet: poll all and latch only the one streaming real controller-state reports.
        // Latching on any byte (n > 0) can lock onto a lizard-mode mouse/keyboard interface, which then
        // masks the absence of decoded input. Gate on the report type instead (confound-free).
        for (s in slots) {
            val n = conn.bulkTransfer(s.inEp, buf, buf.size, timeoutMs / slots.size + 1)
            if (n > 0 && TritonProtocol.isStateReport(buf[0].toInt() and 0xFF)) {
                active = s
                Log.i(TAG, "live slot iface=${s.iface.id} (report 0x${(buf[0].toInt() and 0xFF).toString(16)})")
                return n
            }
        }
        return 0
    }

    /**
     * Write a haptic output report to the active slot's interrupt-OUT endpoint. Returns the number of
     * bytes transferred, or < 0 if there's no active slot / OUT endpoint or the transfer failed.
     */
    fun writeOutputReport(report: ByteArray): Int {
        val conn = connection ?: return -1
        val s = active ?: return -1
        val out = s.outEp ?: return -1
        return conn.bulkTransfer(out, report, report.size, 1000)
    }

    private fun writeFeatureTo(s: Slot, report: ByteArray) {
        val conn = connection ?: return
        val reportNumber = report[0].toInt() and 0xFF
        val offset = if (reportNumber == 0) 1 else 0
        val length = report.size - offset
        conn.controlTransfer(
            SET_REPORT_REQUEST_TYPE, HID_SET_REPORT,
            (HID_FEATURE shl 8) or reportNumber, s.iface.id,
            report, offset, length, 1000,
        )
    }

    fun close() {
        val conn = connection
        if (conn != null) {
            for (s in slots) runCatching { conn.releaseInterface(s.iface) }
            conn.close()
        }
        slots.clear(); active = null; connection = null
    }
}
