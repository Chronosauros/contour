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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.chronosauros.contour.core.BuildChannel
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.Proven
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.core.native.DacSetting
import io.github.chronosauros.contour.core.native.FamilyDevices
import io.github.chronosauros.contour.core.native.FamilySettings
import io.github.chronosauros.contour.core.native.FamilyState
import io.github.chronosauros.contour.core.native.FiioCodec
import io.github.chronosauros.contour.core.native.FiioConfig
import io.github.chronosauros.contour.core.native.FiioMelodyExchange
import io.github.chronosauros.contour.core.native.FiioRegisters
import io.github.chronosauros.contour.core.native.FiioReset
import io.github.chronosauros.contour.core.native.FiioResetBackup
import io.github.chronosauros.contour.core.native.FiioResetFlow
import io.github.chronosauros.contour.core.native.FiioResetSent
import io.github.chronosauros.contour.core.native.FiioSnapshot
import io.github.chronosauros.contour.core.native.MoondropCodec
import io.github.chronosauros.contour.core.native.NativeState
import io.github.chronosauros.contour.core.native.ProbePlan
import io.github.chronosauros.contour.core.native.SettingKind
import io.github.chronosauros.contour.core.native.SettingReading
import io.github.chronosauros.contour.core.native.SettingWriteOutcome
import io.github.chronosauros.contour.core.native.WalkPlayProfile
import io.github.chronosauros.contour.core.native.WalkPlayReset
import io.github.chronosauros.contour.core.native.WalkPlayResetBackup
import io.github.chronosauros.contour.core.native.WalkPlayResetFlow
import io.github.chronosauros.contour.core.native.WalkPlayResetSent
import io.github.chronosauros.contour.core.native.WalkPlaySettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class Link { NO_DAC, NEEDS_PERMISSION, CONNECTED }

/** Outcome of one send from the app (HOLD TO SEND). */
data class SendOutcome(val verified: Boolean, val reason: String?)

/**
 * The factory reset of the DAC SETTINGS page (SnowSky Melody, Tiny A, FiiO KA15) as the sheet shows it, step by step ([DeviceController.confirmFactoryReset]). Only a button of
 * the confirmation sheet moves it from [Confirm] on; nothing is sent before the copy of the DAC has been saved.
 */
sealed interface ResetUi {
    companion object {
        /** The step shown while the FiiO reset waits for the owner to unplug the DAC and plug it back in (the sheet words its second line from it). */
        const val REPLUG_STEP = "UNPLUG THE DAC AND PLUG IT BACK IN"
    }
    data object Idle : ResetUi
    /** The confirmation sheet is open: nothing has been read or sent. */
    data object Confirm : ResetUi
    /** [what] is the step in progress. */
    data class Running(val what: String) : ResetUi
    /** The reset was sent and the DAC was read again: [changes] are what differs from the saved copy (empty = nothing, the reset may not have run); [sentNote] says what is unknown about the frame. */
    data class Result(val changes: List<String>, val sentNote: String?, val backupFile: String) : ResetUi
    data object Restoring : ResetUi
    /** The saved copy was written back, one line per slot and setting; [ok] = every one read back as saved. */
    data class Restored(val lines: List<String>, val ok: Boolean) : ResetUi
    /** Stopped. [sent] = the reset frame had gone out; [canRestore] = a copy is kept and can be written back; [backupFile] = its name. */
    data class Failed(val reason: String, val sent: Boolean, val canRestore: Boolean, val backupFile: String?) : ResetUi
}

/**
 * The DAC as the UI sees it: link state, the last read (for matching), busy flag. Reads on connect and on
 * resume (read-only); writes only through [send] / the service screen's buttons, i.e. only on an
 * explicit user action. All I/O runs on DacClient's single USB thread.
 */
class DeviceController(private val context: Context, private val scope: CoroutineScope) {
    private val manager = context.getSystemService(UsbManager::class.java)
    val client = DacClient(manager, { d -> sessionGuard(d) }, { d -> UsbAudioKeepAlive.start(context, d) })

    /**
     * Review hook only (`--es simulate <dac>`, [DacSimulation]): the DAC the app behaves as if it were attached; null = the real bus. While it is set [findDac] never looks at the USB
     * host, every operation runs on the simulated DAC's own connection ([DacHandle.simulated]) and the phone's slot records are kept apart ([recordScope]). Never set in a release build.
     */
    private var simulated: DacHandle? = null
    private val recordScope: String get() = if (simulated != null) "sim:" else ""

    /** The system's USB permission for a USB DAC; a simulated one has none to ask for. */
    private fun permitted(d: DacHandle): Boolean = d.usb?.let { manager.hasPermission(it) } ?: true

