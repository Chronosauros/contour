package io.github.chronosauros.contour.model

import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.ProtocolMicro
import java.util.UUID
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel

/** The two pages of the pager, in their order. */
object Page {
    const val TUNE = 0
    const val LIBRARY = 1
}

/**
 * The library and the editing state: every profile (active and archived) in the saved order, the current
 * one, the selected band. Every edit replaces the profile (immutable [Profile]) and schedules a debounced
 * atomic save (400 ms); [flush] queues everything pending (onStop). A delete keeps the file until [finishDelete]
 * (snackbar gone, or the app stops), so UNDO can put the row back.
 */
class AppModel(private val store: ProfileStore, private val scope: CoroutineScope, private val beforeLoad: () -> Unit = {}) {
    val profiles = mutableStateListOf<Profile>()
    var currentId by mutableStateOf<String?>(null)
        private set
    var selectedBand by mutableIntStateOf(0)
        private set
    var lastSentId by mutableStateOf<String?>(null)
        private set

    /** A page the UI should go to (set by the model or the review hooks, cleared by the pager). */
    var pageRequest by mutableStateOf<Int?>(null)

    val current: Profile? by derivedStateOf { profiles.firstOrNull { it.id == currentId } }
    val active: List<Profile> by derivedStateOf { profiles.filter { !it.archived } }
    val archived: List<Profile> by derivedStateOf { profiles.filter { it.archived } }

    private var seeded = true
    private val deletedIds = HashSet<String>()
    var loadError by mutableStateOf(false)
        private set
    var saveError by mutableStateOf(false)
        private set
    private var stateJob: Job? = null
    private val dirty = HashMap<String, Profile?>() // null = delete the file
    private data class WriteRequest(val state: SavedState, val changes: Map<String, Profile?>)
    private val writes = Channel<WriteRequest>(Channel.UNLIMITED)
    private val pendingDeletes = HashMap<String, () -> Unit>()
    var recentlyDeleted by mutableStateOf<Profile?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var deleting by mutableStateOf(false)
        private set

    /** Deleted rows waiting for their snackbar: id -> (profile, index in [profiles], was current). */
    private val deleted = HashMap<String, Triple<Profile, Int, Boolean>>()
    private val deleteTimers = HashMap<String, Job>()

    init {
        // This scope is process-owned, not Activity-owned. One channel reader serializes writes;
        // initial/retry load completes before it ever enqueues one, and the same model survives recreation.
        scope.launch(Dispatchers.IO) {
            val outstanding = HashMap<String, Profile?>()
            for (request in writes) {
                outstanding.putAll(request.changes)
                var failed = false
                try {
                    // A state.order entry is never published before its profile file exists.
                    outstanding.filterValues { it != null }.toMap().forEach { (id, p) ->
                        store.save(p!!)
                        outstanding.remove(id)
                    }
                    store.saveState(request.state)
                    // Physical deletes come only AFTER a durable tombstone, even on retries.
                    outstanding.filterValues { it == null }.keys.toList().forEach { id ->
                        store.delete(id)
                        outstanding.remove(id)
                    }
                } catch (e: Exception) {
                    Log.e(ProfileStore.TAG, "Library save failed", e)
                    failed = true
                }
                withContext(Dispatchers.Main) {
                    if (failed) saveError = true else {
                        request.changes.forEach { (id, value) -> if (dirty.containsKey(id) && dirty[id] == value) dirty.remove(id) }
                        saveError = false
                        request.state.deletedIds.forEach { id -> pendingDeletes.remove(id)?.invoke() }
                        deleting = pendingDeletes.isNotEmpty()
                    }
                }
            }
        }
    }

    // ---- loading ------------------------------------------------------------------------------------

