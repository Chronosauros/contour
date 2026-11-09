package io.github.chronosauros.contour.desktop.usb

import io.github.chronosauros.contour.core.*
import io.github.chronosauros.contour.core.native.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

data class DacSnapshot(
    val firmware: String,
    val slot: Int,
    val bands: List<WalkPlay.DeviceBand>,
    val preampDb: Int?,
    val readMs: Long,
    val protocol: DeviceProtocol? = DeviceProtocol.MICRO,
    val nativeState: NativeState? = null,
    val target: DeviceTarget = DeviceTarget.of(requireNotNull(protocol)),
    val familyState: FamilyState? = null,
) {
    val shownPreamp: Double? get() = if (familyState != null) familyState.preampDb else if (nativeState != null) nativeState.preamp else preampDb?.toDouble()

    fun matches(profile: Profile): Boolean = familyState?.matches(profile) ?: nativeState?.matches(profile)
        ?: requireNotNull(protocol).matches(profile, bands.map { it.registers }, requireNotNull(preampDb))

    fun importExact(): DacImport = familyState?.let { s ->
        runCatching<DacImport> { DacImport.Ready(s.importExact()) }
            .getOrElse { DacImport.Rejected(listOf(it.message ?: "The DAC state cannot be imported exactly")) }
    } ?: nativeState?.let { s ->
        runCatching<DacImport> { DacImport.Ready(s.importExact()) }
            .getOrElse { DacImport.Rejected(listOf(it.message ?: "Native state cannot be imported exactly")) }
    } ?: requireNotNull(protocol).importExact(bands, requireNotNull(preampDb))
}

data class WriteResult(
    val verified: Boolean,
    val mismatches: List<String>,
    val writeMs: Long,
    val readBackMs: Long,
    val readBack: DacSnapshot?,
    val previousIndex: Int? = null,
)

