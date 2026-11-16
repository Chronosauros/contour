package io.github.chronosauros.contour.desktop.usb

import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort

/** Bridges DesktopHidIo with :core NativePort for WalkPlay wire framing. */
class MacWalkPlayPort(
    private val t: DesktopHidIo,
    device: MacDacHandle,
    private val tick: () -> Unit = {}
) : NativePort {
    override val deviceKey = device.key

    var firstSendMs = 0L
        private set
    var lastSendEndMs = 0L
        private set

    override fun guard() = t.checkSession()

    override fun payloadSize(id: Int, kind: NativeHidReports.Kind): Int = t.nativePayloadSize(id, kind)

    override fun pause(ms: Long) {
        t.checkSession()
        Thread.sleep(ms)
        t.checkSession()
        lastSendEndMs = System.currentTimeMillis()
    }

    override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) {
        require(id == WalkPlay.REPORT_ID && kind == NativeHidReports.Kind.OUTPUT) { "Only WalkPlay output report 0x4B" }
        if (firstSendMs == 0L) firstSendMs = System.currentTimeMillis()
        if (t.isNative) {
            t.sendNative(id, kind, payload)
        } else {
            t.send(byteArrayOf(id.toByte()) + payload)
        }
        lastSendEndMs = System.currentTimeMillis()
        tick()
    }

    override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        require(id == WalkPlay.REPORT_ID) { "Only WalkPlay report 0x4B" }
        val reply = if (t.isNative) {
            t.requestNative(id, payload, matches)
        } else {
            t.requestOnce(byteArrayOf(id.toByte()) + payload) {
                it.size > 1 && it[0] == id.toByte() && matches(it.copyOfRange(1, it.size))
            }.let { it.copyOfRange(1, it.size) }
        }
        tick()
        return reply
    }

    override fun log(message: String) {
        println("[USB] $message")
    }
}
