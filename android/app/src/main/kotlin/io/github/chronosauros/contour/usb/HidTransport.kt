package io.github.chronosauros.contour.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import io.github.chronosauros.contour.core.WalkPlay
import java.io.Closeable
import java.io.IOException

/**
 * The DAC's HID interface, claimed for one operation. Only the HID interface (class 3; interface 3 on the
 * Protocol Micro) is ever claimed - the audio interfaces stay with the kernel. Output reports go through the
 * interrupt OUT endpoint when there is one, else HID SET_REPORT; input reports come from the interrupt IN endpoint.
 * Blocking: call from the single USB thread only.
 */
class HidTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint?,
) : Closeable {

    companion object {
        const val REPLY_TIMEOUT_MS = 200
        const val RETRIES = 2
        private const val WRITE_TIMEOUT_MS = 1000

        fun open(manager: UsbManager, device: UsbDevice): HidTransport {
            val hids = (0 until device.interfaceCount).map { device.getInterface(it) }
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            val intf = hids.firstOrNull { it.id == 3 } ?: hids.firstOrNull()
                ?: throw IOException("no HID interface on ${device.deviceName}")
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = epIn ?: ep else epOut = epOut ?: ep
            }
            if (epIn == null) throw IOException("HID interface ${intf.id} has no interrupt IN endpoint")
            val connection = manager.openDevice(device) ?: throw IOException("openDevice failed (permission?)")
            if (!connection.claimInterface(intf, true)) {
                connection.close()
                throw IOException("claimInterface(${intf.id}) failed")
            }
            UsbLog.line(
                "open: HID interface ${intf.id}, IN 0x%02x%s".format(
                    epIn.address,
                    if (epOut != null) ", OUT 0x%02x".format(epOut.address) else ", no OUT (SET_REPORT)",
                ),
            )
            return HidTransport(connection, intf, epIn, epOut).also { it.drain() }
        }
    }

    private val inBuf = ByteArray(maxOf(epIn.maxPacketSize, WalkPlay.REPORT_SIZE))

    fun send(report: ByteArray) {
        UsbLog.tx(report)
        val n = if (epOut != null) {
            connection.bulkTransfer(epOut, report, report.size, WRITE_TIMEOUT_MS)
        } else {
            // SET_REPORT: bmRequestType 0x21, bRequest 0x09, wValue (Output = 2) << 8 | report ID, wIndex = interface
            connection.controlTransfer(0x21, 0x09, (0x02 shl 8) or WalkPlay.REPORT_ID, intf.id, report, report.size, WRITE_TIMEOUT_MS)
        }
        if (n != report.size) throw IOException("output report failed ($n of ${report.size} bytes)")
    }

    /** One input report, or null after [timeoutMs]. */
    fun receive(timeoutMs: Int): ByteArray? {
        val n = connection.bulkTransfer(epIn, inBuf, inBuf.size, timeoutMs.coerceAtLeast(1))
        if (n <= 0) return null
        return inBuf.copyOf(n).also { UsbLog.rx(it) }
    }

    /** Drops input that queued up before this operation (e.g. volume-key reports 0x03). */
    private fun drain() {
        var dropped = 0
        while (dropped < 16 && receive(5) != null) dropped++
    }

    /** Lock-step request/reply: send, wait up to 200 ms for a matching reply, at most 2 retries. */
    fun request(report: ByteArray, what: String, matches: (ByteArray) -> Boolean): ByteArray {
        for (attempt in 0..RETRIES) {
            send(report)
            val deadline = SystemClock.elapsedRealtime() + REPLY_TIMEOUT_MS
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                val r = receive(left.toInt()) ?: break
                if (matches(r)) return r
            }
            UsbLog.line("no reply to $what (attempt ${attempt + 1}/${RETRIES + 1})")
        }
        throw IOException("the DAC did not answer $what")
    }

    override fun close() {
        runCatching { connection.releaseInterface(intf) }
        connection.close()
        UsbLog.line("close: interface ${intf.id} released")
    }
}
