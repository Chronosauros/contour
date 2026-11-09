package io.github.chronosauros.contour.desktop.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.chronosauros.contour.core.*
import io.github.chronosauros.contour.desktop.usb.DacSnapshot
import io.github.chronosauros.contour.desktop.usb.MacDeviceController
import io.github.chronosauros.contour.desktop.usb.WriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

enum class DesktopNavPage { TUNE, LIBRARY, DAC_SETTINGS }

/** Desktop application state: profile collection, active selection, band manipulation and sending. */
class DesktopAppModel(
    val store: DesktopProfileStore,
    val controller: MacDeviceController,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    var currentPage by mutableStateOf(DesktopNavPage.TUNE)
    var showAboutDialog by mutableStateOf(false)

    val profiles = mutableStateListOf<Profile>()

    var currentProfileId by mutableStateOf<String?>(null)

    val currentProfile: Profile?
        get() = profiles.firstOrNull { it.id == currentProfileId } ?: profiles.firstOrNull()

    var selectedBandIndex by mutableIntStateOf(0)

    val currentTarget: DeviceTarget
        get() = controller.target ?: DeviceTarget.MICRO

    val maxBands: Int
        get() = currentTarget.caps.bands

    var lastSentId by mutableStateOf<String?>(null)

    val onDacProfileId: String?
        get() {
            val s = controller.snapshot ?: return null
            return profiles.firstOrNull { p -> s.matches(p) }?.id
        }

    init {
        loadAllProfiles()
    }

    fun loadAllProfiles() {
        profiles.clear()
        val loaded = store.loadAll()
        profiles.addAll(loaded)
        val state = store.loadState()
        currentProfileId = state?.lastOpen?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id
        selectedBandIndex = (state?.lastBand ?: 0).coerceIn(0, (currentProfile?.bands?.size ?: 1) - 1)
        lastSentId = state?.lastSent
    }

    private fun persist(profile: Profile) {
        val now = System.currentTimeMillis()
        val updatedProfile = profile.copy(updatedAt = now)
        val idx = profiles.indexOfFirst { it.id == profile.id }
        if (idx >= 0) {
            profiles[idx] = updatedProfile
        } else {
            profiles.add(updatedProfile)
        }
        store.saveProfile(updatedProfile)
        store.saveState(DesktopSavedState(
            order = profiles.map { it.id },
            lastOpen = currentProfileId,
            lastBand = selectedBandIndex,
            lastSent = lastSentId,
            seeded = true
        ))
    }

    fun selectProfile(id: String) {
        currentProfileId = id
        selectedBandIndex = 0
        persistState()
    }

    fun selectBand(index: Int) {
        selectedBandIndex = index.coerceIn(0, (currentProfile?.bands?.size ?: 1) - 1)
        persistState()
    }

    fun updateBand(index: Int, band: Band) {
        val p = currentProfile ?: return
        if (index !in p.bands.indices) return
        val newBands = p.bands.toMutableList()
        newBands[index] = band
        val updated = p.copy(bands = newBands)
        persist(updated)
    }

    fun updateSelectedBand(transform: (Band) -> Band) {
        val p = currentProfile ?: return
        if (selectedBandIndex !in p.bands.indices) return
        val b = p.bands[selectedBandIndex]
        updateBand(selectedBandIndex, transform(b))
    }

    fun addBand(type: FilterType = FilterType.PEAK, freq: Double = 1000.0, gain: Double = 0.0, q: Double = 1.0) {
        val p = currentProfile ?: return
        if (p.bands.size >= maxBands) return
        val newBand = Band("b${System.currentTimeMillis()}", type, freq, gain, q, true)
        val updated = p.copy(bands = p.bands + newBand)
        persist(updated)
        selectedBandIndex = updated.bands.size - 1
    }

    fun removeBand(index: Int) {
        val p = currentProfile ?: return
        if (p.bands.size <= 1 || index !in p.bands.indices) return
        val newBands = p.bands.filterIndexed { i, _ -> i != index }
        val updated = p.copy(bands = newBands)
        persist(updated)
        selectedBandIndex = selectedBandIndex.coerceIn(0, updated.bands.size - 1)
    }

    fun setPreamp(db: Double?) {
        val p = currentProfile ?: return
        val updated = p.copy(preampDb = db)
        persist(updated)
    }

    fun calculateAutoPreamp(): Double {
        val p = currentProfile ?: return 0.0
        return Preamp.auto(p.bands).toDouble()
    }

    fun toggleAutoPreamp() {
        val p = currentProfile ?: return
        if (p.preampDb == null) {
            setPreamp(calculateAutoPreamp())
        } else {
            setPreamp(null)
        }
    }

    fun createProfile(name: String): Profile {
        val now = System.currentTimeMillis()
        val defaultBands = (0 until minOf(8, maxBands)).mapIndexed { i, _ ->
            val f = doubleArrayOf(32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0).getOrElse(i) { 1000.0 }
            Band("b$i", FilterType.PEAK, f, 0.0, 1.0, true)
        }
        val p = Profile(
            id = UUID.randomUUID().toString().take(8),
            name = name.ifBlank { "NEW PROFILE" },
            bands = defaultBands,
            preampDb = 0.0,
            createdAt = now,
            updatedAt = now
        )
        profiles.add(p)
        currentProfileId = p.id
        selectedBandIndex = 0
        persist(p)
        return p
    }

    fun duplicateProfile(source: Profile): Profile {
        val now = System.currentTimeMillis()
        val p = source.copy(
            id = UUID.randomUUID().toString().take(8),
            name = "${source.name} (Copy)",
            createdAt = now,
            updatedAt = now
        )
        profiles.add(p)
        currentProfileId = p.id
        selectedBandIndex = 0
        persist(p)
        return p
    }

    fun renameProfile(id: String, newName: String) {
        val p = profiles.firstOrNull { it.id == id } ?: return
        val updated = p.copy(name = newName.trim().ifBlank { p.name })
        persist(updated)
    }

    fun deleteProfile(id: String) {
        if (profiles.size <= 1) return
        val p = profiles.firstOrNull { it.id == id } ?: return
        profiles.remove(p)
        store.deleteProfile(id)
        if (currentProfileId == id) {
            currentProfileId = profiles.firstOrNull()?.id
            selectedBandIndex = 0
        }
        persistState()
    }

    fun importFromDac(): Profile? {
        val s = controller.snapshot ?: return null
        val imported = s.importExact()
        if (imported !is DacImport.Ready) return null
        val eq = imported.eq
        val now = System.currentTimeMillis()
        val p = Profile(
            id = UUID.randomUUID().toString().take(8),
            name = "${currentTarget.caps.name} READ",
            subtitle = "Imported from DAC memory",
            bands = eq.bands,
            preampDb = eq.preampDb,
            createdAt = now,
            updatedAt = now
        )
        profiles.add(p)
        currentProfileId = p.id
        selectedBandIndex = 0
        persist(p)
        return p
    }

    fun importApoText(text: String, name: String = "IMPORTED"): Profile? {
        val imported = ApoText.parse(text)
        if (imported.bands.isEmpty()) return null
        val now = System.currentTimeMillis()
        val p = Profile(
            id = UUID.randomUUID().toString().take(8),
            name = name,
            subtitle = "Imported AutoEQ / APO",
            bands = imported.bands.take(maxBands),
            preampDb = imported.preampDb,
            createdAt = now,
            updatedAt = now
        )
        profiles.add(p)
        currentProfileId = p.id
        selectedBandIndex = 0
        persist(p)
        return p
    }

    fun exportApoText(profile: Profile): String = ApoText.formatProfile(profile)

    fun sendCurrentToDac(slot: Int? = null, onProgress: (Float) -> Unit = {}) {
        val p = currentProfile ?: return
        scope.launch {
            val res = controller.sendProfile(p, slot, onProgress)
            if (res != null && res.verified) {
                lastSentId = p.id
                persistState()
            }
        }
    }

    private fun persistState() {
        store.saveState(DesktopSavedState(
            order = profiles.map { it.id },
            lastOpen = currentProfileId,
            lastBand = selectedBandIndex,
            lastSent = lastSentId,
            seeded = true
        ))
    }
}