    fun load() {
        if (!loading && !loadError) return
        loading = true
        loadError = false
        scope.launch {
            // Backup, reads and any first-run writes must stay off the main thread.
            val loaded = try {
                withContext(Dispatchers.IO) {
                    beforeLoad()
                    val saved = store.loadState()
                    var list = store.loadProfiles(saved?.deletedIds.orEmpty())
                    if (saved == null && list.size != list.map { it.id }.toSet().size)
                        throw java.io.IOException("Duplicate profile ids; library preserved")
                    if (saved == null && list.all(::isOriginalSeed) && list.size != list.map { it.name }.toSet().size)
                        throw java.io.IOException("Duplicate seed roles; library preserved")
                    if (saved == null && (list.isEmpty() || list.all(::isOriginalSeed))) {
                        // A previous launch may have written one or two seed files then failed. Keep their
                        // ids and finish only missing roles; unrelated pre-state libraries are never seeded.
                        val now = System.currentTimeMillis()
                        val seeds = listOf(
                            Profile(newId(), "PROFILE 1", "", "headphones", listOf(flatBand()), null, now, now),
                            Profile(newId(), "NIGHTFALL", "CRINEAR NIGHTFALL", "moon", nightfallBands(), null, now, now + 1),
                            Profile(newId(), "DUSK", "MOONDROP DUSK DEFAULT DSP", "sun", duskBands(), null, now, now + 2),
                        )
                        for (seed in seeds) if (list.none { it.name == seed.name }) {
                            store.save(seed)
                            list = list + seed
                        }
                        list = seeds.map { seed -> list.first { it.name == seed.name } }
                    }
                    saved to list
                }
            } catch (e: Exception) {
                Log.e(ProfileStore.TAG, "Library load failed", e)
                loadError = true
                loading = false
                return@launch
            }
            val (saved, list) = loaded
            deletedIds.clear()
            deletedIds.addAll(saved?.deletedIds.orEmpty())
            val order = saved?.order.orEmpty()
            val sorted = list.sortedWith(compareBy({ order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.createdAt }))
            profiles.clear()
            profiles.addAll(sorted)
            currentId = saved?.lastOpen?.takeIf { id -> sorted.any { it.id == id } } ?: sorted.firstOrNull { !it.archived }?.id
            selectedBand = saved?.lastBand ?: 0
            lastSentId = saved?.lastSent
            seeded = saved?.seeded ?: true
            clampSelection()
            loading = false
            // Normalize only after all referenced files have been verified/read.
            saveStateNow()
            deletedIds.forEach { id -> dirty[id] = null }
            if (deletedIds.isNotEmpty()) enqueueWrite()
        }
    }

    private fun isOriginalSeed(p: Profile): Boolean {
        if (p.archived || p.preampDb != null || p.bands.any { it.type != FilterType.PEAK || !it.enabled }) return false
        val shape = p.bands.map { listOf(it.freqHz, it.gainDb, it.q) }
        return when (p.name) {
            "PROFILE 1" -> p.subtitle == "" && p.icon == "headphones" && shape == listOf(listOf(1000.0, 0.0, 0.71))
            "NIGHTFALL" -> p.subtitle == "CRINEAR NIGHTFALL" && p.icon == "moon" && shape == listOf(listOf(6207.0, -3.0, 3.9), listOf(12450.0, -4.5, 6.05), listOf(15911.0, 3.0, 6.3))
            "DUSK" -> p.subtitle == "MOONDROP DUSK DEFAULT DSP" && p.icon == "sun" && shape == listOf(listOf(1400.0, -3.0, 0.8), listOf(5400.0, -3.0, 2.0), listOf(14000.0, -5.0, 2.0))
            else -> false
        }
    }

    fun newId(): String = UUID.randomUUID().toString().substring(0, 13)
    fun bandId(): String = "b" + UUID.randomUUID().toString().substring(0, 8)

    /** The band a fresh profile starts with: PEAK 1 kHz, 0 dB, Q 0.71 (flat, ready to drag). */
    fun flatBand() = Band(bandId(), FilterType.PEAK, 1000.0, 0.0, 0.71)

    /** The maintainer's own tuning for the CrinEar Nightfall, shipped as the example profile. */
    private fun nightfallBands() = listOf(
        Band(bandId(), FilterType.PEAK, 6207.0, -3.0, 3.9),
        Band(bandId(), FilterType.PEAK, 12450.0, -4.5, 6.05),
        Band(bandId(), FilterType.PEAK, 15911.0, 3.0, 6.3),
    )

    /** The DUSK-Default curve of Moondrop's DSP cable (values published by Crinacle), for the analog cable. */
    private fun duskBands() = listOf(
        Band(bandId(), FilterType.PEAK, 1400.0, -3.0, 0.8),
        Band(bandId(), FilterType.PEAK, 5400.0, -3.0, 2.0),
        Band(bandId(), FilterType.PEAK, 14000.0, -5.0, 2.0),
    )

    // ---- persistence ----------------------------------------------------------------------------------

    private fun savedState() = SavedState(profiles.map { it.id }.filterNot { it in pendingDeletes },
        currentId?.takeUnless { it in pendingDeletes }, selectedBand, lastSentId, seeded,
        deletedIds + pendingDeletes.keys)

    private fun enqueueWrite() {
        if (!loading && !loadError) writes.trySend(WriteRequest(savedState(), dirty.toMap()))
    }

    private fun scheduleSave(p: Profile?, id: String) {
        dirty[id] = p
        scheduleState()
    }

    private fun scheduleState() {
        stateJob?.cancel()
        stateJob = scope.launch {
            delay(400)
            enqueueWrite()
        }
    }

    /** onStop queues a snapshot in the process-owned writer; never waits for storage on main. */
    fun flush() {
        if (loading || loadError) return
        deleted.keys.toList().forEach(::finishDelete)
        stateJob?.cancel()
        enqueueWrite()
    }

