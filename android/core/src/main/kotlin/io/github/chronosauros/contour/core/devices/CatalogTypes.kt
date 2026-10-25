package io.github.chronosauros.contour.core.devices

import io.github.chronosauros.contour.core.DeviceCapabilities
import io.github.chronosauros.contour.core.FilterType

/*
 * The plain data the catalog generator (:core:generateDeviceCatalog, android/core/devices.gradle.kts) fills from the device records. The classes are
 * hand-written so the generator emits constructor calls only; the catalogs and drivers read these instead of typing the numbers again.
 * Behaviour stays in the drivers. A value the code uses while its record still says "unknown" is not here: see [PendingRecords].
 */

/** Which lookup of DeviceTarget finds a record: `find` is the 1.5 list (VID:PID only), `findDevice` adds the 1.6 devices (some need the USB product name). */
internal enum class Recognition { FIND, FIND_DEVICE }

/**
 * How a record is found on USB. [productName] is the literal USB product string (null = the record has none); [matchByName] says the name must
 * match as well (the product string or one of [nameAliases], exactly), otherwise VID:PID alone is enough.
 */
internal data class UsbIdentity(
    val recordId: String,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val matchByName: Boolean,
    val nameAliases: List<String>,
    val recognition: Recognition,
) {
    fun matches(vendorId: Int, productId: Int, productName: String?): Boolean =
        this.vendorId == vendorId && this.productId == productId &&
            (!matchByName || (productName != null && (productName == this.productName || productName in nameAliases)))
}

/** A factory-flat band: a peak at 0 dB, this frequency, this Q (the register Q for the TRN). */
internal data class FlatBand(val freqHz: Int, val q: Double)

/** A stable WalkPlay record (Micro, Max, TRN): the caps, the preamp policy, the factory flat of the spare slots and the read bounds. */
internal data class WalkPlayData(
    val id: String,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    /** The literal USB product string; null = not recorded. */
    val usbName: String?,
    val schemeNo: Int,
    val reportId: Int,
    /** The length byte of a filter write. */
    val bandLen: Int,
    val bands: Int,
    val gainMinDb: Double,
    val gainMaxDb: Double,
    val qMin: Double,
    val qMax: Double,
    val freqMinHz: Double,
    val freqMaxHz: Double,
    val types: Set<FilterType>,
    /** The register Q of a shelf is the intended Q times this (record `peq.shelfQScale`; the TRN: sqrt(2)); 1.0 = the register is the Q. */
    val shelfQScale: Double,
    /** The rate the DSP runs the coefficients Contour sends at (record `peq.coefficientRateHz`; the Micro: 93 750 Hz, the vendor's tool designs for 96 000). */
    val coefficientRateHz: Double,
    val preampWholeDb: Boolean,
    val preampMinDb: Int,
    val preampMaxDb: Int,
    val factoryFlat: List<FlatBand>,
    /** What a read accepts (the register format, not the editor caps); null = the record gives none and the read is held to the caps. */
    val readBoundsHz: IntRange?,
    val readBoundsDb: ClosedFloatingPointRange<Double>?,
) {
    fun caps() = DeviceCapabilities(name, vendorId, productId, bands, gainMinDb, gainMaxDb, qMin, qMax, freqMinHz, freqMaxHz, types, preampWholeDb,
        shelfQScale = shelfQScale, coefficientRateHz = coefficientRateHz)
}

/** The WalkPlay record of a device Contour only reads (the Echo-A): what its profile and its caps take from the record; the rest is [PendingRecords]. */
internal data class ReadOnlyWalkPlayData(
    val id: String,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val usbName: String,
    val schemeNo: Int,
    val reportId: Int,
    val bands: Int,
    val gainMinDb: Double,
    val gainMaxDb: Double,
    val qMin: Double,
    val qMax: Double,
    val types: Set<FilterType>,
    /** The rate the DSP runs the coefficients at (record `peq.coefficientRateHz`), measured on the Echo-A like on the Micro. */
    val coefficientRateHz: Double,
)

/** The Moondrop record (Dawn Pro 2). [offeredTypes] are what Contour offers; [nativeTypes] every code the manufacturer's tool knows (offered and held). */
internal data class MoondropData(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String,
    val reportId: Int,
    val reportBytes: Int,
    /** The EQ index the Hub's Write Cfg first frame asks for (record `driver.peqBank`). */
    val writableIndex: Int,
    /** Every EQ index whose bank Contour writes (record `slots.writableIndexes`): the vendor catalog's PEQ index and the one the owner's unit stays on. */
    val writableIndexes: Set<Int>,
    val bands: Int,
    val gainMinDb: Double,
    val gainMaxDb: Double,
    val qMin: Double,
    val qMax: Double,
    val freqMinHz: Double,
    val freqMaxHz: Double,
    val offeredTypes: Set<FilterType>,
    val nativeTypes: Map<Int, FilterType>,
    /** The register Q of a shelf is the intended Q times this (record `peq.shelfQScale`; measured on the Dawn Pro 2, run 2026-10-25-moondrop-dawn-pro-2-08-mac-eqtest-48k: sqrt(2)); 1.0 = the register is the Q. */
    val shelfQScale: Double,
    val preGainMinDb: Double,
    val preGainMaxDb: Double,
    val globalGainMinDb: Double,
    val globalGainMaxDb: Double,
)

/** The KT Micro record (Allegro Mini): five bands as register pairs consecutive from [bandRegisterBase] (gain and frequency, then Q and type). */
internal data class KtMicroData(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String,
    val nameAliases: List<String>,
    val bands: Int,
    val gainMinDb: Double,
    val gainMaxDb: Double,
    val qMin: Double,
    val qMax: Double,
    val freqMinHz: Double,
    val freqMaxHz: Double,
    val types: Set<FilterType>,
    val bandRegisterBase: Int,
    val enableRegister: Int,
    val digitalGainRegister: Int,
    val writeAck: Int,
    /** The Global Gain steps the vendor tool offers (register 0x3B). */
    val globalGainUserSteps: IntRange,
    /** The rate the DSP designs its biquads for (record `peq.designRateHz`, 24 000 Hz measured): a band plays at register frequency x stream rate / design rate. */
    val designRateHz: Double,
    /** The stream rate Contour assumes (record `peq.assumedStreamHz`, 48 000 Hz) when it converts an intended frequency to the register. */
    val assumedStreamHz: Double,
)
