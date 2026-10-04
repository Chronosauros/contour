package io.github.chronosauros.contour.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.core.DeviceProtocol
import io.github.chronosauros.contour.core.WalkPlayCatalog
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.BuildConfig
import java.io.Closeable
import java.io.IOException

/** Claims only HID; audio remains with the kernel. New targets require a read-only descriptor proof. */
class HidTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint?,
    private val epOut: UsbEndpoint?,
    private val reports: WalkPlayCatalog.Reports?,
    private val nativeReports: NativeHidReports.Shape?,
    private val nativeReportId: Int?,
    private val sessionGuard: () -> Unit,
) : Closeable {
    companion object {
        const val REPLY_TIMEOUT_MS = 200
        const val RETRIES = 2
        private const val WRITE_TIMEOUT_MS = 1000

        private fun descriptorLength(raw: ByteArray, intf: UsbInterface): Int {
            var i = 0; var matching = false
            val lengths = ArrayList<Int>()
            while (i < raw.size) {
                require(i + 2 <= raw.size)
                val n = raw[i].toInt() and 255
                require(n >= 2 && i + n <= raw.size) { "Malformed USB descriptors" }
                when (raw[i + 1].toInt() and 255) {
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

        fun open(manager: UsbManager, device: UsbDevice, guard: () -> Unit = {}): HidTransport {
            guard()
            val p = DeviceTarget.find(device.vendorId, device.productId, BuildConfig.ADVANCED, device.productName)
                ?: throw IOException("Unsupported DAC or missing exact USB product name")
            val hids = (0 until device.interfaceCount).map { device.getInterface(it) }
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            val connection = manager.openDevice(device) ?: throw IOException("openDevice failed (permission?)")
            try {
                data class Target(val intf: UsbInterface, val input: UsbEndpoint?, val output: UsbEndpoint?, val reports: WalkPlayCatalog.Reports?, val native: NativeHidReports.Shape? = null)
                val target = if (!p.descriptorRequired) {
                    // Captured Micro path deliberately unchanged.
                    val h = hids.firstOrNull { it.id == 3 } ?: hids.firstOrNull() ?: error("No HID interface")
                    val eps = (0 until h.endpointCount).map { h.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
                    Target(h, eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: error("No interrupt IN"),
                        eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }, null)
                } else if (p.walkplay == DeviceProtocol.MAX) {
                    // Protocol Max: exactly the stable 1.3.0 selection (the hardware-tested path), no descriptor read.
                    val h = hids.filter { h -> h.alternateSetting == 0 &&
                        (0 until h.endpointCount).any { i -> h.getEndpoint(i).let { ep ->
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN && ep.maxPacketSize >= WalkPlay.REPORT_SIZE
                        } }
                    }.singleOrNull() ?: throw IOException("Protocol Max requires one unambiguous 64-byte HID interrupt IN interface")
                    var epIn: UsbEndpoint? = null
                    var epOut: UsbEndpoint? = null
                    for (i in 0 until h.endpointCount) {
                        val ep = h.getEndpoint(i)
                        if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                        if (ep.maxPacketSize < WalkPlay.REPORT_SIZE) continue
                        if (ep.direction == UsbConstants.USB_DIR_IN) epIn = epIn ?: ep else epOut = epOut ?: ep
                    }
                    if (epIn == null) throw IOException("HID interface ${h.id} has no interrupt IN endpoint")
                    Target(h, epIn, epOut, null)
                } else {
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
                            val epsNative = (0 until h.endpointCount).map { h.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
                            if (p.native) {
                                val shape = NativeHidReports.parse(descriptor)
                                val id = p.fiio?.reportId ?: if (p.fosi) 1 else 0x4B
                                val outSize = shape.rawSize(id, NativeHidReports.Kind.OUTPUT)
                                p.moondrop?.let { shape.qualifyMoondrop(it) }
                                val minimum = if (p.fiio != null) 17 else if (p.moondrop != null) 7 else 11
                                require(outSize >= minimum)
                                val input = if (p.fosi) {
                                    require(outSize == 64 && shape.rawSize(id, NativeHidReports.Kind.FEATURE) == 64)
                                    shape.requireSameOwner(id, NativeHidReports.Kind.OUTPUT, NativeHidReports.Kind.FEATURE)
                                    null
                                } else {
                                    val inSize = shape.rawSize(id, NativeHidReports.Kind.INPUT)
                                    require(inSize >= if (p.moondrop != null) 37 else minimum)
                                    shape.requireSameOwner(id, NativeHidReports.Kind.INPUT, NativeHidReports.Kind.OUTPUT)
                                    epsNative.filter { it.direction == UsbConstants.USB_DIR_IN && it.maxPacketSize >= inSize }.singleOrNull()
                                        ?: error("No matching native interrupt IN")
                                }
                                // FiiO: send like the web tool (WebHID writes to the interrupt OUT endpoint when there is one).
                                // The KA15 silence first blamed on SET_REPORT was the USB audio stream going idle (03.10.2026).
                                // Native Output SET_REPORT remains distinct from Feature SET_REPORT for the other families.
                                val out = if (p.fiio != null) epsNative.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.maxPacketSize >= outSize } else null
                                UsbLog.line("HID if ${h.id} native output via ${if (out != null) "interrupt OUT 0x${Integer.toHexString(out.address)}" else "SET_REPORT control"}")
                                return@runCatching Target(h, input, out, null, shape)
                            }
                            val shape = WalkPlayCatalog.reports(descriptor)
                            val eps = (0 until h.endpointCount).map { h.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
                            val input = eps.filter { it.direction == UsbConstants.USB_DIR_IN && it.maxPacketSize >= shape.inputBytes }.singleOrNull()
                                ?: error("No unambiguous matching interrupt IN")
                            val outs = eps.filter { it.direction == UsbConstants.USB_DIR_OUT }
                            val output = if (outs.isEmpty()) null else outs.singleOrNull()?.takeIf { it.maxPacketSize >= shape.outputBytes }
                                ?: error("Ambiguous/undersized interrupt OUT")
                            Target(h, input, output, shape)
                        }.onFailure { connection.releaseInterface(h); UsbLog.line("HID if ${h.id} rejected: ${it.message ?: it.javaClass.simpleName}") }.getOrNull()
                    }
                    targets.singleOrNull() ?: throw IOException("No unambiguous descriptor-proven family HID interface")
                }
                guard()
                if (!connection.claimInterface(target.intf, true)) throw IOException("claimInterface failed")
                return HidTransport(connection, target.intf, target.input, target.output, target.reports, target.native, if (p.native) p.fiio?.reportId ?: if (p.fosi) 1 else 0x4B else null, guard).also { if (it.epIn != null) it.drain() }
            } catch (e: Exception) {
                connection.close(); throw e
            }
        }
    }

    private val inBuf = ByteArray(maxOf(epIn?.maxPacketSize ?: 64, reports?.inputBytes ?: nativeReports?.let { s -> nativeReportId?.let { id -> s.rawSizes[NativeHidReports.Key(id, NativeHidReports.Kind.INPUT)] } } ?: WalkPlay.REPORT_SIZE))
    fun nativePayloadSize(id: Int, kind: NativeHidReports.Kind): Int = requireNotNull(nativeReports).payloadSize(id, kind)
    fun checkSession() = sessionGuard()

    fun send(report: ByteArray) {
        checkSession()
        val wire = if (reports == null) report else {
            require(report.isNotEmpty() && (report[0].toInt() and 255) == 0x4B)
            // WalkPlay constructors preserve Micro's 64B bytes. Strip only PROVEN zero padding if shorter.
            require(report.size <= reports.outputBytes || report.drop(reports.outputBytes).all { it == 0.toByte() })
            report.copyOf(reports.outputBytes)
        }
        UsbLog.tx(wire)
        val n = if (epOut != null) connection.bulkTransfer(epOut, wire, wire.size, WRITE_TIMEOUT_MS)
            else connection.controlTransfer(0x21, 0x09, 0x024B, intf.id, wire, wire.size, WRITE_TIMEOUT_MS)
        checkSession()
        if (n != wire.size) throw IOException("output report failed ($n of ${wire.size})")
    }

    fun receive(timeoutMs: Int): ByteArray? {
        checkSession()
        val n = connection.bulkTransfer(epIn ?: throw IOException("No interrupt IN"), inBuf, inBuf.size, timeoutMs.coerceAtLeast(1))
        checkSession()
        if (n <= 0) return null
        val r = inBuf.copyOf(n)
        if (nativeReports != null && (r[0].toInt() and 255) == nativeReportId) nativeReports.payload(requireNotNull(nativeReportId), NativeHidReports.Kind.INPUT, r)
        if (reports != null && (r[0].toInt() and 255) == 0x4B && n != reports.inputBytes)
            throw IOException("Malformed HID report 4B length $n; expected ${reports.inputBytes}")
        return r.also { UsbLog.rx(it) }
    }

    private fun drain() {
        var dropped = 0
        while (dropped < 16 && receive(5) != null) dropped++
        require(nativeReports == null || dropped < 16) { "Native input queue did not drain; explicit reconnect/read required" }
    }

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

    fun sendNative(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
        checkSession()
        require(kind != NativeHidReports.Kind.INPUT && id == nativeReportId)
        val wire = requireNotNull(nativeReports).wire(id, kind, payload)
        UsbLog.tx(wire)
        val n = if (epOut != null) connection.bulkTransfer(epOut, wire, wire.size, WRITE_TIMEOUT_MS)
            else connection.controlTransfer(0x21, 0x09, (kind.controlType shl 8) or id, intf.id, wire, wire.size, WRITE_TIMEOUT_MS)
        checkSession()
        require(n == wire.size) { "Short native output report: $n/${wire.size}" }
    }
    fun requestNative(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        // One outstanding request; discard stale queued input before issuing a new query.
        // No retry of a mutation (KT slot acknowledgement uses this same boundary).
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
    fun featureNative(id: Int): ByteArray {
        checkSession()
        require(id == nativeReportId)
        val raw = ByteArray(requireNotNull(nativeReports).rawSize(id, NativeHidReports.Kind.FEATURE))
        raw[0] = id.toByte()
        val n = connection.controlTransfer(0xA1, 0x01, 0x0300 or id, intf.id, raw, raw.size, WRITE_TIMEOUT_MS)
        checkSession()
        require(n == raw.size) { "Short native GET_FEATURE: $n/${raw.size}" }
        nativeReports.payload(id, NativeHidReports.Kind.FEATURE, raw)
        UsbLog.rx(raw)
        return raw
    }

    override fun close() {
        runCatching { connection.releaseInterface(intf) }; connection.close()
    }
}
