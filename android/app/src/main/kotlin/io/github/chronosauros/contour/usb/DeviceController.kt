package io.github.chronosauros.contour.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.core.native.FiioCodec
import io.github.chronosauros.contour.core.native.FiioConfig
import io.github.chronosauros.contour.core.native.FiioMelodyExchange
import io.github.chronosauros.contour.core.native.FiioRegisters
import io.github.chronosauros.contour.core.native.FiioSnapshot
import io.github.chronosauros.contour.core.native.NativeState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Link { NO_DAC, NEEDS_PERMISSION, CONNECTED }

/** Outcome of one send from the app (HOLD TO SEND). */
data class SendOutcome(val verified: Boolean, val reason: String?)

/**
 * The DAC as the UI sees it: link state, the last read (for matching), busy flag. Reads on connect and on
 * resume (read-only); writes only through [send] / the service screen's buttons, i.e. only on an
 * explicit user action. All I/O runs on DacClient's single USB thread.
 */
class DeviceController(private val context: Context, private val scope: CoroutineScope) {
    private val manager = context.getSystemService(UsbManager::class.java)
    val client = DacClient(manager, { d -> sessionGuard(d) }, { d -> UsbAudioKeepAlive.start(context, d) })

    /** The DAC found on the bus (MICRO when none). MAX for the Protocol Max, KA15 for the FiiO KA15. */
    var protocol by mutableStateOf(DeviceTarget.MICRO)
        private set
    /** The last DAC model seen; a different one resets every device-specific state (see [refresh]). */
    private var lastProtocol: DeviceTarget? = null

    var link by mutableStateOf(Link.NO_DAC)
        private set
    var busy by mutableStateOf(false)
        private set
    /** True until the first read of a connection has opened the DAC's content in the editor ([onConnectRead]); a failed read keeps it. */
    private var openPending = true
    /** Set by the editor: told once per connection, right after the first read ([DacEditor.connectRead]). */
    var onConnectRead: ((DacSnapshot) -> Unit)? = null

    // ---- DAC transfers the user can see: the button says SAVING TO DAC (not for the background scan alone) -----------------
    // A transfer is a send (its frames are counted by [Sender]), the follower ([switchToSlot]), the connect read or a rename. The
    // button fills on a time ramp to about 85 % over the expected time (the last measured duration of that kind on this DAC in
    // this session, else a default), to 100 % when the transfer ends, and keeps the state for [SETTLE_MS] after it.

    private enum class Kind(val defaultMs: Long) { SWITCH(800), SLOT_READ(1700), CONNECT_READ(1200), RENAME(800) }
    private val measured = HashMap<Kind, Long>()
    private fun expected(k: Kind) = measured[k] ?: k.defaultMs
    private var transfers = 0
    private var settleJob: Job? = null
    /** A transfer is running. */
    var transferActive by mutableStateOf(false)
        private set
    /** A transfer is running or ended less than [SETTLE_MS] ago: HOLD TO SEND says SAVING TO DAC. */
    var transferring by mutableStateOf(false)
        private set
    /** When the time ramp of the running transfers reaches its mark ([SystemClock.elapsedRealtime]); chained transfers extend it. */
    var transferEndMs by mutableStateOf(0L)
        private set
    /** The first read of a connection runs (about 1 s, once per plug-in): a slot tap waits for it, so the slot row ignores taps. */
    var connecting by mutableStateOf(false)
        private set
    /** A slot rename runs: slot taps and a second rename are refused. */
    var renaming by mutableStateOf(false)
        private set