/** Unified macOS USB HID client for WalkPlay, FiiO, Moondrop and KT Micro protocols. */
class MacDacClient(
    private val guardFor: (MacDacHandle) -> (() -> Unit) = { {} }
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "contour-macos-usb") }
    private val usb = executor.asCoroutineDispatcher()
    private val hidLock = Mutex()

    fun shutdown() = executor.shutdown()

    private suspend fun <T> withDevice(device: MacDacHandle, block: (DesktopHidIo) -> T): T = hidLock.withLock {
        withContext(usb) {
            val guard = guardFor(device)
            guard()
            MacHidIo.open(device, guard).use { io ->
                block(io).also { guard() }
            }
        }
    }

    private fun target(device: MacDacHandle): DeviceTarget = device.target

    private fun protocol(device: MacDacHandle): DeviceProtocol =
        target(device).walkplay ?: throw IOException("WalkPlay-only operation unavailable on ${target(device).caps.name}")

    private fun nativeSession(device: MacDacHandle, t: DesktopHidIo, target: DeviceTarget) = NativeSession(target, object : NativePort {
        override val deviceKey = device.key
        override fun guard() = t.checkSession()
        override fun pause(ms: Long) { t.checkSession(); Thread.sleep(ms); t.checkSession() }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) = t.sendNative(id, kind, payload)
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean) =
            t.requestNative(id, payload, matches)
    })

    private fun nativeSnapshot(target: DeviceTarget, state: NativeState, ms: Long) =
        DacSnapshot("native", state.slot, emptyList(), null, ms, protocol = null, nativeState = state, target = target)

    suspend fun readDevice(device: MacDacHandle): DacSnapshot = withDevice(device) { t ->
        val target = target(device)
        if (target.walkplay != null) {
            readWalkPlay(device, t, target)
        } else if (target.fiio == null) {
            readFamily(device, t, target)
        } else {
            val start = System.currentTimeMillis()
            val state = nativeSession(device, t, target).read()
            t.checkSession()
            val ms = System.currentTimeMillis() - start
            nativeSnapshot(target, state, ms)
        }
    }

    private fun readWalkPlay(device: MacDacHandle, t: DesktopHidIo, target: DeviceTarget): DacSnapshot {
        val p = requireNotNull(target.walkplay)
        val t0 = System.currentTimeMillis()
        val s = walkPlaySession(device, t, target).first.read()
        val ms = System.currentTimeMillis() - t0
        return snapshotOf(p, s, ms)
    }

    private fun readFamily(device: MacDacHandle, t: DesktopHidIo, target: DeviceTarget): DacSnapshot {
        val port = MacWalkPlayPort(t, device)
        val start = System.currentTimeMillis()
        val dawn = target.moondrop
        val allegro = target.ktMicro
        val state: FamilyState = when {
            dawn != null -> FamilyState.Dawn(dawn, MoondropSession(dawn, port).read(withPreGain = true))
            allegro != null -> KtMicroSession(allegro, port).let { s -> FamilyState.Allegro(allegro, s.read(), s.readGlobalGain()) }
            else -> FamilyDevices.readEchoA(port)
        }
        t.checkSession()
        val ms = System.currentTimeMillis() - start
        return DacSnapshot((state as? FamilyState.EchoA)?.snapshot?.firmware ?: "native", state.slot, emptyList(), null, ms, protocol = null, target = target, familyState = state)
    }

    private fun snapshotOf(p: DeviceProtocol, s: WalkPlaySnapshot, ms: Long) =
        DacSnapshot(s.firmware, s.slot, s.bands, s.preampDb, ms, p)

    suspend fun writeProfile(
        device: MacDacHandle,
        profile: Profile,
        targetSlot: Int? = null,
        onProgress: ((Float) -> Unit)? = null
    ): WriteResult {
        val target = target(device)
        if (target.readOnly) throw IOException(DeviceTarget.READ_ONLY_ISSUE.format(target.caps.name))
        target.echo?.let { return writeEcho(device, profile, target, it, onProgress) }
        target.moondrop?.let { return writeDawn(device, profile, target, it, onProgress) }
        target.ktMicro?.let { return writeAllegro(device, profile, target, it, onProgress) }
        if (!target.native) return when (val plan = requireNotNull(target.walkplay).plan(profile)) {
            is DevicePlan.Rejected -> throw IOException(plan.issues.joinToString("; "))
            is DevicePlan.Ready -> writePlan(device, plan, onProgress)
        }
        target.issues(profile).takeIf { it.isNotEmpty() }?.let { throw IOException(it.joinToString("; ")) }
        return withDevice(device) { t ->
            val start = System.currentTimeMillis()
            val r = nativeSession(device, t, target).write(profile, explicitHold = true, targetSlot = targetSlot,
                onProgress = onProgress?.let { report -> { done, total -> report(done.toFloat() / total) } })
            t.checkSession()
            WriteResult(r.verified, listOfNotNull(r.reason), System.currentTimeMillis() - start, 0,
                nativeSnapshot(target, requireNotNull(r.readback), 0))
        }
    }

    private suspend fun writePlan(device: MacDacHandle, plan: DevicePlan.Ready, onProgress: ((Float) -> Unit)? = null): WriteResult = withDevice(device) { t ->
        val p = protocol(device)
        var steps: Steps? = null
        val (session, port) = walkPlaySession(device, t, target(device)) { steps?.tick() }
        steps = Steps(session.plannedFrames(plan), onProgress)
        walkPlayWrite(session, port, plan, p)
    }

    private fun walkPlayWrite(session: WalkPlaySession, port: MacWalkPlayPort, plan: DevicePlan.Ready, p: DeviceProtocol): WriteResult {
        val r = session.writePlan(plan, explicitHold = true)
        val back = requireNotNull(r.readback)
        val now = System.currentTimeMillis()
        val writeMs = port.lastSendEndMs - port.firstSendMs
        val readMs = now - port.lastSendEndMs
        val mismatches = session.mismatches(plan, back, requireNotNull(session.lastSlot))
        val verified = mismatches.isEmpty()
        return WriteResult(verified, mismatches, writeMs, readMs, snapshotOf(p, back, readMs))
    }

    private class Steps(private val total: Int, private val report: ((Float) -> Unit)?) {
        private var done = 0
        fun tick() { done++; report?.invoke(done.toFloat() / maxOf(total, done + 1)) }
    }

    private fun walkPlaySession(device: MacDacHandle, t: DesktopHidIo, target: DeviceTarget, tick: () -> Unit = {}): Pair<WalkPlaySession, MacWalkPlayPort> {
        val port = MacWalkPlayPort(t, device, tick)
        return WalkPlaySession(requireNotNull(target.walkplayProfile), port) to port
    }

    private suspend fun writeDawn(device: MacDacHandle, profile: Profile, target: DeviceTarget, model: MoondropCatalog.Model, onProgress: ((Float) -> Unit)?): WriteResult {
        target.issues(profile).takeIf { it.isNotEmpty() }?.let { throw IOException(it.joinToString("; ")) }
        val planned = MoondropCodec.planFor(model, profile.bands, profile.effectivePreampDb())
        if (planned.issues.isNotEmpty()) throw IOException(planned.issues.joinToString("; "))
        return withDevice(device) { t ->
            val start = System.currentTimeMillis()
            val steps = Steps(2 * 2 * MoondropCodec.readQueries(model).size + planned.bands.size + 9, onProgress)
            val session = MoondropSession(model, MacWalkPlayPort(t, device) { steps.tick() })
            val r = session.write(planned.bands, planned.preGainDb, explicitHold = true)
            t.checkSession()
            val back = requireNotNull(r.readback)
            val previous = session.lastBefore?.activeIndex?.takeIf { it != back.activeIndex }
            val ms = System.currentTimeMillis() - start
            WriteResult(r.verified, listOfNotNull(r.reason), ms, 0,
                DacSnapshot("dawn", back.activeIndex, emptyList(), null, 0, null, target = target, familyState = FamilyState.Dawn(model, back)),
                previousIndex = previous)
        }
    }

    private suspend fun writeEcho(device: MacDacHandle, profile: Profile, target: DeviceTarget, echo: WalkPlayProfile, onProgress: ((Float) -> Unit)?): WriteResult {
        target.issues(profile).takeIf { it.isNotEmpty() }?.let { throw IOException(it.joinToString("; ")) }
        return withDevice(device) { t ->
            val start = System.currentTimeMillis()
            var steps: Steps? = null
            val session = WalkPlaySession(echo, MacWalkPlayPort(t, device) { steps?.tick() })
            val plan = when (val planned = session.plan(profile)) {
                is DevicePlan.Rejected -> throw IOException(planned.issues.joinToString("; "))
                is DevicePlan.Ready -> planned
            }
            steps = Steps(session.plannedFrames(plan), onProgress)
            val r = session.writePlan(plan, explicitHold = true)
            t.checkSession()
            val back = requireNotNull(r.readback)
            val ms = System.currentTimeMillis() - start
            WriteResult(r.verified, listOfNotNull(r.reason), ms, 0,
                DacSnapshot(back.firmware, back.slot, back.bands, back.preampDb, 0, null, target = target, familyState = FamilyState.EchoA(echo, back)))
        }
    }

    private suspend fun writeAllegro(device: MacDacHandle, profile: Profile, target: DeviceTarget, model: KtMicroCatalog.Model, onProgress: ((Float) -> Unit)?): WriteResult {
        target.issues(profile).takeIf { it.isNotEmpty() }?.let { throw IOException(it.joinToString("; ")) }
        return withDevice(device) { t ->
            val start = System.currentTimeMillis()
            val steps = Steps(2 * KtMicroCodec.readQueries(model).size + 2 * model.bandCount + 6, onProgress)
            val session = KtMicroSession(model, MacWalkPlayPort(t, device) { steps.tick() })
            session.write(profile.copy(preampDb = 0.0), explicitHold = true, eqEnabled = null)
            val ms = System.currentTimeMillis() - start
            WriteResult(true, emptyList(), ms, 0, null)
        }
    }

    suspend fun writeTemporaryBands(device: MacDacHandle, bands: List<WalkPlay.BandWrite>, slot: Int) = withDevice(device) { t ->
        walkPlaySession(device, t, target(device)).first.writeTemporary(bands, slot, explicitHold = true)
    }

    suspend fun readSettings(device: MacDacHandle): List<SettingReading> = withDevice(device) { t ->
        val target = target(device)
        require(target.settings.isNotEmpty()) { "${target.caps.name} has no settings list" }
        target.walkplayProfile?.let { profile ->
            val r = WalkPlaySession(profile, MacWalkPlayPort(t, device)).readSettings(target.settings).map { SettingReading(it.id, it.value, null) }
            t.checkSession()
            return@withDevice r
        }
        if (target.fiio == null) throw IOException("No settings reader for ${target.caps.name} yet")
        val r = nativeSession(device, t, target).readSettings { println("[FiiO Settings] $it") }
        t.checkSession()
        r
    }

    suspend fun writeWalkPlaySetting(device: MacDacHandle, id: String, value: Int): SettingWriteOutcome = withDevice(device) { t ->
        val target = target(device)
        val profile = target.walkplayProfile ?: throw IOException("No WalkPlay settings writer for ${target.caps.name}")
        val row = target.settings.firstOrNull { it.id == id }?.takeIf { it.writeEnabled } ?: throw IOException("${target.caps.name}: \"$id\" is not written")
        val r = WalkPlaySession(profile, MacWalkPlayPort(t, device)).writeSetting(id, value, explicitHold = true, save = id == WalkPlaySettings.MIC)
        t.checkSession()
        when {
            r.verified -> SettingWriteOutcome.Verified
            r.readback != null -> SettingWriteOutcome.Mismatch(r.readback!!)
            else -> SettingWriteOutcome.NoReply
        }
    }

    suspend fun selectSlot(device: MacDacHandle, slot: Int): Int = withDevice(device) { t ->
        val confirmed = nativeSession(device, t, target(device)).selectSlot(slot, explicitUserAction = true)
        t.checkSession()
        confirmed
    }

    suspend fun readSlot(device: MacDacHandle, slot: Int): DacSnapshot = withDevice(device) { t ->
        val target = target(device)
        val start = System.currentTimeMillis()
        val state = nativeSession(device, t, target).readSlot(slot, explicitUserAction = true)
        t.checkSession()
        nativeSnapshot(target, state, System.currentTimeMillis() - start)
    }

    suspend fun renameSlot(device: MacDacHandle, slot: Int, name: String): String = withDevice(device) { t ->
        nativeSession(device, t, target(device)).renameSlot(slot, name, explicitUserAction = true).also { t.checkSession() }
    }
}
