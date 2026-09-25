package io.github.chronosauros.contour.model

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

/** The two pages of the pager, in their order. */
object Page {
    const val TUNE = 0
    const val LIBRARY = 1
}

/**
 * The library and the editing state: every profile (active and archived) in the saved order, the current
 * one, the selected band. Every edit replaces the profile (immutable [Profile]) and schedules a debounced
 * atomic save (400 ms); [flush] writes everything pending (onStop). A delete keeps the file until [finishDelete]
 * (snackbar gone, or the app stops), so UNDO can put the row back.
 */
class AppModel(private val store: ProfileStore, private val scope: CoroutineScope) {
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
    private val pending = HashMap<String, Job>()
    private var stateJob: Job? = null
    private val lock = Any()
    private val dirty = HashMap<String, Profile?>() // null = delete the file

    /** Deleted rows waiting for their snackbar: id -> (profile, index in [profiles], was current). */
    private val deleted = HashMap<String, Triple<Profile, Int, Boolean>>()
    private val deleteTimers = HashMap<String, Job>()

    // ---- loading ------------------------------------------------------------------------------------

    fun load() {
        val saved = store.loadState()
        val list = store.loadProfiles()
        val order = saved?.order.orEmpty()
        var sorted = list.sortedWith(compareBy({ order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.createdAt }))
        if (saved == null && sorted.isEmpty()) { // first launch: PROFILE 1 (one flat band) opens, NIGHTFALL as an example
            val now = System.currentTimeMillis()
            val first = Profile(newId(), "PROFILE 1", "", "headphones", listOf(flatBand()), null, now, now)
            val example = Profile(newId(), "NIGHTFALL", "CRINEAR NIGHTFALL", "moon", nightfallBands(), null, now, now + 1)
            store.save(first)
            store.save(example)
            sorted = listOf(first, example)
        }
        profiles.clear()
        profiles.addAll(sorted)
        currentId = saved?.lastOpen?.takeIf { id -> sorted.any { it.id == id } } ?: sorted.firstOrNull { !it.archived }?.id
        selectedBand = saved?.lastBand ?: 0
        lastSentId = saved?.lastSent
        seeded = saved?.seeded ?: true
        clampSelection()
        store.saveState(savedState())
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

    // ---- persistence ----------------------------------------------------------------------------------

    private fun savedState() = SavedState(profiles.map { it.id }, currentId, selectedBand, lastSentId, seeded)

    private var stateSeq = 0L
    private var writtenSeq = 0L

    /** Writes [s] (captured with number [seq]) unless a newer state is already on disk. Any thread. */
    private fun writeState(s: SavedState, seq: Long) = synchronized(lock) {
        if (seq < writtenSeq) return@synchronized
        store.saveState(s)
        writtenSeq = seq
    }

    private fun scheduleSave(p: Profile?, id: String) {
        synchronized(lock) { dirty[id] = p }
        pending[id]?.cancel()
        pending[id] = scope.launch(Dispatchers.IO) {
            delay(400)
            writeDirty(id)
        }
        scheduleState()
    }

    private fun writeDirty(id: String) {
        val entry = synchronized(lock) { if (dirty.containsKey(id)) dirty.remove(id) to true else null to false }
        if (!entry.second) return
        val p = entry.first
        if (p == null) store.delete(id) else store.save(p)
    }

    private fun scheduleState() {
        stateJob?.cancel()
        val s = savedState()
        val seq = ++stateSeq
        stateJob = scope.launch(Dispatchers.IO) {
            delay(400)
            writeState(s, seq)
        }
    }

    /** Writes every pending change now and removes the files of deleted rows (onStop). */
    fun flush() {
        deleted.keys.toList().forEach(::finishDelete)
        val ids = synchronized(lock) { dirty.keys.toList() }
        pending.values.forEach { it.cancel() }
        pending.clear()
        stateJob?.cancel()
        val s = savedState()
        val seq = ++stateSeq
        scope.launch(Dispatchers.IO) {
            ids.forEach(::writeDirty)
            writeState(s, seq)
        }
    }

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
        update { p -> if (auto) p.copy(preampDb = null) else p.copy(preampDb = round1(shownPreamp(p))) }
        logPreamp()
    }

    /** One log line per preamp change: what the row shows and the register a send would write (no DAC access). */
    private fun logPreamp() {
        val p = current ?: return
        android.util.Log.i("ContourPreamp", "${p.name}: auto=${p.preampDb == null} shown=${shownPreamp(p)} register=${ProtocolMicro.devicePreamp(p.bands, p.preampDb)}")
    }

    /** Manual preamp in the curve domain, clamped so the register stays in the device range. */
    fun setPreamp(db: Double) = update {
        val hs = ProtocolMicro.highShelfGainSum(it.bands)
        it.copy(preampDb = round1(db.coerceIn(ProtocolMicro.PREAMP_MIN_DB - hs, ProtocolMicro.PREAMP_MAX_DB - hs)))
    }.also { logPreamp() }

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

    /**
     * Removes the row and writes state.json without it at once; the file goes in [finishDelete], which runs
     * by itself [UNDO_MS] after the delete (the model's own timer, not the snackbar's) or earlier from the
     * snackbar / onStop. [undoDelete] puts the row back at the same index.
     */
    fun delete(id: String) {
        val i = profiles.indexOfFirst { it.id == id }
        if (i < 0) return
        val p = profiles.removeAt(i)
        val wasCurrent = currentId == id
        deleted[id] = Triple(p, i, wasCurrent)
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
        profiles.add(i.coerceAtMost(profiles.size), p)
        if (wasCurrent) {
            currentId = p.id
            selectedBand = 0
            clampSelection()
        }
        scheduleState()
    }

    /** The file goes now (no debounce), and state.json is rewritten now. Idempotent. */
    fun finishDelete(id: String) {
        deleteTimers.remove(id)?.cancel()
        deleted.remove(id) ?: return
        if (lastSentId == id) lastSentId = null
        pending.remove(id)?.cancel()
        synchronized(lock) { dirty.remove(id) }
        val s = savedState()
        val seq = ++stateSeq
        stateJob?.cancel()
        scope.launch(Dispatchers.IO) {
            store.delete(id)
            writeState(s, seq)
        }
    }

    private fun saveStateNow() {
        stateJob?.cancel()
        val s = savedState()
        val seq = ++stateSeq
        stateJob = scope.launch(Dispatchers.IO) { writeState(s, seq) }
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
