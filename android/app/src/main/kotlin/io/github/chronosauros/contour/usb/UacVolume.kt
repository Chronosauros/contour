package io.github.chronosauros.contour.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * The DAC's hardware volume: the USB Audio Class Feature Unit on the playback path, the same control that
 * bit-perfect players (HiBy, UAPP) set in exclusive mode. Android never touches it, so a low value set there
 * caps every other app afterwards. Not part of the WalkPlay HID protocol.
 *
 * On the Protocol Micro (UAC2, Realtek extension unit): playback input terminal 0x0E (USB streaming, 2 ch)
 * -> Feature Unit 0x10 (volume on channels 1 and 2, no master) -> mixer 0x24 (with the mic sidetone) -> speaker
 * output terminal 0x0F. The units are found from the raw configuration descriptor, so other UAC1/UAC2 dongles work too.
 *
 * Values are signed 1/256 dB (both UAC1 and UAC2); 0x8000 is silence.
 */
object UacVolume {

    /** The Feature Unit that controls playback volume, and how to address it. */
    data class Target(
        val uac2: Boolean,
        val controlInterface: Int,
        val unitId: Int,
        /** 0 = master; otherwise the logical channels (1 = left, 2 = right) that have a volume control. */
        val channels: List<Int>,
        val writable: Boolean,
    ) {
        override fun toString() =
            "UAC${if (uac2) 2 else 1} FU 0x%02x on interface %d, channels %s%s".format(unitId, controlInterface, channels, if (writable) "" else ", read-only")
    }

    data class Range(val min256: Int, val max256: Int, val res256: Int)

    /** How the last control request reached the DAC. */
    enum class Path {
        /** Control transfer while the kernel's audio driver keeps the interface - music keeps playing. */
        SHARED,
        /** Interface 0 claimed from the kernel for the request and released - audio drops out for a moment. */
        CLAIMED,
    }

    data class State(val target: Target, val range: Range?, val current256: List<Int>, val path: Path)

    // ---- descriptor -----------------------------------------------------------------------------------

    private class Unit(val id: Int, val subtype: Int, val sources: List<Int>, val terminalType: Int = 0, val volumeChannels: List<Int> = emptyList(), val volumeWritable: Boolean = false)

    /** Finds the Feature Unit with a volume control whose sources lead back to a USB-streaming input (playback). */
    fun find(raw: ByteArray): Target? {
        var acInterface = -1
        var uac2 = false
        var inAc = false
        val units = HashMap<Int, Unit>()
        var i = 0
        while (i + 1 < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len < 2 || i + len > raw.size) break
            val d = raw.copyOfRange(i, i + len)
            fun u8(k: Int) = d[k].toInt() and 0xFF
            fun u16(k: Int) = u8(k) or (u8(k + 1) shl 8)
            when (u8(1)) {
                0x04 -> if (len >= 9) { // interface
                    inAc = u8(5) == 0x01 && u8(6) == 0x01
                    if (inAc && acInterface < 0) { acInterface = u8(2); uac2 = u8(7) == 0x20 }
                }
                0x24 -> if (inAc && len >= 4) { // class-specific AudioControl
                    val id = u8(3)
                    when (u8(2)) {
                        0x02 -> if (len >= 6) units[id] = Unit(id, 0x02, emptyList(), terminalType = u16(4)) // input terminal
                        0x03 -> if (len >= 8) units[id] = Unit(id, 0x03, listOf(u8(7)), terminalType = u16(4)) // output terminal
                        0x04, 0x05 -> if (len >= 5) { // mixer / selector: bNrInPins, baSourceID...
                            val n = u8(4)
                            units[id] = Unit(id, u8(2), (0 until n).filter { 5 + it < len }.map { u8(5 + it) })
                        }
                        0x06 -> if (len >= 7) units[id] = featureUnit(d, uac2)
                        else -> {
                            // UAC2: 07 effect (source at 6), 08 processing / 09 extension (bNrInPins at 6);
                            // UAC1: 07 processing / 08 extension (bNrInPins at 6).
                            val st = u8(2)
                            if (uac2 && st == 0x07 && len >= 7) units[id] = Unit(id, st, listOf(u8(6)))
                            else if (st in 0x07..0x09 && len >= 7) {
                                val n = u8(6)
                                units[id] = Unit(id, st, (0 until n).filter { 7 + it < len }.map { u8(7 + it) })
                            }
                        }
                    }
                }
            }
            i += len
        }
        if (acInterface < 0) return null

