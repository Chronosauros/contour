package io.github.chronosauros.contour.model

import androidx.compose.runtime.snapshotFlow
import io.github.chronosauros.contour.core.DacImport
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.native.FamilyState
import io.github.chronosauros.contour.usb.DacSnapshot
import io.github.chronosauros.contour.usb.DeviceController
import io.github.chronosauros.contour.usb.DeviceController.SlotContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the DAC holds drives the editor: a tap on a USER slot, the first read of a connection and FROM DAC in the new-profile
 * sheet all open the library profile for what a slot holds ([find]), and only when the library has none make one. The icon
 * never takes part in matching, and a read never adds a profile whose curve the library already has.
 */
class DacEditor(private val model: AppModel, private val device: DeviceController, private val scope: CoroutineScope) {
    init {
        device.onConnectRead = ::connectRead
        device.onScanRead = ::scanRead
        // DUSK-SP: a preset picked on the DAC page (a RAM select, read back) puts what plays on the graph, as if that profile had been sent. A background read of the same preset does not emit.
        scope.launch { snapshotFlow { (device.snapshot?.familyState as? FamilyState.Dusk)?.slot }.filterNotNull().collect { device.snapshot?.let(::presetRead) } }
    }

    /**
     * The library profile for [c], in this order: the slot's link (the profile last opened from or verified-sent to it, while
     * the slot still reads as it did then and the profile exists; otherwise the link is dropped); a profile that is not archived
     * and whose curve the slot holds (working copy or saved version), the one named like the slot first, then the most recently
     * updated; otherwise null. [savedToo] false (a DUSK-SP firmware preset, which plays its table whatever the library holds): only a working copy that equals it counts, not one that was edited since its saved version.
     */
    private fun find(c: SlotContent, savedToo: Boolean = true): String? {
        val slot = c.slot
        if (slot != null) device.linkOf(slot)?.let { (id, fingerprint) ->
            if (fingerprint == c.fingerprint && model.byId(id) != null) return id
            device.dropLink(slot)
        }
        return model.active.filter { c.holds(it) || (savedToo && c.holds(model.savedProfile(it))) }
            .sortedWith(compareByDescending<Profile> { c.sameName(it) }.thenByDescending { it.updatedAt })
            .firstOrNull()?.id
    }

    /** A new profile from the DAC state [s] (FROM DAC); a state that cannot be imported exactly shows its notice and changes nothing. */
    private fun create(c: SlotContent, s: DacSnapshot?, navigate: Boolean): String? {
        s ?: return null
        return when (val r = s.importExact()) {
            is DacImport.Ready -> {
                val name = c.name ?: c.label ?: (s.familyState as? FamilyState.Dusk)?.importName
                val p = model.create(r.eq.bands, r.eq.preampDb, name = name, sub = "FROM DAC", open = navigate)
                val n = r.eq.bands.size
                model.showOnGraph("FROM DAC${name?.let { " $it" }.orEmpty()}: $n FILTER${if (n == 1) "" else "S"}" + (r.warning?.let { " - $it" } ?: ""))
                p.id
            }
            is DacImport.Rejected -> {
                model.notice = "FROM DAC: ${r.issues.joinToString("; ")}"
                null
            }
        }
    }

    /** Puts [c] on the graph: the profile [find] gives (or a new one made from [s]) becomes current and is linked to its slot. */
    private fun open(c: SlotContent, s: DacSnapshot?, navigate: Boolean): Boolean {
        val id = find(c, savedToo = (s?.familyState as? FamilyState.Dusk)?.onCustom != false) ?: create(c, s, navigate) ?: return false
        model.choose(id, navigate)
        c.slot?.let { device.setLink(it, id, c.fingerprint) }
        return true
    }

    /** The first read of a connection: the editor opens what the DAC plays (the library may still be loading at launch). */
    private fun connectRead(s: DacSnapshot) {
        if (!model.loading) return openConnected(s)
        scope.launch {
            snapshotFlow { model.loading }.first { !it }
            device.snapshot?.let(::openConnected)
        }
    }

    /** The DUSK-SP now plays another preset (or the Custom EQ): the library profile for it becomes current, unless the one on screen already is it (a send, a read of the same state). */
    private fun presetRead(s: DacSnapshot) {
        if (model.loading || model.loadError) return // the first read of a connection waits for the library in connectRead
        val c = device.contentOf(s) ?: return
        if (model.current?.let { c.holds(it) } == true) return
        open(c, s, navigate = false)
    }

    private fun openConnected(s: DacSnapshot) {
        if (model.loadError) return
        val c = device.contentOf(s) ?: return
        // A slot DAC on a stock preset or Close EQ holds nothing the editor could open: it stays as it is.
        if (device.slotPicker && c.slot == null && !c.unknownUser) return
        open(c, s, navigate = false)
    }

    /**
     * The background scan read a slot: it runs through the lookup, so a link that no longer fits its slot is dropped and the
     * slot's profile is known. It never creates a profile: a slot with none stays a record until it is tapped.
     */
    private fun scanRead(s: DacSnapshot) {
        device.contentOf(s)?.let { find(it) }
    }

    /**
     * A tap on a USER slot, never refused or delayed by DAC traffic. A slot Contour has a record of opens at once from it; the DAC
     * follows in the background ([DeviceController.switchToSlot]): it only switches (no read). Only when that switch is not
     * confirmed, or the slot has no record, is the slot read, and when that read differs from the record the lookup runs again on it.
     */
    fun tapSlot(slot: Int) {
        val record = device.contentOfRecord(slot)
        val instant = record != null && open(record, null, navigate = true)
        device.switchToSlot(slot) { s ->
            val c = device.contentOf(s) ?: return@switchToSlot
            if (!instant || record?.fingerprint != c.fingerprint) open(c, s, navigate = true)
        }
    }

    /** FROM DAC in the new-profile sheet: what plays now opens through the same lookup. */
    fun openFromDac(): Boolean {
        val s = device.snapshot ?: return false
        val c = device.contentOf(s) ?: return false
        return open(c, s, navigate = true)
    }
}
