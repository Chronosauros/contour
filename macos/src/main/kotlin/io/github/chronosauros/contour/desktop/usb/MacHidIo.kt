package io.github.chronosauros.contour.desktop.usb

import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.core.native.NativeHidReports
import org.hid4java.HidDevice
import java.io.Closeable
import java.io.IOException

interface DesktopHidIo : Closeable {
    val isNative: Boolean
    fun nativePayloadSize(id: Int, kind: NativeHidReports.Kind): Int
    fun checkSession()
    fun send(report: ByteArray)
    fun receive(timeoutMs: Int): ByteArray?
    fun requestOnce(report: ByteArray, matches: (ByteArray) -> Boolean): ByteArray
    fun sendNative(id: Int, kind: NativeHidReports.Kind, payload: ByteArray)
    fun requestNative(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray
}

/** Low-level macOS USB HID transport over hid4java with report descriptor inspection. */
class MacHidIo private constructor(
    private val device: HidDevice,
    private val nativeReports: NativeHidReports.Shape? = null,
    private val nativeReportId: Int? = null,
    private val sessionGuard: () -> Unit = {}
) : DesktopHidIo {

    companion object {
        const val REPLY_TIMEOUT_MS = 200
        private const val WRITE_TIMEOUT_MS = 1000

        fun open(handle: MacDacHandle, guard: () -> Unit = {}): MacHidIo {
            guard()
            val device = handle.device
            if (device.isClosed) {
                if (!device.open()) {
                    throw IOException("Failed to open HID device: ${device.lastErrorMessage}")
                }
            }

            val target = handle.target
            val reportId = target.fiio?.reportId ?: WalkPlay.REPORT_ID

            // descriptor-proven targets: FiiO and Contour 1.6 families (Dawn Pro 2, Allegro Mini, Echo-A)
            if (target.fiio != null || target.walkplay == null) {
                return try {
                    val descBuf = ByteArray(4096)
                    val n = device.getReportDescriptor(descBuf)
                    val shape = if (n > 0) {
                        val descriptor = descBuf.copyOf(n)
                        NativeHidReports.parse(descriptor, undefinedDesktop = true).also {
                            it.qualify(target, reportId)
                        }
                    } else null
                    MacHidIo(device, shape, reportId, guard).also { it.drain() }
                } catch (e: Exception) {
                    // fallback to WalkPlay framing if descriptor parse is not strictly required
                    if (target.walkplay != null) {
                        MacHidIo(device, null, null, guard).also { it.drain() }
                    } else {
                        device.close()
                        throw e
                    }
                }
            }

            return MacHidIo(device, null, null, guard).also { it.drain() }
        }
    }

    override val isNative: Boolean get() = nativeReports != null

    override fun nativePayloadSize(id: Int, kind: NativeHidReports.Kind): Int =
        requireNotNull(nativeReports).payloadSize(id, kind)

    override fun checkSession() = sessionGuard()

    override fun send(report: ByteArray) {
        checkSession()
        require(report.isNotEmpty())
        val reportId = report[0]
        val payload = report.copyOfRange(1, report.size)
        val written = device.write(payload, payload.size, reportId)
        if (written < 0) {
            throw IOException("Output report failed: ${device.lastErrorMessage}")
        }
    }

    override fun receive(timeoutMs: Int): ByteArray? {
        val inSize = nativeReports?.let { s ->
            nativeReportId?.let { id -> s.rawSizes[NativeHidReports.Key(id, NativeHidReports.Kind.INPUT)] }
        } ?: WalkPlay.REPORT_SIZE
        val buf = ByteArray(maxOf(64, inSize))
        val n = device.read(buf, timeoutMs.coerceAtLeast(1))
        if (n <= 0) return null
        return buf.copyOf(n)
    }

    fun drain() {
        var dropped = 0
        while (dropped < 16 && receive(5) != null) dropped++
        if (nativeReports != null && dropped >= 16) {
            throw IOException("Native input queue did not drain; reconnect and read again")
        }
    }

    override fun requestOnce(report: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        drain()
        send(report)
        val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
        while (true) {
            checkSession()
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val r = receive(left.toInt()) ?: break
            if (matches(r)) {
                checkSession()
                return r
            }
        }
        throw IOException("Native request timed out or returned invalid state")
    }

    override fun sendNative(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
        checkSession()
        require(kind == NativeHidReports.Kind.OUTPUT && id == nativeReportId)
        val wire = requireNotNull(nativeReports).wire(id, kind, payload)
        val reportId = wire[0]
        val p = wire.copyOfRange(1, wire.size)
        val n = device.write(p, p.size, reportId)
        checkSession()
        if (n < 0) throw IOException("Short native output report: ${device.lastErrorMessage}")
    }

    override fun requestNative(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        drain()
        sendNative(id, NativeHidReports.Kind.OUTPUT, payload)
        val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
        while (true) {
            checkSession()
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val raw = receive(left.toInt()) ?: break
            if ((raw[0].toInt() and 255) != id) continue
            val p = requireNotNull(nativeReports).payload(id, NativeHidReports.Kind.INPUT, raw)
            if (matches(p)) {
                checkSession()
                return p
            }
        }
        throw IOException("Native request timed out or returned invalid state")
    }

    override fun close() {
        if (!device.isClosed) {
            device.close()
        }
    }
}