    /** User-initiated single attempt; failed entries stay pending until this or the next onStop. */
    fun retrySave() { stateJob?.cancel(); enqueueWrite() }

    // ---- selection ------------------------------------------------------------------------------------

    /** Library tap: [id] becomes current and the app slides to Tune. */
    fun choose(id: String) {
        if (currentId != id) {
            currentId = id
            selectedBand = 0
            clampSelection()
        }
        pageRequest = Page.TUNE
        scheduleState()
    }

    fun selectBand(i: Int) {
        selectedBand = i
        scheduleState()
    }

    private fun clampSelection() {
        val n = current?.bands?.size ?: 0
        selectedBand = if (n == 0) 0 else selectedBand.coerceIn(0, n - 1)
    }

    fun setLastSent(id: String?) {
        lastSentId = id
        scheduleState()
    }

    fun byId(id: String?): Profile? = profiles.firstOrNull { it.id == id }

    // ---- editing --------------------------------------------------------------------------------------

    fun update(id: String? = currentId, transform: (Profile) -> Profile) {
        val i = profiles.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = profiles[i]
        val t = transform(old)
        if (t == old) return
        val next = t.copy(updatedAt = System.currentTimeMillis())
        profiles[i] = next
        scheduleSave(next, next.id)
    }

    fun setBand(index: Int, band: Band) = update { p ->
        if (index !in p.bands.indices) p else p.copy(bands = p.bands.toMutableList().also { it[index] = band })
    }

    /** A drag may finish after a profile switch, band deletion, or another edit. Transform only the live band. */
    fun transformBandIfCurrent(profileId: String, index: Int, bandId: String, transform: (Band) -> Band) {
        if (currentId != profileId) return
        update(profileId) { p ->
            if (currentId != profileId || p.bands.getOrNull(index)?.id != bandId) p
            else p.copy(bands = p.bands.toMutableList().also { it[index] = transform(it[index]) })
        }
    }

    /** Adds a PEAK band (Q 1) at [freq] / [gain], or 0 dB in the widest gap; selects it. False when full. */
    fun addBand(freq: Double? = null, gain: Double = 0.0): Boolean {
        val p = current ?: return false
        if (p.bands.size >= MAX_BANDS) return false
        val f = freq ?: widestGapMiddle(p.bands.map { it.freqHz })
        val band = Band(bandId(), FilterType.PEAK, Math.round(f.coerceIn(20.0, 20_000.0)).toDouble(), round1(gain.coerceIn(-10.0, 10.0)), 0.71)
        update { it.copy(bands = it.bands + band) }
        selectBand(p.bands.size)
        return true
    }

    fun deleteBand(index: Int) {
        update { p -> p.copy(bands = p.bands.filterIndexed { i, _ -> i != index }) }
        if (selectedBand >= index && selectedBand > 0) selectedBand--
        clampSelection()
        scheduleState()
    }

    fun toggleBypass(index: Int) = update { p ->
        p.copy(bands = p.bands.mapIndexed { i, b -> if (i == index) b.copy(enabled = !b.enabled) else b })
    }

    fun setType(type: FilterType) {
        val p = current ?: return
        val b = p.bands.getOrNull(selectedBand) ?: return
        if (b.type != type) setBand(selectedBand, b.copy(type = type))
    }

    /** AUTO off starts manual at the shown (curve-domain) value, so the device register does not change. */
    fun setPreampAuto(auto: Boolean) {
        update { p ->
            if (auto) {
                if (runCatching { shownPreamp(p.copy(preampDb = null)) }.isSuccess) p.copy(preampDb = null) else p
            } else runCatching { round1(shownPreamp(p)) }.getOrNull()?.let { p.copy(preampDb = it) } ?: p
        }
    }

    /** Manual preamp in the curve domain, clamped so the register stays in the device range. */
    fun setPreamp(db: Double) = update {
        val hs = ProtocolMicro.highShelfGainSum(it.bands)
        it.copy(preampDb = round1(db.coerceIn(ProtocolMicro.PREAMP_MIN_DB - hs, ProtocolMicro.PREAMP_MAX_DB - hs)))
    }

    fun rename(id: String, name: String) = update(id) {
        it.copy(name = name.trim().uppercase().take(NAME_MAX).ifEmpty { it.name })
    }

    fun setHeadphones(id: String, sub: String) = update(id) { it.copy(subtitle = sub.trim().uppercase().take(40)) }

    fun setIcon(id: String, icon: String) = update(id) { it.copy(icon = icon) }

    // ---- library ------------------------------------------------------------------------------------

    private fun nextDefaultName(): String {
        var n = active.size + 1
        while (profiles.any { it.name == "PROFILE $n" }) n++
        return "PROFILE $n"
    }