    /**
     * Review hook only (`--es simulate <dac>`, MainActivity): connects the simulated DAC [name] ([DacSimulation.open]; null or an unknown name = back to the real bus) as if it had just
     * been plugged in: nothing read, written or switched on the DAC before carries over, then it is read like any attached DAC. Its registers, slots and settings stay in memory for the
     * process, so a read after a send shows what the send wrote.
     */
    fun simulate(name: String?) {
        val next = name?.let(DacSimulation::open)
        if (name != null && next == null) return UsbLog.line("simulate: no simulated DAC \"$name\"")
        // the same DAC again (MainActivity applies its extras more than once on a cold start): nothing to reconnect
        if (next?.key == simulated?.key) return
        simulated = next
        UsbLog.line(if (next != null) "simulate: ${next.target.caps.name} (no USB)" else "simulate: off")
        dismissInterruptedSend()
        connectionEpoch++
        snapshot = null
        lastWrite = null
        error = null
        abBypassed = false
        abReference = null
        selectedSlot = null
        openPending = true
        lastProtocol = null
        dropDacWork()
        refresh(read = true)
        rereadSoon()
    }

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
        returnIndex = null
        dropSettings()
    }
    // ---- DAC Settings page --------------------------------------------------------------------------------------------------
    // The page is generated from the settings list of the DAC's [DeviceTarget] (FiiO, WalkPlay or a 1.6 family; empty = no page). A row is written only where [DacSetting.writeEnabled]
    // says so: proven on hardware, or a review build ([io.github.chronosauros.contour.core.Proven]); a release shows no unproven WalkPlay row, so it has no page for a WalkPlay DAC.

    /** Review hook only (`--es dac tinya|melody|ka15`): the target whose list the page shows while no attached DAC has a list; nothing touches USB. */
    var dacPreview by mutableStateOf<DeviceTarget?>(null)
    /** A DAC with a settings list is attached (or plugged in and waiting for permission): the page shows that DAC, never the preview. */
    private val attachedHasSettings: Boolean get() = link != Link.NO_DAC && protocol.settings.isNotEmpty()
    /** The DAC whose settings the page shows: the one attached (when it has a list), else the preview (also over an attached DAC without a list). Null = no DAC Settings page. */
    val dacTarget: DeviceTarget? get() = if (attachedHasSettings) protocol else dacPreview?.takeIf { it.settings.isNotEmpty() }
    /** The page shows a preview, not a DAC. */
    val dacIsPreview: Boolean get() = !attachedHasSettings && dacTarget != null
    val dacSettings: List<DacSetting> get() = dacTarget?.settings.orEmpty()
    /** The last settings read of this connection, by setting id. */
    var settingValues by mutableStateOf<Map<String, SettingReading>>(emptyMap())
        private set
    /** A settings pass of this connection has finished (answered or not): a readable setting without a value then says NO REPLY. */
    var settingsRead by mutableStateOf(false)
        private set
    var settingsReading by mutableStateOf(false)
        private set
    /** Counts the first reads of connections; the page reads its settings after each one. */
    var connectReads by mutableIntStateOf(0)
        private set
    private var settingsJob: Job? = null

    /**
     * Reads every readable setting of the connected DAC (read-only). It waits for the connect read, the background slot scan and any
     * other DAC work to finish, and holds the DAC ([busy]) while it reads. The slot the app knows is not touched.
     */
    fun readSettings() {
        // The Dawn Pro 2 and the Allegro Mini fill their rows from the connect read ([publishFamilySettings]); there is no separate settings pass for them. The Echo-A is a WalkPlay DAC: it has one.
        if (link != Link.CONNECTED || (protocol.isFamily && protocol.echo == null) || protocol.settings.none { it.read != null }) return
        if (settingsJob?.isActive == true) return
        val epoch = connectionEpoch
        settingsJob = scope.launch {
            settingsReading = true
            try {
                var waits = 0
                while ((busy || scanning || following || connecting || openPending) && waits++ < 400) delay(25)
                if (epoch != connectionEpoch || busy || scanning || following || openPending) return@launch
                val d = findDac() ?: return@launch
                if (!permitted(d)) return@launch
                busy = true
                try {
                    val r = client.readSettings(d)
                    if (epoch == connectionEpoch) { settingValues = r.associateBy { it.id }; settingsRead = true }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    UsbLog.line("settings failed: ${e.message}")
                    if (epoch == connectionEpoch) { settingValues = emptyMap(); settingsRead = true }
                } finally {
                    busy = false
                }
            } finally {
                settingsReading = false
            }
        }
    }
    private var writeJob: Job? = null
    /**
     * Sets one setting of the connected DAC and reads it back (the UI send path). Only a setting with [DacSetting.writeEnabled] (proven, or a review build) goes out, and never
     * the preview: otherwise [onDone] gets null at once. It waits for the connect read, the background slot scan, the follower and any other DAC work
     * and holds the DAC ([busy]) while it writes, so it never runs beside an EQ write or the slot scan; one write at a time. [onDone] gets the outcome
     * (null = not run). A verified value, or the value the DAC read instead, replaces the one in [settingValues]. A verified VOLUME STEPS re-reads
     * the settings (the volume may rescale and the slider top moves).
     */
    fun writeSetting(id: String, value: Int, onDone: (SettingWriteOutcome?) -> Unit) {
        val s = protocol.settings.firstOrNull { it.id == id }
        if (s == null || !s.writeEnabled || dacIsPreview || link != Link.CONNECTED || writeJob?.isActive == true) return onDone(null)
        // WalkPlay (Micro, TRN, Echo-A) goes through its own session below; the Dawn Pro 2 and the Allegro Mini write by setting id: each id has its own frame and its own read-back,
        // and an id with none is refused here.
        val walkplay = protocol.walkplayProfile != null
        if (protocol.isFamily && !walkplay) return writeFamilySetting(id, value, onDone)
        val epoch = connectionEpoch
        writeJob = scope.launch {
            var outcome: SettingWriteOutcome? = null
            try {
                var waits = 0
                while ((busy || scanning || following || connecting || openPending) && waits++ < 400) delay(25)
                if (epoch != connectionEpoch || busy || scanning || following || connecting || openPending) return@launch
                val d = findDac() ?: return@launch
                if (!permitted(d)) return@launch
                busy = true
                try {
                    outcome = if (walkplay) client.writeWalkPlaySetting(d, id, value) else client.writeSetting(d, id, value)
                    if (epoch == connectionEpoch) when (val o = outcome) {
                        SettingWriteOutcome.Verified -> settingValues = settingValues + (id to SettingReading(id, value, null))
                        is SettingWriteOutcome.Mismatch -> settingValues = settingValues + (id to SettingReading(id, o.read, null))
                        else -> Unit
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    UsbLog.line("settings write failed: ${e.message}")
                    outcome = SettingWriteOutcome.NoReply
                } finally {
                    busy = false
                }
                if (outcome == SettingWriteOutcome.Verified && id == "volume-steps") readSettings()
            } finally {
                onDone(outcome)
            }
        }
    }
    /**
     * One setting of a 1.6 family, by id: the Allegro Mini EQ switch (register 0x24) and Global Gain (0x3B, steps 1-15), the Dawn Pro 2 Global Gain (0x03) and Pre Gain
     * (0x23, tenths of a dB). Only a row with [DacSetting.writeEnabled] gets here ([writeSetting]); an id that has no frame for this DAC is refused (null). An Allegro
     * write ends at SAVE: the DAC leaves the bus and the value counts only after it is back and a read agrees. A Dawn gain is read back in the same session.
     */
    private fun writeFamilySetting(id: String, value: Int, onDone: (SettingWriteOutcome?) -> Unit) {
        val allegro = protocol.ktMicro != null
        val dawn = protocol.moondrop != null
        val op: (suspend (DacHandle) -> WriteResult)? = when {
            allegro && id == FamilySettings.EQ_SWITCH -> { d -> client.writeEqSwitch(d, value == 1) }
            allegro && id == FamilySettings.GLOBAL_GAIN -> { d -> client.writeAllegroGlobalGain(d, value) }
            dawn && id == FamilySettings.GLOBAL_GAIN -> { d -> client.writeDawnGain(d, MoondropCodec.GainRegister.GLOBAL, value / 10.0) }
            dawn && id == FamilySettings.PRE_GAIN -> { d -> client.writeDawnGain(d, MoondropCodec.GainRegister.PRE, value / 10.0) }
            else -> null
        }
        if (op == null) return onDone(null)
        val epoch = connectionEpoch
        writeJob = scope.launch {
            var outcome: SettingWriteOutcome? = null
            try {
                var waits = 0
                while ((busy || scanning || following || connecting || openPending) && waits++ < 400) delay(25)
                if (epoch != connectionEpoch || busy || scanning || following || connecting || openPending) return@launch
                val d = findDac() ?: return@launch
                if (!permitted(d)) return@launch
                busy = true
                var written: WriteResult? = null
                try {
                    written = op(d)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    UsbLog.line("settings write failed: ${e.message}")
                    outcome = SettingWriteOutcome.NoReply
                } finally {
                    busy = false
                }
                // What the read after the write says for this setting (null = the read does not carry it).
                fun readOf(s: DacSnapshot?): Int? = s?.familyState?.readings()?.firstOrNull { it.id == id }?.value
                written?.pending?.let { save ->
                    val v = awaitSave(save)
                    val read = readOf(v.readBack)
                    outcome = when {
                        v.verified -> SettingWriteOutcome.Verified
                        read != null -> SettingWriteOutcome.Mismatch(read)
                        else -> SettingWriteOutcome.NoReply
                    }
                    if (v.verified) settingValues = settingValues + (id to SettingReading(id, value, null))
                    UsbLog.line("settings write: ${v.reason ?: "VERIFIED"}")
                } ?: written?.let { w ->
                    val read = readOf(w.readBack)
                    outcome = when {
                        w.verified -> SettingWriteOutcome.Verified
                        read != null -> SettingWriteOutcome.Mismatch(read)
                        else -> SettingWriteOutcome.NoReply
                    }
                    if (epoch == connectionEpoch) {
                        if (w.verified) { w.readBack?.let { snapshot = it; publishFamilySettings() } }
                        else if (read != null) settingValues = settingValues + (id to SettingReading(id, read, null))
                    }
                    UsbLog.line("settings write: ${if (w.verified) "VERIFIED" else w.mismatches.firstOrNull() ?: "NOT VERIFIED"}")
                }
            } finally {
                onDone(outcome)
            }
        }
    }
    private var writeTestJob: Job? = null
    /** The last write self-test of this connection as one line (the page shows it in its footer); null = none yet. */
    var writeTestLine by mutableStateOf<String?>(null)
        private set

    /**
     * The write self-test (review builds only, started by `--es writetest <id> [--ei to <v>]`; see [NativeSession.writeTest]): the one write path of this
     * build that is not the UI, run by hand on a DAC that is attached. Reads the setting, writes a target value, reads it back, writes the original
     * back and reads it back; every TX and RX and the result line go to the USB log and to `<external files>/probe/writetest-<id>.txt`. Like the
     * probe it waits for work in progress and holds the DAC ([busy]). A refusal (a setting that is not in the model's list, USB AUDIO) is one line.
     */
    fun writeTest(id: String, to: Int?) {
        val safe = ProbePlan.safeLabel(id)
        val file = runCatching { java.io.File(context.getExternalFilesDir(null), "probe").also { it.mkdirs() }.resolve("writetest-$safe.txt") }.getOrNull()
        val sink: (String) -> Unit = { line -> UsbLog.line(line); runCatching { file?.appendText(line + '\u000A') } }
        if (writeTestJob?.isActive == true) return sink("WRITETEST $safe busy: a self-test is already running")
        writeTestJob = scope.launch {
            val d = findDac()
            if (d == null) return@launch sink("WRITETEST $safe no DAC attached")
            if (!permitted(d)) return@launch sink("WRITETEST $safe no USB permission for the DAC")
            var waits = 0
            while ((busy || scanning || following || connecting || openPending) && waits++ < 400) delay(25)
            if (busy || scanning || following || connecting || openPending) return@launch sink("WRITETEST $safe busy: Contour is still working on the DAC")
            busy = true
            var done = false
            try {
                val r = client.writeTest(d, id, to, sink)
                writeTestLine = r.line().uppercase()
                done = true
            } catch (e: IllegalArgumentException) {
                sink("WRITETEST $safe refused: ${e.message}")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                sink("WRITETEST $safe failed ${e.message}")
            } finally {
                busy = false
            }
            if (done) readSettings() // the page shows what the DAC says now
        }
    }
    private var probeJob: Job? = null

    /**
     * The DAC probe (review builds only, started by `--es probe <label>`; see [DacClient.probe]): one pass on the attached DAC, reads only.
     * It does not read the DAC for the app, scan slots or change the link; it waits for work in progress, holds the DAC ([busy]) while it
     * runs and writes its lines to the USB log and to `<external files>/probe/<label>.txt`. No DAC or no permission is one line and nothing else.
     */
    fun probe(label: String) {
        val safe = ProbePlan.safeLabel(label)
        val file = runCatching { java.io.File(context.getExternalFilesDir(null), "probe").also { it.mkdirs() }.resolve("$safe.txt") }.getOrNull()
        val sink: (String) -> Unit = { line -> UsbLog.line(line); runCatching { file?.appendText(line + '\u000A') } }
        if (simulated != null) return sink("PROBE $safe skipped: a simulated DAC has no USB to read")
        if (probeJob?.isActive == true) return sink("PROBE $safe busy: a probe is already running")
        probeJob = scope.launch {
            val d = findDac()
            if (d == null) return@launch sink("PROBE $safe no DAC attached")
            if (!permitted(d)) return@launch sink("PROBE $safe no USB permission for the DAC")
            var waits = 0
            while ((busy || scanning || following || connecting || openPending) && waits++ < 400) delay(25)
            if (busy || scanning || following || connecting || openPending) return@launch sink("PROBE $safe busy: Contour is still working on the DAC")
            busy = true
            try {
                client.probe(d, safe, sink)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                sink("PROBE $safe failed ${e.message}")
            } finally {
                busy = false
            }
        }
    }
    private fun dropSettings() {
        settingsJob?.cancel()
        if (pending == null) writeJob?.cancel() // an Allegro write waits for its DAC to come back: the detach that ends the connection is expected
        writeTestLine = null
        settingValues = emptyMap()
        settingsRead = false
        settingsReading = false
    }

    /** A supported DAC is plugged in and one tap connects it: NO DAC is shown only when nothing connectable is attached (owner, 14.10.2026). */
    val tapToConnect: Boolean get() = link == Link.NEEDS_PERMISSION
    var snapshot by mutableStateOf<DacSnapshot?>(null)
        private set
    var lastWrite by mutableStateOf<WriteResult?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /**
     * A send that ended before the DAC confirmed it (unplugged mid-send, a timeout, a lost session): the DAC may hold a partial EQ. Unlike [error] it is shown whatever the link is (the DAC is gone
     * when it happens) and it stays until a send is verified, the user dismisses it ([dismissInterruptedSend]) or another DAC model replaces the DAC. The fresh read after the DAC is back does
     * not clear it: it changes the text ([interruptedReadAgain]) so the user compares what the DAC now holds with what was meant to be sent.
     */
    var interruptedSend by mutableStateOf<String?>(null)
        private set
    /** The DAC has been read again since the interrupted send. */
    var interruptedReadAgain by mutableStateOf(false)
        private set
    fun dismissInterruptedSend() { interruptedSend = null; interruptedReadAgain = false }
    private fun markInterrupted(reason: String) {
        interruptedSend = reason
        interruptedReadAgain = false
        UsbLog.line("send interrupted: $reason")
    }

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
    private fun recordKey(slot: Int) = "$recordScope${protocol.caps.name}:$slot"
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
    private fun linkKey(slot: Int) = "link:$recordScope${protocol.caps.name}:$slot"
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

    private fun fingerprint(s: DacSnapshot): String = s.familyState?.describe() ?: (s.nativeState as? NativeState.Fiio)?.let { encode(it.raw) }
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
        // A 1.6 family has no slots to link: what the DAC holds opens in the editor only when it is Contour's own bank (the Dawn on another EQ index and the Echo-A are only shown).
        s.familyState?.let { fs -> return if (!fs.importable) null else SlotContent(null, fs.describe(), null, null, { p -> fs.holds(p) }, { false }) }
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
    private fun sessionGuard(d: DacHandle): () -> Unit {
        val epoch = connectionEpoch
        val id = d.usb?.deviceId
        return {
            // a simulated DAC is never unplugged: only a new connection ends its session
            if (epoch != connectionEpoch || (id != null && manager.deviceList.values.none { it.deviceId == id }) || !permitted(d))
                throw IOException("DAC SESSION CHANGED; reconnect and retry")
        }
    }

    /** Why [profile] cannot be sent to the DAC now; empty = HOLD TO SEND may write. */
    fun sendIssues(profile: Profile): List<String> {
        val issues = protocol.issues(profile)
        if (issues.isNotEmpty() || !protocol.native) return issues
        // The 1.6 families: the profile fits their catalog; HOLD TO SEND also needs a successful read (and a read-only Echo-A never sends).
        if (protocol.isFamily) return FamilyDevices.stateIssues(protocol, snapshot?.familyState)
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
            if (simulated != null) return // review builds: a simulated DAC is on, the real bus is left alone
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    UsbLog.line("permission ${if (granted) "granted" else "denied"}")
                    refresh(read = granted)
                    if (granted) rereadSoon()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // The app may be open while the system dialog is dismissed or handled elsewhere: TAP TO CONNECT.
                    if (!isDac(intent)) return
                    dacAttaches++
                    UsbLog.line("attached (broadcast)")
                    refresh(read = true)
                    rereadSoon()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (!isDac(intent)) return
                    dacDetaches++
                    UsbLog.line("detached")
                    pending?.detached = true
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

    // ---- the DAC comes back: a fresh read, asked again when it did not take -------------------------------------------------------------------------
    // Plugging the DAC in already reads it ([refresh]), but that read is dropped while the controller is busy or the background scan runs, and it fails when another Contour (the
    // stable app) wins the HID interface at the same moment (runs 2026-10-22-snowsky-melody-01-phone, 2026-10-23-snowsky-tiny-a-02-phone: nobody asked again). So after an attach
    // (or the USB permission) the read is repeated, a few times, while the DAC is there and still not read; the first success ends it.

    private var rereadJob: Job? = null
    private fun rereadSoon() {
        rereadJob?.cancel()
        rereadJob = scope.launch {
            val epoch = connectionEpoch
            repeat(REREAD_ATTEMPTS) { n ->
                delay(if (n == 0) REREAD_FIRST_MS else REREAD_GAP_MS)
                val d = findDac() ?: return@launch // gone again: the next attach asks again
                if (!permitted(d) || abBypassed) return@launch // the permission result asks
                var waited = 0
                while ((busy || scanning) && waited < REREAD_BUSY_MAX_MS) { delay(200); waited += 200 }
                if (snapshot != null) { if (interruptedSend != null) interruptedReadAgain = true; return@launch }
                UsbLog.line("read again (attempt ${n + 1} of $REREAD_ATTEMPTS)")
                refresh(read = true)
                delay(REREAD_ANSWER_MS)
                if (snapshot != null) { if (interruptedSend != null) interruptedReadAgain = true; return@launch }
            }
        }
    }

    private fun isDac(intent: Intent): Boolean {
        val d = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        return d != null && DeviceIdentity.target(d) != null
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

    private fun findDac(): DacHandle? = simulated ?: manager.deviceList.values.firstNotNullOfOrNull { DacHandle.of(it) }

    /** Recomputes the link; when connected and [read], reads the DAC (read-only). */
    fun refresh(read: Boolean) {
        val d = findDac()
        val found = d?.target
        if (found != null && lastProtocol != null && found != lastProtocol) {
            // Another DAC model (Micro <-> Max <-> KA15): nothing read, written or switched on the old one carries over.
            UsbLog.line("DAC changed: ${lastProtocol?.caps?.name} -> ${found.caps.name}")
            dismissInterruptedSend()
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
            !permitted(d) -> Link.NEEDS_PERMISSION
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
        val usbDevice = d.usb
        if (usbDevice == null || manager.hasPermission(usbDevice)) return refresh(true)
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        manager.requestPermission(usbDevice, PendingIntent.getBroadcast(context, 0, intent, flags))
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
                publishFamilySettings()
                resolvePending()
                recordSlot()
                if (first) {
                    openPending = false
                    // The slot playing becomes the chosen one; unknown or not a USER slot = none chosen until a tap.
                    if (identifyPlayingSlot() != null) recordSlot()
                    selectedSlot = playingSlot
                    snapshot?.let { s -> onConnectRead?.invoke(s) }
                    startScan(d)
                    connectReads++
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

    private fun startScan(d: DacHandle) {
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
    private suspend fun finishScan(d: DacHandle, f: FiioConfig, connectRaw: FiioSnapshot, start: Int?, lastRead: Int?) {
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
            if (!permitted(d)) { link = Link.NEEDS_PERMISSION; snapshot = null; return }
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
                error = "switch failed: ${FailureText.of(e.message ?: e.javaClass.simpleName)}"
            }
        } finally {
            if (held) busy = false
            following = false
            pendingTaps.clear()
            endTransfer(Kind.SWITCH, null)
        }
    }

    /** Brings the DAC to USER [target]: a known slot is switched to (and read once if that is not confirmed), an unknown one is read. */
    private suspend fun serve(d: DacHandle, target: Int, onRead: ((DacSnapshot) -> Unit)?, epoch: Long): Kind {
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
        if (!permitted(d)) { link = Link.NEEDS_PERMISSION; return forgetAb("NO PERMISSION") }
        if (busy) return forgetAb("DAC BUSY")
        if (bypass) {
            // B is entered only when A can be put back: the restoring sequence is encoded (and so checked) first.
            val restore = reference.bands.map { it.registers }.filter { it.gain256 != 0 }.map { r ->
                WalkPlay.BandWrite(r.index, r.freq.toDouble(), r.gain256 / 256.0, r.q256 / 256.0, r.typeCode, rateHz = protocol.caps.coefficientRateHz)
            }
            if (runCatching { WalkPlay.temporaryBandSequence(restore, reference.slot) }.isFailure) {
                return SendOutcome(false, "A/B blocked: a band on the DAC does not fit its filter format, so A could not be restored. Nothing was changed.")
            }
        }
        val epoch = connectionEpoch
        val bands = reference.bands.map { it.registers }.filter { it.gain256 != 0 }.map { r ->
            WalkPlay.BandWrite(r.index, r.freq.toDouble(), if (bypass) 0.0 else r.gain256 / 256.0, r.q256 / 256.0, r.typeCode, rateHz = protocol.caps.coefficientRateHz)
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
            else forgetAb(FailureText.of(e.message ?: e.javaClass.simpleName))
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

    private suspend fun sendWith(what: String, op: suspend (DacHandle) -> WriteResult): SendOutcome {
        val d = findDac() ?: run { link = Link.NO_DAC; snapshot = null; return SendOutcome(false, "NO DAC") }
        if (!permitted(d)) {
            link = Link.NEEDS_PERMISSION
            snapshot = null
            return SendOutcome(false, "NO PERMISSION")
        }
        if (busy) return SendOutcome(false, "DAC BUSY")
        busy = true
        var held = true
        val epoch = connectionEpoch
        val started = protocol
        return try {
            val r = op(d)
            // Allegro Mini: the write ended at SAVE and the DAC leaves the bus. The DAC is released for the read after it is back, and the send
            // is not done until that read has been compared with what was written ([awaitSave]); a detach is expected here, not an error.
            r.pending?.let { save ->
                held = false
                busy = false
                lastWrite = r
                snapshot = null
                error = null
                return finishSave(r, save)
            }
            // Experimental device, or the DAC model changed under the write: never keep a result for the wrong DAC.
            if ((started.experimental || protocol != started) && epoch != connectionEpoch) {
                markInterrupted("DAC DETACHED")
                return SendOutcome(false, "DAC DETACHED")
            }
            lastWrite = r
            r.previousIndex?.let { returnIndex = it }
            val back = requireNotNull(r.readBack)
            // Protocol Max, KA15 and the 1.6 families fail closed: a mismatching read-back is not ON DAC. The Micro keeps the 1.2.2 behaviour.
            snapshot = if (back.target.experimental && !r.verified) null else back
            publishFamilySettings()
            // A verified write can still carry a note (KA15: the slot name did not read back).
            error = if (r.verified) FailureText.note(r.mismatches.firstOrNull()) else null
            if (r.verified) dismissInterruptedSend()
            if (r.verified) SendOutcome(true, null) else SendOutcome(false, FailureText.readBack(r.mismatches.firstOrNull()))
        } catch (e: Exception) {
            UsbLog.line("$what failed: ${e.message}")
            snapshot = null // a partial write or failed read cannot establish what the DAC holds
            val shown = FailureText.of(e.message ?: e.javaClass.simpleName)
            error = if (Proven.channel == BuildChannel.PERF) e.message else shown
            markInterrupted(shown)
            SendOutcome(false, shown)
        } finally {
            if (held) busy = false
        }
    }

    // ---- a write that ends at SAVE (Allegro Mini) ---------------------------------------------------------------------------
    // SAVE makes the DAC drop off the USB bus and come back. Until a read after that has been compared with what was written the write is
    // NOT verified: [verifyingSave] is true, HOLD TO SEND says SAVING TO DAC (TAP TO CONNECT when the system needs a tap to let the DAC in again).
    // The wait polls: once the DAC has been seen leaving (or [SAVE_NO_DETACH_MS] passed without it leaving) a read is tried every second.

    private class PendingVerify(val save: PendingSave) {
        val startedMs = SystemClock.elapsedRealtime()
        val result = CompletableDeferred<Verdict>()
        @Volatile var detached = false
        /** A read may count as the proof: the DAC was seen leaving the bus, or it never left within the time a re-enumeration takes. */
        fun ready(): Boolean = detached || SystemClock.elapsedRealtime() - startedMs >= SAVE_NO_DETACH_MS
    }
    /** [reason] is null when verified; [readBack] is null when the DAC did not come back in time. */
    private class Verdict(val reason: String?, val mismatches: List<String>, val readBack: DacSnapshot?) { val verified: Boolean get() = reason == null }
    private var pending: PendingVerify? = null
    /** A write ended at SAVE and has not been proven by a read yet. */
    var verifyingSave by mutableStateOf(false)
        private set

    /** The first read after the DAC is back settles the write that is waiting. */
    private fun resolvePending() {
        val p = pending ?: return
        val s = snapshot ?: return
        if (p.result.isCompleted || !p.ready() || s.familyState !is FamilyState.Allegro) return
        val mismatches = p.save.verify(s)
        UsbLog.line("save: read back after ${if (p.detached) "reconnect" else "no reconnect"}: ${if (mismatches.isEmpty()) "VERIFIED" else mismatches.joinToString("; ")}")
        p.result.complete(Verdict(if (mismatches.isEmpty()) null else FailureText.readBack(mismatches.first()), mismatches, s))
    }

    private suspend fun awaitSave(save: PendingSave): Verdict {
        val p = PendingVerify(save)
        pending = p
        verifyingSave = true
        UsbLog.line("save: waiting for the DAC to come back (up to ${VERIFY_TIMEOUT_MS / 1000} s)")
        try {
            // The 45 s do not run while the system's USB permission dialog is open (link NEEDS_PERMISSION): the app cannot read the DAC until the person answers it, and a late OK used to
            // find the wait over - the app then read the DAC itself, the registers equalled what was sent, and the button still said FAILED (Allegro Mini, Nothing Phone and Pixel, 24-25.10.2026,
            // where the system asks again after every SAVE). A dialog nobody answers still ends it, after VERIFY_PERMISSION_MAX_MS. The DAC's absence from the bus counts as before.
            var counted = 0L
            var askedFor = 0L
            var last = SystemClock.elapsedRealtime()
            while (!p.result.isCompleted) {
                delay(VERIFY_POLL_MS)
                val now = SystemClock.elapsedRealtime()
                if (link == Link.NEEDS_PERMISSION) askedFor += now - last else counted += now - last
                last = now
                if (p.result.isCompleted) break
                if (counted >= VERIFY_TIMEOUT_MS || askedFor >= VERIFY_PERMISSION_MAX_MS) {
                    val why = if (counted >= VERIFY_TIMEOUT_MS) "did not come back within ${VERIFY_TIMEOUT_MS / 1000} s" else "was not let in: the USB permission was not given within ${VERIFY_PERMISSION_MAX_MS / 1000} s"
                    return Verdict("$NOT_VERIFIED: the DAC $why; plug it in again, then tap to read it", emptyList(), null)
                }
                if (p.ready() && link == Link.CONNECTED && !busy && !scanning) read()
            }
            return p.result.await()
        } finally {
            if (pending === p) pending = null
            verifyingSave = false
        }
    }

    private suspend fun finishSave(written: WriteResult, save: PendingSave): SendOutcome {
        val v = awaitSave(save)
        val back = v.readBack
        lastWrite = written.copy(verified = v.verified, mismatches = v.mismatches, readBack = back, pending = null)
        // Fail closed like every non-WalkPlay target: a write that is not proven leaves no read to call ON DAC.
        snapshot = if (v.verified) back else null
        publishFamilySettings()
        error = v.reason
        return if (v.verified) SendOutcome(true, null) else SendOutcome(false, v.reason)
    }

    /** The DAC Settings rows of the Dawn Pro 2 and the Allegro Mini are what the last read says ([FamilyState.readings]); the Echo-A's come from the WalkPlay settings pass ([readSettings]). */
    private fun publishFamilySettings() {
        val state = snapshot?.familyState ?: return
        if (state is FamilyState.EchoA) return
        settingValues = state.readings().associateBy { it.id }
        settingsRead = true
    }

    // ---- Dawn Pro 2: back to the EQ index the DAC was on --------------------------------------------------------------------------

    /** The EQ index the Dawn Pro 2 was on before Contour's send switched it to Contour's bank (null = no send switched it, or it went back). */
    var returnIndex by mutableStateOf<Int?>(null)
        private set

    /** One explicit action: the DAC goes back to [returnIndex] (saved there), is read again and shows the index it is on. */
    fun returnToPreviousIndex() {
        val index = returnIndex ?: return
        if (protocol.moondrop == null) return
        launchOp("switch", keepSnapshot = true) { d ->
            val t0 = SystemClock.elapsedRealtime()
            beginTransfer(Kind.SWITCH)
            try {
                val confirmed = client.returnToIndex(d, index)
                if (confirmed != index) throw IOException("the DAC reports EQ index $confirmed, not $index")
                returnIndex = null
                snapshot = client.readDevice(d)
                publishFamilySettings()
            } finally {
                endTransfer(Kind.SWITCH, SystemClock.elapsedRealtime() - t0)
            }
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
        val back = requireNotNull(r.readBack) { "The service write has no read-back" }
        if (back.target.experimental && !r.verified) {
            snapshot = null
            throw java.io.IOException(FailureText.readBack(r.mismatches.firstOrNull()))
        }
        snapshot = back
    }

    private fun launchOp(what: String, keepSnapshot: Boolean = false, op: suspend (DacHandle) -> Unit) {
        val d = findDac() ?: run { link = Link.NO_DAC; snapshot = null; return }
        if (!permitted(d)) { link = Link.NEEDS_PERMISSION; snapshot = null; return }
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
                error = "$what failed: ${FailureText.of(e.message ?: e.javaClass.simpleName)}"
            } finally {
                busy = false
            }
        }
    }

    // ---- factory reset (SnowSky Melody, Tiny A, FiiO KA15) -------------------------------------------------------------------------------------
    // The one place the app can send FiiO command 0x21 or the WalkPlay reset sequence, and only through [FiioResetFlow] / [WalkPlayResetFlow]: a complete copy of the DAC is read and kept on the phone first, the owner's
    // button on the confirmation sheet issues the one-frame ticket, the DAC is read again after the reset and what changed is shown, and one more button writes the
    // copy back. It runs on its own job ([resetJob]) because the DAC may leave the bus and come back, which ends the connection other work belongs to ([dropDacWork]).
    // The row stays dimmed until the model is in [FiioReset.VERIFIED] / [WalkPlayReset.VERIFIED] or the build is a review build (Proven): the frames are from the vendors' web tools,
    // not a capture, and what they erase is unknown. An open but unproven reset keeps the "not verified" wording.

    /** The reset sheet's state; [ResetUi.Idle] = no sheet. */
    var resetUi by mutableStateOf<ResetUi>(ResetUi.Idle)
        private set
    private var resetJob: Job? = null
    /** USB detach and attach broadcasts of a DAC seen so far: the FiiO reset waits for a real unplug and plug-in by comparing them with the values taken when the frame went out. */
    @Volatile private var dacDetaches = 0
    @Volatile private var dacAttaches = 0
    private var replugMarkDetaches = 0
    private var replugMarkAttaches = 0
    /** The DAC was unplugged and plugged back in since the FiiO reset frame went out. */
    private var resetReplugged = false
    /** [awaitReplug] is waiting; cleared by [stopWaitingForReplug]. */
    @Volatile private var replugWaitOpen = false
    private var resetFlow: FiioResetFlow? = null
    private var walkPlayResetFlow: WalkPlayResetFlow? = null
    private var resetFile: File? = null
    /** The DAC the reset runs on: while it is off the bus [protocol] falls back to the Micro, so the flow keeps its own. */
    private var resetTarget: DeviceTarget? = null
    private val resetRunning: Boolean get() = resetJob?.isActive == true

    /** The DAC SETTINGS page shows a FACTORY RESET row: the DAC (or the preview) is a Melody, Tiny A, KA15, or a WalkPlay Micro, Echo-A or TRN. */
    val resetOffered: Boolean get() = dacTarget?.let { FiioReset.inApp(it.fiio) || it.walkplayProfile?.let(WalkPlayReset::offered) == true } == true
    /** The row works: the reset passed on hardware ([FiioReset.VERIFIED], [WalkPlayReset.VERIFIED]) or this is a review build, and this is a DAC, not the preview. */
    val resetEnabled: Boolean get() = !dacIsPreview && dacTarget?.let { FiioReset.enabledInApp(it.fiio) || it.walkplayProfile?.let(WalkPlayReset::enabled) == true } == true
    /** The reset passed on hardware: an open reset that is not proven says so on its row. */
    val resetProven: Boolean get() = dacTarget?.let { FiioReset.proven(it.fiio) || it.walkplayProfile?.let(WalkPlayReset::proven) == true } == true
    /** The sheet is about a WalkPlay DAC's reset (its own wording: the sequence is the WalkPlay web tool's). */
    val resetIsWalkPlay: Boolean get() = (resetTarget ?: dacTarget)?.walkplayProfile != null

    /** The row was tapped: the confirmation sheet opens (nothing is read or sent yet). */
    fun askFactoryReset() {
        if (!resetEnabled || link != Link.CONNECTED || resetUi != ResetUi.Idle || resetRunning) return
        resetUi = ResetUi.Confirm
    }
    fun cancelFactoryReset() { if (resetUi == ResetUi.Confirm) resetUi = ResetUi.Idle }
    /** The sheet is closed after a result; the saved copy stays on the phone. */
    fun dismissFactoryReset() {
        if (resetRunning) return
        resetUi = ResetUi.Idle
        resetFlow = null
        walkPlayResetFlow = null
        resetFile = null
        resetTarget = null
    }

    private fun resetValueText(s: DacSetting?, v: Int): String = when {
        s == null -> v.toString()
        s.kind == SettingKind.TOGGLE || s.kind == SettingKind.CHOICE -> s.options.getOrNull(v) ?: v.toString()
        s.scale > 1 -> "${v / s.scale.toDouble()}${if (s.unit.isEmpty()) "" else " " + s.unit}"
        else -> "$v${if (s.unit.isEmpty()) "" else " " + s.unit}"
    }

    /** The follower and the scan are stopped and nothing else holds the DAC; false = Contour is still working on it. */
    private suspend fun takeDacForReset(): Boolean {
        if (following) followJob?.join()
        if (scanning) {
            scanStop = true
            UsbLog.line("scan: stopped for a reset")
            scanJob?.join()
        }
        var waits = 0
        while ((busy || scanning || following || connecting) && waits++ < 400) delay(25)
        return !(busy || scanning || following || connecting)
    }

    /** The DAC on the bus with the system's permission, polled; after the reset it may take a while, and the system may ask for a tap again. Null = it did not come back. */
    private suspend fun awaitDacBack(timeoutMs: Long): DacHandle? {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        var asked = false
        while (SystemClock.elapsedRealtime() < end) {
            val d = findDac()
            if (d != null) {
                if (permitted(d)) return d
                if (!asked) { asked = true; requestPermission() }
            }
            delay(500)
        }
        return null
    }

    /**
     * After a FiiO reset frame: waits for the owner to unplug the DAC and plug it back in, and for the DAC to be on the bus again with the system's permission. A real unplug is a detach
     * broadcast since the frame ([replugMarkDetaches]) together with an attach broadcast or the DAC seen missing from the bus; a detach alone (a KA15 can report one while it stays
     * plugged in) does not count. The 0x21 does not restart the DAC's controller, a replug does (KA15 on the Pixel, 22.10.2026: after a reset the 4th flash save hung it).
     * There is no time limit: the wait lasts while the reset sheet is open (the owner replugged after 4 min in the Melody phone run of 25.10.2026, which a 3 min limit had already ended);
     * [stopWaitingForReplug] ends it. Null = the owner stopped waiting, or the DAC did not come back on the bus after the replug.
     */
    private suspend fun awaitReplug(): DacHandle? {
        // Review builds: a simulated DAC is "unplugged and plugged back in" by itself after a moment, so the reset runs to its result.
        if (simulated != null) {
            replugWaitOpen = true
            try { delay(SIM_REPLUG_MS) } finally { replugWaitOpen = false }
            resetReplugged = true
            UsbLog.line("reset: the simulated DAC was unplugged and plugged back in")
            return findDac()
        }
        replugWaitOpen = true
        try {
            var sawMissing = false
            while (replugWaitOpen) {
                val present = findDac() != null
                if (!present) sawMissing = true
                if (FiioReset.replugSeen(replugMarkDetaches, dacDetaches, replugMarkAttaches, dacAttaches, sawMissing)) {
                    val d = awaitDacBack(RESET_BACK_MS)
                    if (d != null) { resetReplugged = true; UsbLog.line("reset: the DAC was unplugged and plugged back in"); return d }
                    return null
                }
                delay(250)
            }
            UsbLog.line("reset: stopped waiting for the unplug and plug-in")
            return null
        } finally { replugWaitOpen = false }
    }

    /** The owner leaves the wait for the unplug and plug-in: the reset ends as it did when the wait ran out ([ResetUi.Failed], the saved copy can still be written back). */
    fun stopWaitingForReplug() { replugWaitOpen = false }

    /** The copy as a file on the phone (app storage), checked by reading it back exactly; anything else is a failure before the reset. */
    private fun saveResetBackup(config: FiioConfig, backup: FiioResetBackup): File {
        val text = backup.toText()
        if (runCatching { FiioResetBackup.parse(text).toText() == text }.getOrDefault(false).not())
            throw IOException("The copy of the DAC cannot be written down exactly (a slot name with a space or an odd character).")
        return writeResetCopy(config.productName, backup.takenAtMs, text)
    }

    /** The WalkPlay copy as a file on the phone, checked by reading it back exactly. */
    private fun saveWalkPlayResetBackup(profile: WalkPlayProfile, backup: WalkPlayResetBackup): File {
        val text = backup.toText()
        if (runCatching { WalkPlayResetBackup.parse(text).toText() == text }.getOrDefault(false).not())
            throw IOException("The copy of the DAC cannot be written down exactly.")
        return writeResetCopy(profile.name, backup.takenAtMs, text)
    }

    private fun writeResetCopy(name: String, takenAtMs: Long, text: String): File {
        val dir = File(context.filesDir, "reset-backups").also { it.mkdirs() }
        val file = File(dir, "${name.lowercase().filter { it.isLetterOrDigit() }}-$takenAtMs.txt")
        file.writeText(text)
        if (file.readText() != text) throw IOException("The copy of the DAC could not be saved on the phone.")
        return file
    }

    /**
     * The confirmation sheet's button. Reads the DAC (every USER slot with its name, every readable setting) and keeps that copy as a file; only then does the flow hand out the
     * ticket and the one reset frame go out. Then it waits for the DAC to come back, reads it again and shows what changed ([ResetUi.Result]); the copy is written back only by
     * [restoreAfterFactoryReset]. A failure before the frame says so and sends nothing.
     */
    fun confirmFactoryReset() {
        protocol.walkplayProfile?.let { return confirmWalkPlayReset(it) }
        val config = protocol.fiio
        if (resetUi != ResetUi.Confirm || config == null || !FiioReset.enabledInApp(config) || dacIsPreview || link != Link.CONNECTED || resetRunning) return
        resetUi = ResetUi.Running(RESET_STEP_COPY)
        val flow = FiioResetFlow(config)
        val target = protocol
        resetFlow = flow
        walkPlayResetFlow = null
        resetFile = null
        resetTarget = target
        resetJob = scope.launch {
            var held = false
            fun sentOut() = flow.step.ordinal >= FiioResetFlow.Step.SENT.ordinal
            try {
                if (!takeDacForReset()) throw IOException("Contour is still working on the DAC. Nothing was sent.")
                val d = findDac() ?: throw IOException("NO DAC. Nothing was sent.")
                if (!permitted(d)) throw IOException("NO PERMISSION. Nothing was sent.")
                busy = true
                held = true
                val taken = client.captureForReset(d, System.currentTimeMillis())
                resetFile = saveResetBackup(config, taken)
                flow.begin(taken)
                resetUi = ResetUi.Running(RESET_STEP_SEND)
                val ticket = flow.confirm(true)
                resetReplugged = false
                replugMarkDetaches = dacDetaches
                replugMarkAttaches = dacAttaches
                val sent: FiioResetSent = client.factoryReset(d, ticket)
                flow.sent()
                UsbLog.line("reset: frame sent${sent.note?.let { ", $it" }.orEmpty()}")
                // None of the FiiO resets is proven, and the controller state after 0x21 is the one difference from a normal session: the DAC is read and written again only after a replug.
                resetUi = ResetUi.Running(ResetUi.REPLUG_STEP)
                awaitReplug() ?: throw IOException("The DAC was not unplugged and plugged back in. Unplug it, plug it in again, then write the saved copy back.")
                resetUi = ResetUi.Running(RESET_STEP_WAIT)
                delay(FiioReset.SETTLE_MS)
                var after: FiioResetBackup? = null
                var lastError: String? = null
                for (attempt in 0 until 3) {
                    if (after != null) break
                    val back = awaitDacBack(if (attempt == 0) RESET_BACK_MS else RESET_BACK_MS / 2) ?: continue
                    resetUi = ResetUi.Running(RESET_STEP_READ)
                    try {
                        after = client.captureForReset(back, System.currentTimeMillis())
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        lastError = e.message
                        UsbLog.line("reset: read after the reset failed: ${e.message}")
                        delay(2000)
                    }
                }
                val read = after ?: throw IOException(if (Proven.channel == BuildChannel.PERF) "The DAC could not be read after the reset (${lastError ?: "it did not come back"})." else "The DAC could not be read after the reset. Unplug it, plug it back in and read it again.")
                val changes = flow.compared(read, { id -> target.settings.firstOrNull { s -> s.id == id }?.label ?: id },
                    { id, v -> resetValueText(target.settings.firstOrNull { s -> s.id == id }, v) })
                UsbLog.line("reset: ${changes.size} change(s) against the copy")
                resetUi = ResetUi.Result(changes, sent.note, requireNotNull(resetFile).name)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                UsbLog.line("reset failed: ${e.message}")
                val sent = sentOut()
                resetUi = ResetUi.Failed(FailureText.of(e.message ?: e.javaClass.simpleName), sent, sent && flow.backup != null, resetFile?.name)
            } finally {
                if (held) busy = false
            }
            if (sentOut() && resetReplugged) reloadAfterReset() // no read of a DAC that was not unplugged and plugged back in
        }
    }

    /**
     * The WalkPlay counterpart of [confirmFactoryReset] (Protocol Micro, Echo-A, TRN): the same steps, [WalkPlayResetFlow] and the session's own sequence. The copy is every band, the
     * preamp, the slot byte and every setting the DAC answers; the DAC may restart after the sequence ([WalkPlayReset.SETTLE_MS]).
     */
    private fun confirmWalkPlayReset(profile: WalkPlayProfile) {
        if (resetUi != ResetUi.Confirm || !WalkPlayReset.enabled(profile) || dacIsPreview || link != Link.CONNECTED || resetRunning) return
        resetUi = ResetUi.Running(RESET_STEP_COPY)
        val flow = WalkPlayResetFlow(profile)
        val target = protocol
        walkPlayResetFlow = flow
        resetFlow = null
        resetFile = null
        resetTarget = target
        resetJob = scope.launch {
            var held = false
            fun sentOut() = flow.step.ordinal >= WalkPlayResetFlow.Step.SENT.ordinal
            try {
                if (!takeDacForReset()) throw IOException("Contour is still working on the DAC. Nothing was sent.")
                val d = findDac() ?: throw IOException("NO DAC. Nothing was sent.")
                if (!permitted(d)) throw IOException("NO PERMISSION. Nothing was sent.")
                busy = true
                held = true
                val taken = client.captureWalkPlayForReset(d, System.currentTimeMillis())
                resetFile = saveWalkPlayResetBackup(profile, taken)
                flow.begin(taken)
                resetUi = ResetUi.Running(RESET_STEP_SEND)
                val ticket = flow.confirm(true)
                val sent: WalkPlayResetSent = client.walkPlayFactoryReset(d, ticket)
                flow.sent()
                UsbLog.line("reset: ${sent.steps} frame(s) sent${sent.note?.let { ", $it" }.orEmpty()}")
                resetUi = ResetUi.Running(RESET_STEP_WAIT)
                delay(WalkPlayReset.SETTLE_MS)
                var after: WalkPlayResetBackup? = null
                var lastError: String? = null
                for (attempt in 0 until 3) {
                    if (after != null) break
                    val back = awaitDacBack(if (attempt == 0) RESET_BACK_MS else RESET_BACK_MS / 2) ?: continue
                    resetUi = ResetUi.Running(RESET_STEP_READ)
                    try {
                        after = client.captureWalkPlayForReset(back, System.currentTimeMillis())
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        lastError = e.message
                        UsbLog.line("reset: read after the reset failed: ${e.message}")
                        delay(2000)
                    }
                }
                val read = after ?: throw IOException(if (Proven.channel == BuildChannel.PERF) "The DAC could not be read after the reset (${lastError ?: "it did not come back"})." else "The DAC could not be read after the reset. Unplug it, plug it back in and read it again.")
                val rows = WalkPlaySettings.of(profile)
                val changes = flow.compared(read, { id -> rows.firstOrNull { s -> s.id == id }?.label ?: id },
                    { id, v -> resetValueText(rows.firstOrNull { s -> s.id == id }, v) })
                UsbLog.line("reset: ${changes.size} change(s) against the copy")
                resetUi = ResetUi.Result(changes, sent.note, requireNotNull(resetFile).name)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                UsbLog.line("reset failed: ${e.message}")
                val sent = sentOut()
                resetUi = ResetUi.Failed(FailureText.of(e.message ?: e.javaClass.simpleName), sent, sent && flow.backup != null, resetFile?.name)
            } finally {
                if (held) busy = false
            }
            if (sentOut()) reloadAfterReset()
        }
    }

    /** The WalkPlay counterpart of [restoreAfterFactoryReset]: the copy goes back through the session's ordinary write; the gain mode and the balance are read only for the app, so they are listed, not written. */
    private fun restoreWalkPlayAfterReset(flow: WalkPlayResetFlow) {
        val backup = flow.backup
        val profile = resetTarget?.walkplayProfile
        val state = resetUi
        val allowed = state is ResetUi.Result || (state is ResetUi.Failed && state.canRestore)
        if (backup == null || profile == null || !allowed || !WalkPlayReset.enabled(profile) || resetRunning) return
        val file = resetFile?.name
        resetUi = ResetUi.Restoring
        resetJob = scope.launch {
            var held = false
            try {
                val d = awaitDacBack(RESET_BACK_MS) ?: throw IOException("The DAC is not connected. Plug it in and try again; the copy is kept.")
                if (!takeDacForReset()) throw IOException("Contour is still working on the DAC. Try again in a moment; the copy is kept.")
                busy = true
                held = true
                val r = client.restoreWalkPlayAfterReset(d, backup)
                if (flow.step == WalkPlayResetFlow.Step.COMPARED) flow.restored()
                UsbLog.line("reset: restore ${if (r.ok) "OK" else "NOT FULLY READ BACK"}")
                resetUi = ResetUi.Restored(r.lines, r.ok)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                UsbLog.line("restore failed: ${e.message}")
                resetUi = ResetUi.Failed(FailureText.of(e.message ?: e.javaClass.simpleName), true, true, file)
            } finally {
                if (held) busy = false
            }
            reloadAfterReset()
        }
    }

    /** The DAC holds something else now (reset or restore): the next read opens its content in the editor again, scans the slots and re-reads the settings. */
    private fun reloadAfterReset() {
        if (link != Link.CONNECTED) return
        openPending = true
        read()
        readSettings()
    }

    /** The one action after a reset: writes the saved copy back (slot names, slot EQs, settings, the slot the DAC was on), each read back as saved. */
    fun restoreAfterFactoryReset() {
        walkPlayResetFlow?.let { return restoreWalkPlayAfterReset(it) }
        val flow = resetFlow
        val backup = flow?.backup
        val config = resetTarget?.fiio
        val state = resetUi
        val allowed = state is ResetUi.Result || (state is ResetUi.Failed && state.canRestore)
        if (flow == null || backup == null || config == null || !allowed || !FiioReset.enabledInApp(config) || resetRunning) return
        val file = resetFile?.name
        resetUi = ResetUi.Restoring
        resetJob = scope.launch {
            var held = false
            try {
                // A reset whose replug did not happen (the wait ran out) gets it now: the saved copy is not written to a controller that 0x21 left running.
                if (!resetReplugged) {
                    resetUi = ResetUi.Running(ResetUi.REPLUG_STEP)
                    awaitReplug() ?: throw IOException("The DAC was not unplugged and plugged back in. Unplug it, plug it in again, then write the saved copy back; the copy is kept.")
                    resetUi = ResetUi.Restoring
                }
                val d = awaitDacBack(RESET_BACK_MS) ?: throw IOException("The DAC is not connected. Plug it in and try again; the copy is kept.")
                if (!takeDacForReset()) throw IOException("Contour is still working on the DAC. Try again in a moment; the copy is kept.")
                busy = true
                held = true
                val r = client.restoreAfterReset(d, backup)
                if (flow.step == FiioResetFlow.Step.COMPARED) flow.restored()
                UsbLog.line("reset: restore ${if (r.ok) "OK" else "NOT FULLY READ BACK"}")
                resetUi = ResetUi.Restored(r.lines, r.ok)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                UsbLog.line("restore failed: ${e.message}")
                resetUi = ResetUi.Failed(FailureText.of(e.message ?: e.javaClass.simpleName), true, true, file)
            } finally {
                if (held) busy = false
            }
            reloadAfterReset()
        }
    }

    companion object {
        /** The start of the reason of a send that ended at SAVE and could not be proven in time ([awaitSave]): the DAC may hold the EQ, it was not read back. */
        const val NOT_VERIFIED = "NOT CONFIRMED"
        private const val RESET_STEP_COPY = "READING THE DAC AND SAVING A COPY"
        private const val RESET_STEP_SEND = "SENDING THE RESET"
        private const val RESET_STEP_WAIT = "WAITING FOR THE DAC TO COME BACK"
        private const val RESET_STEP_READ = "READING THE DAC AFTER THE RESET"
        /** How long the DAC has after a reset to come back on the bus (the next two tries get half of it each). */
        private const val RESET_BACK_MS = 60_000L
        /** How long SAVING TO DAC stays after a transfer ends, so the fill is seen to reach the end. */
        private const val SETTLE_MS = 250L
        /** A simulated DAC (`--es simulate`) takes this long to be "plugged back in" after a FiiO reset. */
        private const val SIM_REPLUG_MS = 2_500L
        /** An Allegro write ended at SAVE: how long the DAC has to come back and be read, how often a read is tried, and when a read counts as proof although the DAC was never seen leaving. */
        private const val VERIFY_TIMEOUT_MS = 45_000L
        /** How long the system's USB permission dialog after SAVE may stay open before the wait gives up (it does not count against [VERIFY_TIMEOUT_MS]). */
        private const val VERIFY_PERMISSION_MAX_MS = 180_000L
        private const val VERIFY_POLL_MS = 1_000L
        private const val SAVE_NO_DETACH_MS = 8_000L
        /** The read asked again after the DAC comes back: the first try, the gap between tries, how many, how long a busy controller is waited for, how long a read has to answer. */
        private const val REREAD_FIRST_MS = 800L
        private const val REREAD_GAP_MS = 2_500L
        private const val REREAD_ATTEMPTS = 4
        private const val REREAD_BUSY_MAX_MS = 15_000
        private const val REREAD_ANSWER_MS = 2_500L
        private const val ACTION_USB_PERMISSION = "io.github.chronosauros.contour.USB_PERMISSION"
    }
}
