package io.github.chronosauros.contour.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.native.NativeHidReports
import java.io.Closeable
import java.io.IOException

/**
 * The DAC's HID interface, claimed for one operation. Only the HID interface (class 3; interface 3 on the
 * Protocol Micro) is ever claimed - the audio interfaces stay with the kernel. Output reports go through the
 * interrupt OUT endpoint when there is one, else HID SET_REPORT; input reports come from the interrupt IN endpoint.
 * The FiiO KA15 (native FiiO protocol) is opened only after its HID report descriptor proves the report sizes.
 * Blocking: call from the single USB thread only.
 */
class HidTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint?,
    private val nativeReports: NativeHidReports.Shape? = null,
    private val nativeReportId: Int? = null,
    private val sessionGuard: () -> Unit = {},
) : Closeable {

    companion object {
        const val REPLY_TIMEOUT_MS = 200
        const val RETRIES = 2
        private const val WRITE_TIMEOUT_MS = 1000

        /** First configuration only: usbfs returns every configuration, and the FiiO K13 R2R declares two identical ones. */
        private fun descriptorLength(raw: ByteArray, intf: UsbInterface): Int {
            var i = 0; var matching = false; var configs = 0
            val lengths = ArrayList<Int>()
            while (i < raw.size) {
                require(i + 2 <= raw.size)
                val n = raw[i].toInt() and 255
                require(n >= 2 && i + n <= raw.size) { "Malformed USB descriptors" }
                val type = raw[i + 1].toInt() and 255
                if (type == 2 && ++configs > 1) break
                when (type) {
                    4 -> { require(n >= 9); matching = (raw[i + 2].toInt() and 255) == intf.id &&
                        (raw[i + 3].toInt() and 255) == intf.alternateSetting }
                    0x21 -> if (matching) {
                        require(n >= 6)
                        val count = raw[i + 5].toInt() and 255
                        require(6 + count * 3 <= n)
                        repeat(count) { k ->
                            val o = i + 6 + k * 3
                            if (raw[o].toInt() and 255 == 0x22) lengths +=
                                (raw[o + 1].toInt() and 255) or ((raw[o + 2].toInt() and 255) shl 8)
                        }
                    }
                }
                i += n
            }
            return lengths.singleOrNull()?.takeIf { it in 1..8192 }
                ?: throw IOException("Missing/ambiguous HID report descriptor length")
        }

        /** UsbDevice.getInterface lists the interfaces of every configuration; on the FiiO K13 R2R (two identical
         * configurations) HID interface 3 would appear twice. Single-configuration DACs get the same list as before. */
        private fun firstConfigInterfaces(device: UsbDevice): List<UsbInterface> =
            device.takeIf { it.configurationCount > 0 }?.getConfiguration(0)?.let { c -> (0 until c.interfaceCount).map { c.getInterface(it) } }
                ?: (0 until device.interfaceCount).map { device.getInterface(it) }

        fun open(manager: UsbManager, device: UsbDevice, guard: () -> Unit = {}): HidTransport {
            val hids = firstConfigInterfaces(device).filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            val target = DeviceTarget.find(device.vendorId, device.productId)
                ?: throw IOException("Unsupported DAC")
            target.fiio?.let { return openNative(manager, device, hids, it.reportId, guard) }
            val p = requireNotNull(target.walkplay)
            val intf = if (p.experimental) {
                val suitable = hids.filter { h -> h.alternateSetting == 0 &&
                    (0 until h.endpointCount).any { i -> h.getEndpoint(i).let { ep ->
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN && ep.maxPacketSize >= WalkPlay.REPORT_SIZE
                    } }
                }
                suitable.singleOrNull() ?: throw IOException("Protocol Max requires one unambiguous 64-byte HID interrupt IN interface")
            } else hids.firstOrNull { it.id == 3 } ?: hids.firstOrNull()
                ?: throw IOException("no HID interface on ${device.deviceName}")
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                if (p.experimental && ep.maxPacketSize < WalkPlay.REPORT_SIZE) continue
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

        /** FiiO KA15: the one HID interface whose report descriptor declares vendor Input and Output reports
         * [reportId] in the same collection; nothing is written before this read-only proof. */
        private fun openNative(manager: UsbManager, device: UsbDevice, hids: List<UsbInterface>, reportId: Int,
                               guard: () -> Unit): HidTransport {
            guard()
            data class Target(val intf: UsbInterface, val input: UsbEndpoint, val output: UsbEndpoint?, val shape: NativeHidReports.Shape)
            val connection = manager.openDevice(device) ?: throw IOException("openDevice failed (permission?)")
            try {
                val targets = hids.filter { it.alternateSetting == 0 }.mapNotNull { h ->
                    runCatching {
                        guard()
                        // usbfs refuses interface-recipient requests while the kernel HID driver owns it.
                        if (!connection.claimInterface(h, true)) throw IOException("claimInterface failed before descriptor read")
                        val length = descriptorLength(connection.rawDescriptors, h)
                        val descriptor = ByteArray(length)
                        val n = connection.controlTransfer(0x81, 0x06, 0x2200, h.id, descriptor, length, WRITE_TIMEOUT_MS)
                        guard()
                        require(n == length) { "Short HID descriptor read ($n of $length)" }
                        UsbLog.line("HID if ${h.id} report descriptor ${UsbLog.hex(descriptor)}")
                        val eps = (0 until h.endpointCount).map { h.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
                        val shape = NativeHidReports.parse(descriptor, undefinedDesktop = true)
                        val outSize = shape.rawSize(reportId, NativeHidReports.Kind.OUTPUT)
                        require(outSize >= 17)
                        val inSize = shape.rawSize(reportId, NativeHidReports.Kind.INPUT)
                        require(inSize >= 17)
                        shape.requireSameOwner(reportId, NativeHidReports.Kind.INPUT, NativeHidReports.Kind.OUTPUT)
                        val input = eps.filter { it.direction == UsbConstants.USB_DIR_IN && it.maxPacketSize >= inSize }.singleOrNull()
                            ?: error("No matching native interrupt IN")
                        // Send like the FiiO web app (WebHID writes to the interrupt OUT endpoint when there is one).
                        val out = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.maxPacketSize >= outSize }
                        UsbLog.line("HID if ${h.id} native output via ${if (out != null) "interrupt OUT 0x${Integer.toHexString(out.address)}" else "SET_REPORT control"}")
                        Target(h, input, out, shape)
                    }.onFailure { connection.releaseInterface(h); UsbLog.line("HID if ${h.id} rejected: ${it.message ?: it.javaClass.simpleName}") }.getOrNull()
                }
                val target = targets.singleOrNull() ?: throw IOException("No unambiguous descriptor-proven FiiO HID interface")
                guard()
                if (!connection.claimInterface(target.intf, true)) throw IOException("claimInterface failed")
                return HidTransport(connection, target.intf, target.input, target.output, target.shape, reportId, guard).also { it.drain() }
            } catch (e: Exception) {
                connection.close(); throw e
            }
        }
    }

    private val inBuf = ByteArray(maxOf(epIn.maxPacketSize, WalkPlay.REPORT_SIZE,
        nativeReports?.let { s -> nativeReportId?.let { id -> s.rawSizes[NativeHidReports.Key(id, NativeHidReports.Kind.INPUT)] } } ?: 0))

    fun checkSession() = sessionGuard()

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
        val r = inBuf.copyOf(n)
        // Native: a report with our ID must have exactly the descriptor's Input size.
        if (nativeReports != null && (r[0].toInt() and 255) == nativeReportId) nativeReports.payload(requireNotNull(nativeReportId), NativeHidReports.Kind.INPUT, r)
        return r.also { UsbLog.rx(it) }
    }

    /** Drops input that queued up before this operation (e.g. volume-key reports 0x03). */
    private fun drain() {
        var dropped = 0
        while (dropped < 16 && receive(5) != null) dropped++
        if (nativeReports != null && dropped >= 16) throw IOException("Native input queue did not drain; reconnect and read again")
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

    /** Native (KA15): one logical payload, padded to the descriptor's report size behind the report ID. */
    fun sendNative(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
        checkSession()
        require(kind == NativeHidReports.Kind.OUTPUT && id == nativeReportId)
        val wire = requireNotNull(nativeReports).wire(id, kind, payload)
        UsbLog.tx(wire)
        val n = if (epOut != null) connection.bulkTransfer(epOut, wire, wire.size, WRITE_TIMEOUT_MS)
            else connection.controlTransfer(0x21, 0x09, (kind.controlType shl 8) or id, intf.id, wire, wire.size, WRITE_TIMEOUT_MS)
        checkSession()
        require(n == wire.size) { "Short native output report: $n/${wire.size}" }
    }

    /** Native request: one outstanding query; stale queued input is dropped first. The reply is the payload
     * after the report ID. A timeout throws "Native request timed out" (NativeSession retries only reads). */
    fun requestNative(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        drain()
        sendNative(id, NativeHidReports.Kind.OUTPUT, payload)
        val deadline = SystemClock.elapsedRealtime() + REPLY_TIMEOUT_MS
        while (true) {
            checkSession()
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            val raw = receive(left.toInt()) ?: break
            if ((raw[0].toInt() and 255) != id) continue
            val p = requireNotNull(nativeReports).payload(id, NativeHidReports.Kind.INPUT, raw)
            if (matches(p)) { checkSession(); return p }
        }
        throw IOException("Native request timed out or returned invalid state")
    }

    override fun close() {
        runCatching { connection.releaseInterface(intf) }
        connection.close()
        UsbLog.line("close: interface ${intf.id} released")
    }
}
