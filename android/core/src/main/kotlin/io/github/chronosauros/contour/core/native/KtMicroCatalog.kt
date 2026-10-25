package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.devices.GeneratedDevices
import io.github.chronosauros.contour.core.devices.PendingRecords

/**
 * KT Micro register DACs. Only the Kiwi Ears Allegro Mini (chip KT_0211L) is here: the one KT identity with a
 * hardware read (DacLab, 20.10.2026) and a vendor tool whose limits are known (Kiwi Ears Equalizer, equalizer.kiwiears.com).
 * Register facts: devicePEQ 0617f382 (0BSD) ktmicroUsbHidHandler.js and usbDeviceConfig.js, as ported from advBeta. No broader
 * KT catalog, no probe of unknown KT identities.
 *
 * Limits are the vendor tool's, not devicePEQ's: 5 PK bands, +-12 dB, Q 0.1-10, 20-20000 Hz as REGISTER frequencies, an EQ switch (register 0x24),
 * no preamp (its "Global Gain" is the analog DAC gain, register 0x3B, never written here). Exact USB names only.
 *
 * Frequency: the DSP designs its biquads for a fixed 24 000 Hz and runs them at the stream rate, so a band plays at register f x stream / 24 000 (measured 25.10.2026,
 * runs 2026-10-25-kiwi-ears-allegro-mini-07 and -08). Contour assumes a 48 kHz stream and writes the register f = intended f x 24 000 / 48 000 ([Model.registerFrequency]); a read is
 * converted back ([Model.intendedFrequency]). The editor's frequency range is the intended one: the register range x 2, capped at 20 000 Hz (40-20000 Hz). The lower edge can drop to
 * 20 Hz once a register of 10 Hz is proven on the unit (none below 20 was ever written or read).
 */
object KtMicroCatalog {
    // Every number below is the record's (devices/kiwi-ears-allegro-mini.json via GeneratedDevices) - except reconnectAfterSave, which the record
    // still holds as "unknown" (PendingRecords). What stays here is the shape of the model and the lookup.
    private val allegro = GeneratedDevices.KIWI_EARS_ALLEGRO_MINI
    const val VENDOR_ID = GeneratedDevices.KIWI_EARS_ALLEGRO_MINI_VENDOR_ID
    data class RegisterPair(val gainFrequency: Int, val qType: Int)
    data class RegisterMap(val name: String, val bands: List<RegisterPair>, val enableRegister: Int,
                           val writeAck: Int, val digitalGainRegister: Int)
    data class Model(
        val productName: String,
        val productIds: Set<Int>,
        val registers: RegisterMap,
        /** The USB device drops off the bus after SAVE; the proof of a write is a read after it is back. */
        val reconnectAfterSave: Boolean = PendingRecords.ALLEGRO_RECONNECT_AFTER_SAVE,
        /** False until the owner's unit has taken a write from the phone app and read it back: the Allegro Mini has (Nothing Phone 25.10.2026, run 2026-10-25-kiwi-ears-allegro-mini-05-phone-nothing: registers = intended f x 24000 / 48000, VERIFIED; EQ switch and Global Gain on the Pixel run 2026-10-24-kiwi-ears-allegro-mini-04-phone). */
        val hardwareVerified: Boolean = false,
    ) {
        val nativeTypes: Set<FilterType> get() = allegro.types
        val bandCount: Int get() = registers.bands.size
        val minGainDb: Double get() = allegro.gainMinDb
        val maxGainDb: Double get() = allegro.gainMaxDb
        val minQ: Double get() = allegro.qMin
        val maxQ: Double get() = allegro.qMax
        /** The rate the DSP designs its biquads for (record `peq.designRateHz`), and the stream rate Contour assumes (record `peq.assumedStreamHz`). */
        val designRateHz: Double get() = allegro.designRateHz
        val assumedStreamHz: Double get() = allegro.assumedStreamHz
        /** The register frequency (unrounded) that plays [intendedHz] at the assumed stream rate. */
        fun registerFrequency(intendedHz: Double): Double = intendedHz * designRateHz / assumedStreamHz
        /** The frequency a band with this register plays at the assumed stream rate. */
        fun intendedFrequency(register: Int): Double = register * assumedStreamHz / designRateHz
        /** The intended (acoustic) frequency range: the register range (the vendor tool's 20-20000 Hz) converted, and capped at the vendor's upper edge. */
        val minFrequencyHz: Double get() = intendedFrequency(allegro.freqMinHz.toInt())
        val maxFrequencyHz: Double get() = minOf(allegro.freqMaxHz, intendedFrequency(allegro.freqMaxHz.toInt()))
        /** The Global Gain steps (register 0x3B) the vendor tool offers; layout of the register not proven on hardware, so a read is shown only inside this range. */
        val globalGainUserSteps: IntRange get() = allegro.globalGainUserSteps
    }

    /** Band i: gain-and-frequency register `base + 2 i`, Q-and-type register `base + 2 i + 1` (the record's `driver.bandRegisters`). */
    val kt0211 = RegisterMap("KT_0211L", List(allegro.bands) { RegisterPair(allegro.bandRegisterBase + 2 * it, allegro.bandRegisterBase + 2 * it + 1) },
        allegro.enableRegister, allegro.writeAck, allegro.digitalGainRegister)

    /** The owner's unit reports "Kiwi Ears-Allegro Mini"; devicePEQ also lists the spelling without the hyphen (the record's product name and its alias). */
    val models = (listOf(allegro.productName) + allegro.nameAliases).map { Model(it, setOf(allegro.productId), kt0211, hardwareVerified = true) }

    /** Exact VID, exact PID and the literal USB product name; no VID-only or fuzzy-name route: the record's `usb` identity, name match included. */
    fun find(vendorId: Int, productId: Int, productName: String?): Model? =
        if (!GeneratedDevices.KIWI_EARS_ALLEGRO_MINI_USB.matches(vendorId, productId, productName)) null
        else models.singleOrNull { it.productName == productName }
}