    private fun beginTransfer(k: Kind) {
        settleJob?.cancel()
        transferEndMs = if (transfers == 0) SystemClock.elapsedRealtime() + expected(k) else transferEndMs + expected(k)
        transfers++
        transferActive = true
        transferring = true
    }
    private fun extendTransfer(k: Kind) { transferEndMs += expected(k) }
    private fun endTransfer(k: Kind, ms: Long?) {
        ms?.let { measured[k] = it }
        if (transfers > 0) transfers--
        if (transfers > 0) return
        transferActive = false
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(SETTLE_MS)
            if (transfers == 0) transferring = false
        }
    }
    /** The DAC went away or changed: nothing of the old session carries over. */
    private fun dropDacWork() {
        endScan()
        followJob?.cancel()
        following = false
        pendingTaps.clear()
        dacSlot = null
        settleJob?.cancel()
        transfers = 0
        transferActive = false
        transferring = false
        connecting = false
        renaming = false
        measured.clear()
    }
    /** A supported DAC is plugged in and one tap connects it: NO DAC is shown only when nothing connectable is attached (owner, 14.10.2026). */
    val tapToConnect: Boolean get() = link == Link.NEEDS_PERMISSION
    var snapshot by mutableStateOf<DacSnapshot?>(null)
        private set
    var lastWrite by mutableStateOf<WriteResult?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** snapshot remains the A reference during B; it must not be replaced by a bypass read. */
    var abBypassed by mutableStateOf(false)
        private set
    private var abReference: DacSnapshot? = null
    @Volatile private var connectionEpoch = 0L

    /** The USER slot the user tapped on a DAC with a slot picker (it is read onto the graph and HOLD TO SEND writes it); null = none tapped yet. */
    var selectedSlot by mutableStateOf<Int?>(null)
        private set
    /** The DAC stores USER slot names (FiiO command 0x30: KA15, SnowSky Melody and Tiny A): the picker shows and renames them. Without it the picker has generic names, sends no name command and Contour remembers the names itself ([slotRecords]). */
    val slotNames: Boolean get() = protocol.fiio?.userSlotNames == true
    /** Named slots (KA15) or a config that opts in (K13 R2R): the user chooses where HOLD TO SEND writes. */
    val slotPicker: Boolean get() = protocol.fiio?.let { it.userSlotNames || it.userSlotPicker } == true
    /** Read, write and persistence of this DAC were confirmed on real hardware (FiioConfig.hardwareVerified). */
    val hardwareVerified: Boolean get() = protocol.fiio?.hardwareVerified == true
    /** The USER slot the slot row shows as chosen: the one tapped, else the one playing, else none (a SnowSky right after plug-in, a stock preset). */
    val chosenSlot: Int? get() = if (!slotPicker) null else selectedSlot ?: playingSlot
    fun destinationSlot(): Int? = if (!slotPicker) protocol.destinationSlot else chosenSlot ?: protocol.destinationSlot
    /** The chosen slot is the one playing: only then does the read state say what that slot holds (ON DAC). */
    val destinationActive: Boolean get() = !slotPicker || (chosenSlot != null && snapshot?.slot == chosenSlot)

    /**
     * What each USER slot held when Contour last read or wrote it, kept on the phone per DAC model: its registers and,
     * where the DAC stores no names (K13 R2R: FiiO's own app has none there, 14.10.2026), the name of the profile the last
     * verified HOLD TO SEND put there. A later read of that slot with other registers replaces the record and drops the
     * name (the FiiO app or the DAC's buttons changed the slot). Only the slot playing can be read without switching, so
     * the other slots are as last seen.
     */
    data class SlotRecord(val name: String?, val raw: FiioSnapshot)
    var slotRecords by mutableStateOf<Map<Int, SlotRecord>>(emptyMap())
        private set
    private val recordPrefs = context.getSharedPreferences("slot_records", Context.MODE_PRIVATE)
    private fun recordKey(slot: Int) = "${protocol.caps.name}:$slot"
    private fun encode(r: FiioSnapshot) = "${r.preampTenths}|" + r.bands.sortedBy { it.index }
        .joinToString(";") { "${it.frequencyHz},${it.gainTenths},${it.qHundredths},${it.typeCode}" }
    private fun decode(f: FiioConfig, slot: Int, text: String): FiioSnapshot? = runCatching {
        val (preamp, bands) = text.split('|', limit = 2)
        val regs = bands.split(';').mapIndexed { i, b -> b.split(',').map(String::toInt).let { FiioRegisters(i, it[0], it[1], it[2], it[3]) } }
        FiioSnapshot(f.productName, slot, regs.size, preamp.toInt(), regs)
    }.getOrNull()
    private fun loadRecords() {
        val f = protocol.fiio
        slotRecords = if (f == null || !slotPicker) emptyMap() else f.userSlots.mapNotNull { slot ->
            recordPrefs.getString(recordKey(slot), null)?.let { v ->
                decode(f, slot, v.substringAfter('\n'))?.let { slot to SlotRecord(v.substringBefore('\n').ifEmpty { null }, it) }
            }
        }.toMap()
    }
    /** The read state just taken (a read, a slot tap or a send's read-back) is what its USER slot holds; [sentName] after a verified send. */
    private fun recordSlot(sentName: String? = null, from: DacSnapshot? = snapshot) {
        val state = from?.nativeState as? NativeState.Fiio ?: return
        val f = protocol.fiio ?: return
        if (!slotPicker || state.slot !in f.userSlots) return
        val old = slotRecords[state.slot]
        val same = old != null && runCatching { state.codec.matches(old.raw, state.raw) }.getOrDefault(false)
        val name = if (slotNames) null else sentName ?: old?.name?.takeIf { same }
        recordPrefs.edit().putString(recordKey(state.slot), "${name.orEmpty()}\n${encode(state.raw)}").apply()
        loadRecords()
    }
    /** The record of [slot] is no longer trusted (a switch to it was not confirmed): the next tap reads the slot. */
    private fun dropRecord(slot: Int) {
        recordPrefs.edit().remove(recordKey(slot)).apply()
        loadRecords()
    }
    /** The read state with USER [slot] as the slot playing and its record as the registers (the DAC was switched to it, nothing read). */
    private fun stateFromRecord(slot: Int): DacSnapshot? {
        val s = snapshot ?: return null
        val state = s.nativeState as? NativeState.Fiio ?: return null
        val r = slotRecords[slot] ?: return null
        return s.copy(slot = slot, nativeState = state.copy(raw = r.raw.copy(activeSlot = slot)))
    }
    /** The slot's name: the DAC's own (KA15, SnowSky) or the profile Contour last sent there (K13 R2R). */
    fun slotName(slot: Int): String? = if (slotNames) (snapshot?.nativeState as? NativeState.Fiio)?.names?.get(slot)?.takeIf { it.isNotBlank() }
        else slotRecords[slot]?.name
    /** The slot playing now, when the DAC says so (a SnowSky right after plug-in does not). */
    val playingSlot: Int? get() = snapshot?.slot?.takeIf { s -> protocol.fiio?.userSlots?.contains(s) == true }
    /** Does USER [slot] hold [profile]? Its last read or written registers decide; without a record, its name. */
    fun holds(slot: Int, profile: Profile): Boolean {
        val f = protocol.fiio ?: return false
        if (!slotPicker || slot !in f.userSlots) return false
        val c = FiioCodec(f)
        slotRecords[slot]?.let { r ->
            return runCatching {
                c.matches(c.planOnExplicitSend(profile.copy(bands = c.padToDeviceCount(profile.bands), preampDb = profile.effectivePreampDb()), slot, true).expected, r.raw)
            }.getOrDefault(false)
        }
        val name = slotName(slot) ?: return false
        return if (slotNames) name == FiioCodec.slotName(profile.name, f.nameLength) else name == profile.name
    }

    /**
     * Per DAC model and slot: the library profile last opened from, or verified-sent to, that slot, with the slot's registers
     * ([SlotContent.fingerprint]) at that moment. The link is used only while the slot still reads the same (the FiiO app or the
     * DAC's buttons may have changed it) and the profile still exists. Without a slot picker the slot is the DAC's own.
     */
    private fun linkKey(slot: Int) = "link:${protocol.caps.name}:$slot"
    fun linkOf(slot: Int): Pair<String, String>? = recordPrefs.getString(linkKey(slot), null)
        ?.split('\n', limit = 2)?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    fun setLink(slot: Int, profileId: String, fingerprint: String) { recordPrefs.edit().putString(linkKey(slot), "$profileId\n$fingerprint").apply() }
    fun dropLink(slot: Int) { recordPrefs.edit().remove(linkKey(slot)).apply() }

    /**
     * What a DAC slot holds, as the editor opens it ([DacEditor]): [slot] (null for a stock preset or a SnowSky slot not known yet),
     * its registers as a [fingerprint], the slot's [name] and [label], [holds] = the profile compiles to exactly these registers,
     * [sameName] = the profile is named like the slot, [unknownUser] = a SnowSky USER slot whose number the read did not give.
     */
    class SlotContent(val slot: Int?, val fingerprint: String, val name: String?, val label: String?,
        val holds: (Profile) -> Boolean, val sameName: (Profile) -> Boolean, val unknownUser: Boolean = false)

    private fun fingerprint(s: DacSnapshot): String = (s.nativeState as? NativeState.Fiio)?.let { encode(it.raw) }
        ?: (s.bands.joinToString(";") { it.registers.toString() } + "|" + s.preampDb)
    /** The registers [raw] equal what [profile] compiles to; the slot number is left out (a stock preset or an unknown SnowSky slot has none to compile for). */
    private fun rawHolds(c: FiioCodec, raw: FiioSnapshot, profile: Profile): Boolean = runCatching {
        val ref = c.config.userSlots.min()
        c.matches(c.planOnExplicitSend(profile.copy(bands = c.padToDeviceCount(profile.bands), preampDb = profile.effectivePreampDb()), ref, true).expected, raw.copy(activeSlot = ref))
    }.getOrDefault(false)
    private fun sameName(f: FiioConfig, name: String?): (Profile) -> Boolean = { p ->
        name != null && (if (f.userSlotNames) FiioCodec.slotName(p.name, f.nameLength) == name else p.name == name)
    }
    private fun nameOf(slot: Int, names: Map<Int, String>): String? =
        if (slotNames) names[slot]?.takeIf { it.isNotBlank() } else slotRecords[slot]?.name

    /** The content of the slot [s] was read from (the DAC's own slot where there are no slots). */
    fun contentOf(s: DacSnapshot): SlotContent? {
        val state = s.nativeState as? NativeState.Fiio
            ?: return SlotContent(s.slot, fingerprint(s), null, null, { p -> s.matches(p) }, { false })
        val f = protocol.fiio ?: return null
        val slot = state.slot.takeIf { it in f.userSlots }
        val name = slot?.let { nameOf(it, state.names) }
        return SlotContent(slot, fingerprint(s), name, slot?.let { f.slotLabels[it] }, { p -> rawHolds(state.codec, state.raw, p) }, sameName(f, name),
            unknownUser = slot == null && FiioMelodyExchange.appliesTo(f) && state.slot in 7..9)
    }
    /** The content of USER [slot] as Contour last read or wrote it (null without a record). */
    fun contentOfRecord(slot: Int): SlotContent? {
        val f = protocol.fiio ?: return null
        val r = slotRecords[slot] ?: return null
        val c = FiioCodec(f)
        val name = slotName(slot)
        return SlotContent(slot, encode(r.raw), name, f.slotLabels[slot], { p -> rawHolds(c, r.raw, p) }, sameName(f, name))
    }
    /**
     * A SnowSky read right after plug-in does not say which USER slot plays (it reports 7..9). When exactly one slot's record holds
     * the registers just read, that slot plays: the read state takes its number. Returns it, or null.
     */
    private fun identifyPlayingSlot(): Int? {
        val s = snapshot ?: return null
        val state = s.nativeState as? NativeState.Fiio ?: return null
        val f = protocol.fiio ?: return null
        if (!FiioMelodyExchange.appliesTo(f) || state.slot !in 7..9) return null
        val ref = f.userSlots.min()
        val slot = slotRecords.filter { (_, r) ->
            runCatching { state.codec.matches(r.raw.copy(activeSlot = ref), state.raw.copy(activeSlot = ref)) }.getOrDefault(false)
        }.keys.singleOrNull() ?: return null
        snapshot = s.copy(slot = slot, nativeState = state.copy(raw = state.raw.copy(activeSlot = slot)))
        return slot
    }

    /** Long press on a slot (KA15, SnowSky): writes only the name, then shows the name the DAC reads back. */
    fun renameSlot(slot: Int, name: String) {
        if (!slotNames || slot !in protocol.fiio!!.userSlots) return
        launchOp("rename", keepSnapshot = true) { d ->
            val t0 = SystemClock.elapsedRealtime()
            renaming = true
            beginTransfer(Kind.RENAME)
            try {
                val read = client.renameSlot(d, slot, name)
                val s = snapshot; val state = s?.nativeState as? NativeState.Fiio
                if (s != null && state != null) snapshot = s.copy(nativeState = state.copy(names = state.names + (slot to read)))
                if (read != name) throw IOException("name reads back as $read")
            } finally {
                renaming = false
                endTransfer(Kind.RENAME, SystemClock.elapsedRealtime() - t0)
            }
        }
    }

    /** KA15 operations: throws once the attachment the operation started on is gone (detach, other DAC). */
    private fun sessionGuard(d: UsbDevice): () -> Unit {
        val epoch = connectionEpoch
        val id = d.deviceId
        return {
            if (epoch != connectionEpoch || manager.deviceList.values.none { it.deviceId == id } || !manager.hasPermission(d))
                throw IOException("DAC SESSION CHANGED; reconnect and retry")
        }
    }

    /** Why [profile] cannot be sent to the DAC now; empty = HOLD TO SEND may write. */
    fun sendIssues(profile: Profile): List<String> {
        val issues = protocol.issues(profile)
        if (issues.isNotEmpty() || !protocol.native) return issues
        val state = snapshot?.nativeState as? NativeState.Fiio ?: return listOf("Read the DAC first")
        return runCatching {
            state.codec.planOnExplicitSend(profile.copy(bands = state.codec.padToDeviceCount(profile.bands), preampDb = profile.effectivePreampDb()), destinationSlot()!!, true)
        }.exceptionOrNull()?.let { listOf(it.message ?: "Unrepresentable EQ") }.orEmpty()
    }

    private var loggedBlock: String? = null
    /** The full issue list that blocks HOLD TO SEND, once per distinct block, in the USB log (logcat tag [UsbLog.TAG]). An empty list re-arms it. */
    fun logSendBlock(issues: List<String>) {
        val text = issues.joinToString(" | ").takeIf { it.isNotEmpty() }
        if (text == loggedBlock) return
        loggedBlock = text
        if (text != null) UsbLog.line("SEND BLOCKED (${issues.size}): $text")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    UsbLog.line("permission ${if (granted) "granted" else "denied"}")
                    refresh(read = granted)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // The app may be open while the system dialog is dismissed or handled elsewhere: TAP TO CONNECT.
                    if (!isDac(intent)) return
                    UsbLog.line("attached (broadcast)")
                    refresh(read = true)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (!isDac(intent)) return
                    UsbLog.line("detached")
                    connectionEpoch++
                    abBypassed = false
                    abReference = null
                    snapshot = null
                    // A KA15 can report a detach right after the connect read while it stays plugged in, with no attach after it
                    // (Pixel, 14.10.2026): read again when the DAC is still there; a real unplug finds none and reads nothing.
                    refresh(read = true)
                }
            }
        }
    }

    private fun isDac(intent: Intent): Boolean {
        val d = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        return d != null && DeviceTarget.find(d.vendorId, d.productId) != null
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        client.shutdown()
    }

    private fun findDac(): UsbDevice? = manager.deviceList.values.firstOrNull {
        DeviceTarget.find(it.vendorId, it.productId) != null
    }

    /** Recomputes the link; when connected and [read], reads the DAC (read-only). */
    fun refresh(read: Boolean) {
        val d = findDac()
        val found = d?.let { DeviceTarget.find(it.vendorId, it.productId) }
        if (found != null && lastProtocol != null && found != lastProtocol) {
            // Another DAC model (Micro <-> Max <-> KA15): nothing read, written or switched on the old one carries over.
            UsbLog.line("DAC changed: ${lastProtocol?.caps?.name} -> ${found.caps.name}")
            connectionEpoch++
            snapshot = null
            lastWrite = null
            error = null
            abBypassed = false
            abReference = null
            selectedSlot = null
            openPending = true
            dropDacWork()
        }
        if (found != null) lastProtocol = found
        protocol = found ?: DeviceTarget.MICRO
        loadRecords()
        link = when {
            d == null -> Link.NO_DAC
            !manager.hasPermission(d) -> Link.NEEDS_PERMISSION
            else -> Link.CONNECTED
        }
        if (link != Link.CONNECTED) {
            connectionEpoch++
            snapshot = null
            abBypassed = false
            abReference = null
            selectedSlot = null
            openPending = true
            dropDacWork()
        }
        if (read && link == Link.CONNECTED && !abBypassed) read()
    }

    /**
     * Tap on NO DAC / the status pill: looks for the DAC again (it may have been plugged in without an attach
     * event reaching the app), then asks for USB permission or reads it. Returns false when there is none.
     */
    fun connect(): Boolean {
        refresh(read = true)
        if (link == Link.NEEDS_PERMISSION) requestPermission()
        return link != Link.NO_DAC
    }

    /** The system permission dialog (TAP TO CONNECT). */
    fun requestPermission() {
        val d = findDac() ?: return refresh(false)
        if (manager.hasPermission(d)) return refresh(true)
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        manager.requestPermission(d, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    /**
     * Reads what plays now (read-only). The first read of a connection also opens the DAC's content in the editor
     * ([onConnectRead]), identifies a SnowSky slot by its records, and starts the background [startScan].
     */
    fun read() {
        if (abBypassed) return
        val first = openPending
        launchOp("read") { d ->
            val t0 = SystemClock.elapsedRealtime()
            if (first) {
                connecting = true
                beginTransfer(Kind.CONNECT_READ)
            }
            try {
                snapshot = client.readDevice(d)
                recordSlot()
                if (first) {
                    openPending = false
                    // The slot playing becomes the chosen one; unknown or not a USER slot = none chosen until a tap.
                    if (identifyPlayingSlot() != null) recordSlot()
                    selectedSlot = playingSlot
                    snapshot?.let { s -> onConnectRead?.invoke(s) }
                    startScan(d)
                }
                dacSlot = playingSlot
            } finally {
                if (first) {
                    connecting = false
                    endTransfer(Kind.CONNECT_READ, SystemClock.elapsedRealtime() - t0)
                }
            }
        }
    }

    // ---- background scan on connect (SnowSky Melody, Tiny A, K13 R2R) ------------------------------------------------
    // Only the slot playing can be read, so after the connect read the DAC hops through the other USER slots (the same read a
    // tap on an unseen slot does), stores what each holds in [slotRecords] and ends on the slot it started on. Nothing shows in
    // the UI; a tap or HOLD TO SEND stops it after the slot in progress ([switchToSlot], [send]). The KA15 too: the 03.10 HID silence after a preset
    // switch did not come back in a dozen switches on the Pixel (14.10.2026).

    /** The background scan is running: the DAC may be on another slot, so HOLD TO SEND does not say SAFE TO UNPLUG yet. */
    var scanning by mutableStateOf(false)
        private set
    private var scanStop = false
    private var scanJob: Job? = null
    /** Counts scans, so one that was cancelled never resets the flags of the next. */
    private var scanId = 0
    /** The USER slots read in this scan (the slot playing at the start counts as read). */
    private val scanned = HashSet<Int>()
    /** Told with each slot the scan read (the editor runs its lookup on it). */
    var onScanRead: ((DacSnapshot) -> Unit)? = null

    private fun endScan() {
        scanId++
        scanJob?.cancel()
        scanning = false
    }

    private fun startScan(d: UsbDevice) {
        val f = protocol.fiio ?: return
        if (!slotPicker) return
        val state = snapshot?.nativeState as? NativeState.Fiio ?: return
        // A stock preset or Close EQ cannot be switched back to: no scan.
        if (state.slot !in f.userSlots && !(FiioMelodyExchange.appliesTo(f) && state.slot in 7..9)) return
        val epoch = connectionEpoch
        val id = ++scanId
        val connectRaw = state.raw
        val start = playingSlot // null = a SnowSky USER slot whose number the read did not give
        scanned.clear()
        start?.let { scanned += it }
        scanStop = false
        scanning = true
        scanJob = scope.launch {
            var lastRead: Int? = null
            try {
                UsbLog.line("scan: start")
                while (!scanStop && epoch == connectionEpoch) {
                    val next = f.userSlots.sorted().firstOrNull { it !in scanned } ?: break
                    val t0 = SystemClock.elapsedRealtime()
                    val s = client.readSlot(d, next)
                    if (epoch != connectionEpoch) break
                    lastRead = next
                    dacSlot = next
                    scanned += next
                    recordSlot(from = s)
                    UsbLog.line("scan: slot $next read ${SystemClock.elapsedRealtime() - t0} ms")
                    onScanRead?.invoke(s)
                }
                if (!scanStop && epoch == connectionEpoch) finishScan(d, f, connectRaw, start, lastRead)
            } catch (e: Exception) {
                UsbLog.line("scan: aborted: ${e.message}")
                // The DAC may be on another slot than it started on: one try to put it back.
                val back = start
                if (!scanStop && epoch == connectionEpoch && back != null && lastRead != null && lastRead != back) {
                    withContext(NonCancellable) { runCatching { client.selectSlot(d, back) } }
                }
                if (e is CancellationException) throw e
            } finally {
                if (id == scanId) scanning = false
            }
        }
    }

    /** The scan has read every slot: select the slot the DAC played at the start, and mark it as playing. */
    private suspend fun finishScan(d: UsbDevice, f: FiioConfig, connectRaw: FiioSnapshot, start: Int?, lastRead: Int?) {
        val slots = f.userSlots.sorted()
        val end = start ?: run {
            // SnowSky, number unknown: the scanned slot whose registers equal the connect read (the first one: they sound the same).
            val ref = slots.first()
            val c = FiioCodec(f)
            val match = slots.firstOrNull { slot ->
                slotRecords[slot]?.let { r -> runCatching { c.matches(r.raw.copy(activeSlot = ref), connectRaw.copy(activeSlot = ref)) }.getOrDefault(false) } == true
            }
            if (match == null) UsbLog.line("scan: playing slot not identified")
            match ?: ref
        }
        if (lastRead != null && lastRead != end) {
            val confirmed = client.selectSlot(d, end)
            if (confirmed != end) UsbLog.line("scan: switch back to $end answered $confirmed")
        }
        dacSlot = end
        if (scanStop) return
        UsbLog.line("scan: end on $end")
        if (snapshot?.slot != end) stateFromRecord(end)?.let { snapshot = it }
        selectedSlot = end
    }

    // ---- the follower: the DAC follows the slot the user tapped ---------------------------------------------------------
    // A tap never waits for the DAC: it sets [selectedSlot] and the editor opens the slot's record at once ([DacEditor.tapSlot]).
    // One follower per connection then brings the DAC to the last tapped slot, one switch (or one read) after another; taps made
    // during a switch coalesce, the follower reads [selectedSlot] again when it is done. It holds the DAC as [launchOp] does.

    /** The follower works (or waits for the DAC to be free): the button says SAVING TO DAC. */
    var following by mutableStateOf(false)
        private set
    private var followJob: Job? = null
    /** The USER slot the DAC is known to be on; null = not known. */
    private var dacSlot: Int? = null
    /** Taps the follower has not looked at yet, with the editor's hand-off for a read. */
    private val pendingTaps = ArrayList<Pair<Int, (DacSnapshot) -> Unit>>()
    private var extendedForNext = false

    /**
     * A tap on a USER slot of a slot picker DAC: [selectedSlot] changes now and the follower is told. A slot Contour has a record
     * of is only switched to (one query confirms; an unconfirmed switch drops the record and reads the slot once); a slot with no
     * record is read in full. [onRead] gets that read, if the slot is still the one tapped last when it returns. The background
     * scan stops after the slot in progress. HOLD TO SEND is blocked while the follower runs ([busy]).
     */
    fun switchToSlot(slot: Int, onRead: (DacSnapshot) -> Unit) {
        val f = protocol.fiio ?: return
        if (!slotPicker || slot !in f.userSlots || abBypassed) return
        selectedSlot = slot
        // The DAC already plays it and nothing runs: no DAC traffic, and no SAVING TO DAC flash.
        if (!following && !scanning && !busy && dacSlot == slot) {
            if (snapshot?.slot != slot) stateFromRecord(slot)?.let { snapshot = it }
            return
        }
        pendingTaps += slot to onRead
        if (scanning && !scanStop) {
            scanStop = true
            UsbLog.line("scan: stopped for a tap")
        }
        if (following) {
            // The running switch takes the new slot when it is done; the fill gets the time of one more switch (once).
            if (!extendedForNext) {
                extendedForNext = true
                extendTransfer(Kind.SWITCH)
            }
            return
        }
        following = true
        extendedForNext = false
        beginTransfer(if (slotRecords[slot] != null) Kind.SWITCH else Kind.SLOT_READ)
        followJob = scope.launch { follow() }
    }

    private suspend fun follow() {
        val epoch = connectionEpoch
        var held = false
        try {
            // Wait for whatever holds the DAC: the scan finishing its slot, a read or a rename.
            var waits = 0
            while ((busy || scanning) && waits++ < 400) { if (scanning) scanJob?.join() else delay(25) }
            if (epoch != connectionEpoch || busy) return
            val d = findDac() ?: run { link = Link.NO_DAC; snapshot = null; return }
            if (!manager.hasPermission(d)) { link = Link.NEEDS_PERMISSION; snapshot = null; return }
            busy = true
            held = true
            while (epoch == connectionEpoch) {
                val target = selectedSlot ?: break
                val taps = pendingTaps.toList()
                pendingTaps.clear()
                extendedForNext = false
                taps.dropLast(1).forEach { UsbLog.line("follow: skipped ${it.first}") }
                if (dacSlot == target) {
                    if (snapshot?.slot != target) stateFromRecord(target)?.let { snapshot = it }
                    break
                }
                UsbLog.line("follow: slot $target")
                val t0 = SystemClock.elapsedRealtime()
                val kind = serve(d, target, taps.lastOrNull { it.first == target }?.second, epoch)
                val ms = SystemClock.elapsedRealtime() - t0
                measured[kind] = ms
                UsbLog.line("follow: done $target in $ms ms")
            }
        } catch (e: Exception) {
            UsbLog.line("follow failed: ${e.message}")
            if (e is CancellationException) throw e
            // Not retried: the KA15 once went silent after preset switches. Stop here, as an operation that failed does.
            if (epoch == connectionEpoch) {
                snapshot = null
                dacSlot = null
                error = "switch failed: ${e.message}"
            }
        } finally {
            if (held) busy = false
            following = false
            pendingTaps.clear()
            endTransfer(Kind.SWITCH, null)
        }
    }

    /** Brings the DAC to USER [target]: a known slot is switched to (and read once if that is not confirmed), an unknown one is read. */
    private suspend fun serve(d: UsbDevice, target: Int, onRead: ((DacSnapshot) -> Unit)?, epoch: Long): Kind {
        val known = slotRecords[target] != null && snapshot?.nativeState is NativeState.Fiio
        if (known) {
            val confirmed = try { client.selectSlot(d, target) } catch (e: Exception) {
                if (epoch != connectionEpoch) throw e
                UsbLog.line("switch to slot $target not confirmed: ${e.message}")
                null
            }
            if (epoch != connectionEpoch) throw IOException("DAC SESSION CHANGED; reconnect and retry")
            if (confirmed == target) {
                stateFromRecord(target)?.let { snapshot = it; dacSlot = target; return Kind.SWITCH }
            }
            dropRecord(target)
            dacSlot = null
        }
        val s = client.readSlot(d, target)
        if (epoch != connectionEpoch) throw IOException("DAC SESSION CHANGED; reconnect and retry")
        snapshot = s
        recordSlot()
        dacSlot = target
        // The editor gets the read only while this is still the slot the user wants; otherwise it is only recorded.
        if (selectedSlot == target) onRead?.invoke(s)
        return Kind.SLOT_READ
    }

    /** Writes [profile] (plan, commit, read back, compare). The result's read-back becomes the snapshot. [onProgress] (0..1) is called on the USB thread. */
    suspend fun send(profile: Profile, onProgress: ((Float) -> Unit)? = null): SendOutcome {
        // The follower finishes its switch first; the background scan stops after the slot it is reading. The send selects its target itself.
        if (following) followJob?.join()
        if (scanning) {
            scanStop = true
            UsbLog.line("scan: stopped for a send")
            scanJob?.join()
        }
        // A refusal (KA15 limits, Micro/Max Q30 or AUTO) happens here, before anything is written or any state is dropped.
        sendIssues(profile).takeIf { it.isNotEmpty() }?.let { return SendOutcome(false, it.joinToString("; ")) }
        val slot = if (slotPicker) destinationSlot() else null
        return sendWith("send") { d -> client.writeProfile(d, profile, slot, onProgress) }
            .also {
                // The send selected its slot: verified = the DAC is on it; otherwise where it stopped is unknown (the next tap switches).
                if (slot != null) dacSlot = if (it.verified) slot else null
                if (it.verified) {
                    if (slot != null) recordSlot(profile.name)
                    // The slot now holds this profile: the next tap on it opens it again (with any later edits).
                    snapshot?.let { s -> contentOf(s) }?.let { c -> c.slot?.let { sent -> setLink(sent, profile.id, c.fingerprint) } }
                }
            }
    }

    /** Toggle against the actual A registers, not the editor's rounded or emulated band values. */
    suspend fun switchAb(bypass: Boolean): SendOutcome {
        if (!protocol.supportsAb) return SendOutcome(false, "A/B unavailable on ${protocol.caps.name}")
        if (bypass == abBypassed) return SendOutcome(true, null)
        val reference = (if (bypass) snapshot else abReference) ?: return forgetAb("DAC STATE UNKNOWN")
        val d = findDac() ?: run {
            abBypassed = false; abReference = null; snapshot = null; link = Link.NO_DAC
            return SendOutcome(false, "NO DAC")
        }
        if (!manager.hasPermission(d)) { link = Link.NEEDS_PERMISSION; return forgetAb("NO PERMISSION") }
        if (busy) return forgetAb("DAC BUSY")
        if (bypass) {
            // B is entered only when A can be put back: the restoring sequence is encoded (and so checked) first.
            val restore = reference.bands.map { it.registers }.filter { it.gain256 != 0 }.map { r ->
                WalkPlay.BandWrite(r.index, r.freq.toDouble(), r.gain256 / 256.0, r.q256 / 256.0, r.typeCode)
            }
            if (runCatching { WalkPlay.temporaryBandSequence(restore, reference.slot) }.isFailure) {
                return SendOutcome(false, "A/B blocked: a band on the DAC does not fit its filter format, so A could not be restored. Nothing was changed.")
            }
        }
        val epoch = connectionEpoch
        val bands = reference.bands.map { it.registers }.filter { it.gain256 != 0 }.map { r ->
            WalkPlay.BandWrite(r.index, r.freq.toDouble(), if (bypass) 0.0 else r.gain256 / 256.0, r.q256 / 256.0, r.typeCode)
        }
        busy = true
        return try {
            client.writeTemporaryBands(d, bands, reference.slot)
            if (epoch != connectionEpoch) return SendOutcome(false, "DAC DETACHED")
            abBypassed = bypass
            abReference = if (bypass) reference else null
            error = null
            UsbLog.line("A/B: ${if (bypass) "B - EQ OFF, PREAMP KEPT" else "A restored"}")
            SendOutcome(true, null)
        } catch (e: Exception) {
            UsbLog.line("A/B failed: ${e.message}")
            if (epoch != connectionEpoch) SendOutcome(false, "DAC DETACHED")
            else forgetAb(e.message ?: e.javaClass.simpleName)
        } finally {
            busy = false
        }
    }

    private fun forgetAb(reason: String): SendOutcome {
        abBypassed = false
        abReference = null
        snapshot = null // partial switch: UI returns to A, but the DAC state is unknown, not ON DAC
        error = reason
        return SendOutcome(false, reason)
    }

    private suspend fun sendWith(what: String, op: suspend (UsbDevice) -> WriteResult): SendOutcome {
        val d = findDac() ?: run { link = Link.NO_DAC; snapshot = null; return SendOutcome(false, "NO DAC") }
        if (!manager.hasPermission(d)) {
            link = Link.NEEDS_PERMISSION
            snapshot = null
            return SendOutcome(false, "NO PERMISSION")
        }
        if (busy) return SendOutcome(false, "DAC BUSY")
        busy = true
        val epoch = connectionEpoch
        val started = protocol
        return try {
            val r = op(d)
            // Experimental device, or the DAC model changed under the write: never keep a result for the wrong DAC.
            if ((started.experimental || protocol != started) && epoch != connectionEpoch) return SendOutcome(false, "DAC DETACHED")
            lastWrite = r
            // Protocol Max and KA15 fail closed: a mismatching read-back is not ON DAC. The Micro keeps the 1.2.2 behaviour.
            snapshot = if (r.readBack.target.experimental && !r.verified) null else r.readBack
            // A verified write can still carry a note (KA15: the slot name did not read back).
            error = if (r.verified) r.mismatches.firstOrNull() else null
            if (r.verified) SendOutcome(true, null) else SendOutcome(false, "READ-BACK MISMATCH: ${r.mismatches.firstOrNull() ?: ""}")
        } catch (e: Exception) {
            UsbLog.line("$what failed: ${e.message}")
            snapshot = null // a partial write or failed read cannot establish what the DAC holds
            error = e.message
            SendOutcome(false, e.message ?: e.javaClass.simpleName)
        } finally {
            busy = false
        }
    }

    // ---- service screen (the v0.1 debug buttons; only the owner presses them) ----------------------------

    fun serviceTestWrite() = launchOp("test write") { d -> showWrite(client.sendTestBand1(d)) }
    fun serviceRestoreFlat() {
        if (protocol.native) return // Micro and Max only; the KA15 resets through a flat profile and HOLD TO SEND
        launchOp("restore flat") { d -> showWrite(client.restoreFlat(d)) }
    }

    private fun showWrite(r: WriteResult) {
        lastWrite = r
        if (r.readBack.target.experimental && !r.verified) {
            snapshot = null
            throw java.io.IOException("READ-BACK MISMATCH: ${r.mismatches.firstOrNull()}")
        }
        snapshot = r.readBack
    }

    private fun launchOp(what: String, keepSnapshot: Boolean = false, op: suspend (UsbDevice) -> Unit) {
        val d = findDac() ?: run { link = Link.NO_DAC; snapshot = null; return }
        if (!manager.hasPermission(d)) { link = Link.NEEDS_PERMISSION; snapshot = null; return }
        if (busy || scanning) return // the background scan owns the DAC until it ends or a send stops it
        busy = true
        val epoch = connectionEpoch
        val started = protocol
        scope.launch {
            try {
                op(d)
                if ((started.experimental || protocol != started) && epoch != connectionEpoch) { snapshot = null; return@launch }
                error = null
            } catch (e: Exception) {
                UsbLog.line("$what failed: ${e.message}")
                if (!keepSnapshot || epoch != connectionEpoch) snapshot = null
                error = "$what failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    companion object {
        /** How long SAVING TO DAC stays after a transfer ends, so the fill is seen to reach the end. */
        private const val SETTLE_MS = 250L
        private const val ACTION_USB_PERMISSION = "io.github.chronosauros.contour.USB_PERMISSION"
    }
}