        fun fromPlayback(id: Int, seen: MutableSet<Int> = HashSet()): Boolean {
            if (!seen.add(id)) return false
            val u = units[id] ?: return false
            if (u.subtype == 0x02) return u.terminalType == 0x0101 // USB streaming input = what the phone plays
            return u.sources.any { fromPlayback(it, seen) }
        }
        val fu = units.values.filter { it.subtype == 0x06 && it.volumeChannels.isNotEmpty() && fromPlayback(it.id) }
            .maxByOrNull { it.volumeChannels.size } ?: return null
        return Target(uac2, acInterface, fu.id, fu.volumeChannels, fu.volumeWritable)
    }

    private fun featureUnit(d: ByteArray, uac2: Boolean): Unit {
        fun u8(k: Int) = d[k].toInt() and 0xFF
        val id = u8(3)
        val source = u8(4)
        val channels = ArrayList<Int>()
        var writable = true
        if (uac2) {
            // bmaControls: 4 bytes per channel from offset 5 (0 = master); volume = bits 2-3 (01 read-only, 11 read/write)
            val count = (d.size - 6) / 4
            for (ch in 0 until count) {
                val c = (u8(5 + ch * 4) shr 2) and 0x3
                if (c != 0) { channels += ch; if (c != 0x3) writable = false }
            }
        } else {
            // bControlSize at 5, bmaControls from 6; volume = bit 1
            val size = u8(5).coerceAtLeast(1)
            val count = (d.size - 7) / size
            for (ch in 0 until count) if ((u8(6 + ch * size) shr 1) and 1 == 1) channels += ch
        }
        // A master control covers every channel: use it alone.
        val use = if (0 in channels) listOf(0) else channels
        return Unit(id, 0x06, listOf(source), volumeChannels = use, volumeWritable = writable)
    }

    // ---- requests -------------------------------------------------------------------------------------

    private const val TIMEOUT_MS = 500
    private const val VOLUME_CONTROL = 0x02

    private fun wValue(ch: Int) = (VOLUME_CONTROL shl 8) or ch
    private fun wIndex(t: Target) = (t.unitId shl 8) or t.controlInterface

    private fun s16(b: ByteArray, k: Int) = ((b[k].toInt() and 0xFF) or (b[k + 1].toInt() shl 8)).toShort().toInt()

    private fun get(c: UsbDeviceConnection, t: Target, request: Int, ch: Int, len: Int): ByteArray {
        val buf = ByteArray(len)
        val n = c.controlTransfer(0xA1, request, wValue(ch), wIndex(t), buf, len, TIMEOUT_MS)
        if (n < 2) throw IOException("GET 0x%02x ch %d failed (%d)".format(request, ch, n))
        UsbLog.line("UAC RX req 0x%02x ch %d: %s".format(request, ch, UsbLog.hex(buf, n)))
        return buf
    }

    private fun readRange(c: UsbDeviceConnection, t: Target): Range? = runCatching {
        val ch = t.channels.first()
        if (t.uac2) {
            // RANGE: wNumSubRanges, then MIN/MAX/RES per subrange; the first subrange is enough for volume
            val b = get(c, t, 0x02, ch, 8)
            Range(s16(b, 2), s16(b, 4), s16(b, 6))
        } else {
            Range(s16(get(c, t, 0x82, ch, 2), 0), s16(get(c, t, 0x83, ch, 2), 0), s16(get(c, t, 0x84, ch, 2), 0))
        }
    }.onFailure { UsbLog.line("UAC range: ${it.message}") }.getOrNull()

    private fun readCurrent(c: UsbDeviceConnection, t: Target): List<Int> =
        t.channels.map { ch -> s16(get(c, t, if (t.uac2) 0x01 else 0x81, ch, 2), 0) }

    private fun writeCurrent(c: UsbDeviceConnection, t: Target, value256: Int) {
        val v = value256.coerceIn(Short.MIN_VALUE + 1, Short.MAX_VALUE.toInt())
        val buf = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        for (ch in t.channels) {
            val n = c.controlTransfer(0x21, 0x01, wValue(ch), wIndex(t), buf, 2, TIMEOUT_MS)
            UsbLog.line("UAC TX SET_CUR ch %d = %.2f dB (%s): %d".format(ch, v / 256.0, UsbLog.hex(buf), n))
            if (n != 2) throw IOException("SET_CUR ch $ch failed ($n)")
        }
    }

    /**
     * Runs [block] on the control interface: first as a plain control transfer next to the kernel's audio
     * driver; if the kernel refuses, with interface 0 claimed for the request (audio drops out, Android then
     * re-attaches its driver on release). Blocking: call on the single USB thread.
     */
    /** The device whose kernel refused the shared path once: later requests claim straight away (single USB thread only). */
    private var sharedRefused: String? = null

    private fun <T> withControl(manager: UsbManager, device: UsbDevice, block: (UsbDeviceConnection, Target) -> T): Pair<T, Path> {
        val c = manager.openDevice(device) ?: throw IOException("openDevice failed (permission?)")
        try {
            val raw = c.rawDescriptors ?: throw IOException("no raw descriptors")
            val t = find(raw) ?: throw IOException("no playback volume control in the USB descriptors")
            if (sharedRefused != device.deviceName) {
                UsbLog.line("UAC: $t")
                runCatching { return block(c, t) to Path.SHARED }
                    .onFailure {
                        UsbLog.line("UAC shared path refused: ${it.message} - claiming interface ${t.controlInterface}")
                        sharedRefused = device.deviceName
                    }
            }
            val intf = (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { it.id == t.controlInterface && it.alternateSetting == 0 }
                ?: throw IOException("interface ${t.controlInterface} not found")
            if (!c.claimInterface(intf, true)) throw IOException("claimInterface(${t.controlInterface}) failed")
            try {
                return block(c, t) to Path.CLAIMED
            } finally {
                val released = c.releaseInterface(intf)
                UsbLog.line("UAC: interface ${t.controlInterface} released ($released)")
            }
        } finally {
            c.close()
        }
    }

    fun read(manager: UsbManager, device: UsbDevice): State {
        val (r, path) = withControl(manager, device) { c, t -> Triple(t, readRange(c, t), readCurrent(c, t)) }
        val (t, range, cur) = r
        UsbLog.line("UAC volume ${cur.joinToString { "%.2f".format(it / 256.0) }} dB, range $range, path $path")
        return State(t, range, cur, path)
    }

    /** Sets every volume channel to [value256] (clamped to the reported range), then reads it back. */
    fun write(manager: UsbManager, device: UsbDevice, value256: Int): State {
        val (r, path) = withControl(manager, device) { c, t ->
            if (!t.writable) throw IOException("volume is read-only on this DAC")
            val range = readRange(c, t)
            val v = if (range != null) value256.coerceIn(range.min256, range.max256) else value256
            writeCurrent(c, t, v)
            Triple(t, range, readCurrent(c, t))
        }
        val (t, range, cur) = r
        UsbLog.line("UAC volume set, read back ${cur.joinToString { "%.2f".format(it / 256.0) }} dB, path $path")
        return State(t, range, cur, path)
    }
}