    /** A new profile after the last one; it becomes current. [open] = slide to Tune. */
    fun create(bands: List<Band>, preampDb: Double?, name: String? = null, sub: String = "", icon: String = "headphones", open: Boolean = true): Profile {
        val now = System.currentTimeMillis()
        val p = Profile(newId(), name ?: nextDefaultName(), sub, icon, bands.map { it.copy(id = bandId()) }, preampDb, now, now)
        profiles.add(p)
        scheduleSave(p, p.id)
        currentId = p.id
        selectedBand = 0
        if (open) pageRequest = Page.TUNE
        scheduleState()
        return p
    }

    fun duplicate(id: String) {
        val src = byId(id) ?: return
        val now = System.currentTimeMillis()
        val copy = src.copy(id = newId(), name = src.name.take(NAME_MAX - 2) + " 2", bands = src.bands.map { it.copy(id = bandId()) }, createdAt = now, updatedAt = now)
        profiles.add(profiles.indexOfFirst { it.id == id } + 1, copy)
        scheduleSave(copy, copy.id)
    }

    /** ARCHIVE (true) / RESTORE (false). The row keeps its place in the order. */
    fun setArchived(id: String, archived: Boolean) = update(id) { it.copy(archived = archived) }

    /** Keep the row visible until the tombstone is written; only then announce deletion/UNDO. */
    fun delete(id: String) {
        val i = profiles.indexOfFirst { it.id == id }
        if (i < 0 || id in pendingDeletes) return
        pendingDeletes[id] = {
            commitDelete(id)
        }
        deleting = true
        saveStateNow()
    }

    private fun commitDelete(id: String) {
        val i = profiles.indexOfFirst { it.id == id }
        if (i < 0) return
        val p = profiles.removeAt(i)
        recentlyDeleted = p
        val wasCurrent = currentId == id
        deleted[id] = Triple(p, i, wasCurrent)
        deletedIds.add(id)
        if (wasCurrent) {
            val after = profiles.drop(i).firstOrNull { it.archived == p.archived }
                ?: profiles.take(i).lastOrNull { it.archived == p.archived }
                ?: profiles.firstOrNull { !it.archived }
            currentId = after?.id
            selectedBand = 0
            clampSelection()
        }
        saveStateNow()
        deleteTimers[id]?.cancel()
        deleteTimers[id] = scope.launch {
            delay(UNDO_MS)
            finishDelete(id)
        }
    }

    fun undoDelete(id: String) {
        deleteTimers.remove(id)?.cancel()
        val (p, i, wasCurrent) = deleted.remove(id) ?: return
        if (recentlyDeleted?.id == id) recentlyDeleted = null
        deletedIds.remove(id)
        profiles.add(i.coerceAtMost(profiles.size), p)
        // A file might have been removed by a completed delete; restore it before un-tombstoning.
        dirty[id] = p
        if (wasCurrent) {
            currentId = p.id
            selectedBand = 0
            clampSelection()
        }
        scheduleState()
    }

    /** The file goes only after state.json with its tombstone is safely replaced. */
    fun finishDelete(id: String) {
        deleteTimers.remove(id)?.cancel()
        deleted.remove(id) ?: return
        if (recentlyDeleted?.id == id) recentlyDeleted = null
        if (lastSentId == id) lastSentId = null
        dirty[id] = null
        stateJob?.cancel()
        enqueueWrite()
    }

    private fun saveStateNow() {
        stateJob?.cancel()
        enqueueWrite()
    }

    companion object {
        const val MAX_BANDS = 8
        const val NAME_MAX = 18
        /** The UNDO window of a delete (the snackbar shows as long). */
        const val UNDO_MS = 5000L
    }
}

/**
 * The PREAMP row speaks in the domain of the drawn curve (like squig.link), in both modes: the device register
 * minus the HIGH SHELF gains the emulation folds out of the curve (ProtocolMicro.deviceBands). Display only -
 * what is written stays ProtocolMicro.devicePreamp.
 */
fun shownPreamp(p: Profile): Double =
    ProtocolMicro.devicePreamp(p.bands, p.preampDb) - ProtocolMicro.highShelfGainSum(p.bands)

/** Widest gap between existing band frequencies in 20 Hz - 20 kHz (log), its geometric middle; 1 kHz if none. */
fun widestGapMiddle(freqs: List<Double>): Double {
    if (freqs.isEmpty()) return 1000.0
    val pts = (listOf(20.0, 20_000.0) + freqs.map { it.coerceIn(20.0, 20_000.0) }).sorted()
    var best = 0.0
    var mid = 1000.0
    for (i in 0 until pts.lastIndex) {
        val g = ln(pts[i + 1] / pts[i])
        if (g > best) {
            best = g
            mid = sqrt(pts[i] * pts[i + 1])
        }
    }
    return mid
}
