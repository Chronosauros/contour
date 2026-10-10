package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.FilterType
import kotlin.math.sqrt

/** Source rules are evidence, not transport authorisation or hardware support claims. */
data class FiioCaptureIdentity(val vendorId: Int, val productId: Int, val productName: String, val fixturePath: String)
data class FiioConfig(
    val productName: String,
    val vendorIds: Set<Int>,
    val reportId: Int,
    val maxFilters: Int,
    val minGainDb: Double,
    val maxGainDb: Double,
    val minQ: Double,
    val maxQ: Double,
    val saveCommand: Int,
    val userSlots: Set<Int>,
    val declaredWritableSlots: Set<Int>,
    val stockSlots: Set<Int>,
    val slotLabels: Map<Int, String>,
    val bypassSlot: Int?,
    val disconnectOnSave: Boolean,
    val peakingGainCompensation: Boolean,
    val shelfAlphaCompensation: Boolean,
    val sourceUrl: String,
    val constraintsUrl: String,
    val constraintsRef: String,
    val upstreamExperimental: Boolean,
    val bestGuessProductName: Boolean,
    val capture: FiioCaptureIdentity?,
    val codecBlockers: List<String>,
    val evidenceCaveats: List<String>,
    val status: String = "beta-unverified",
    // Only source handler's actual types: PK=0, LSQ=1, HSQ=2. JSON advertises
    // more types but no supported writer/decoder exists; do not use those codes.
    val typeCodes: Map<FilterType, Int> = mapOf(FilterType.PEAK to 0, FilterType.LOW_SHELF to 1, FilterType.HIGH_SHELF to 2),
    // Conservative source connector input range, not an acoustic/firmware guarantee.
    val minFrequencyHz: Int = 20,
    val maxFrequencyHz: Int = 20000,
    // Hardware-observed (KA15, 03.10.2026): the DAC ignores a lower filter count and keeps reading back
    // maxFilters, so the unused tail must be written as neutral filters instead of shortening the count.
    val fixedBandCount: Boolean = false,
    // Hardware-observed: stored Q reads back 1-2 hundredths higher for Q above ~3.5 (390 -> 391, 605 -> 607).
    val qReadbackSlack: Boolean = false,
    // KA15 echoes every AA write; reading each echo keeps request and reply in lockstep.
    val consumeWriteEchoes: Boolean = false,
    // Pause after every AA write, for DACs that do not echo writes (consumeWriteEchoes paces the KA15 instead).
    val writeGapMs: Int = 0,
    // Official web app (fiiocontrol.fiio.com, captured 03.10.2026): byte 3 = host sequence, byte before EE =
    // CRC-8/MAXIM over header..data. The device also answers zeros, so this is fidelity, not a requirement.
    val checksumFrames: Boolean = false,
    // Hardware-observed (KA15 on Android, 03.10.2026): HID replies stop while no USB audio is streaming;
    // the app keeps a silent output track on the DAC for the whole HID session.
    val needsAudioStream: Boolean = false,
    // Official web app (KA15 capture, 03.10.2026): command 0x30 reads and writes the USER slot names,
    // index = position in the sorted userSlots (USER1 = 0).
    val userSlotNames: Boolean = false,
    // Lets the user choose a USER slot without FiiO name commands (no 0x30 reads or writes). Set per device, after a hardware test.
    val userSlotPicker: Boolean = false,
    // Hardware-measured (KA15, 04.10.2026, line-in sweeps against Close EQ): LS/HS shelves play Q = register / sqrt(2)
    // for every type, frequency, gain and Q, so the register holds intended Q * shelfQScale. PK Q is played as stored.
    // Not combinable with shelfAlphaCompensation (the devicePEQ slope law measured wrong on the KA15).
    val shelfQScale: Double = 1.0,
    // Hardware-measured (KA15, 04.10.2026): with any USER preset active, output = input - 12 dB + preamp register,
    // so the register holds intended preamp + preampOffsetDb.
    val preampOffsetDb: Double = 0.0,
) {
    /** Intended (acoustic) preamp range: the register range minus [preampOffsetDb]. */
    val preampMinDb: Double get() = minGainDb - preampOffsetDb
    val preampMaxDb: Double get() = maxGainDb - preampOffsetDb
    /** Intended (acoustic) shelf Q range: the register Q range divided by [shelfQScale]. */
    val shelfQMin: Double get() = minQ / shelfQScale
    val shelfQMax: Double get() = maxQ / shelfQScale
    val hardwareGates: List<String> get() = listOf(
        "Verify exact USB identity and HID interface/descriptor/report size before I/O",
        "Verify selection, complete backup, post-save native readback on actual firmware",
        "Persistence across power cycle and acoustic behaviour unverified",
    )
}

object FiioCatalog {
    const val PINNED_COMMIT = "0617f382e76629792a5933e6933e4b396a756a93"

    /**
     * USB product ID of the FiiO K13 R2R (vendor 0x2972), measured on the hardware 10.10.2026 (Pixel, firmware 1.15:
     * "vidpid 2972:0120", product "FIIO K13 R2R"). The same number goes into `res/xml/device_filter.xml` (decimal 288).
     */
    const val K13_R2R_PRODUCT_ID = 0x0120

    /** Contour stable: the FiiO KA15 (hardware-tested on a Pixel, 04.10.2026) and the K13 R2R. Exact literal rules from the
     * pinned upstream source; the KA15 is extended with what it showed on hardware (see the flags below), the K13 R2R
     * only with what its own hardware test showed (the fixed band count).
     */
    val rules: List<FiioConfig> = listOf(
        FiioConfig(
            productName = "FIIO KA15",
            vendorIds = setOf(10610, 2578),
            reportId = 7,
            maxFilters = 10,
            minGainDb = -12.0,
            maxGainDb = 12.0,
            minQ = 0.1,
            maxQ = 10.0,
            saveCommand = 25,
            userSlots = setOf(7, 8, 9),
            declaredWritableSlots = setOf(7, 8, 9),
            stockSlots = setOf(0, 1, 2, 3, 4, 5, 6),
            slotLabels = mapOf(0 to "Jazz", 1 to "Pop", 2 to "Rock", 3 to "Dance", 4 to "R&B", 5 to "Classic", 6 to "Hip-hop", 7 to "USER1", 8 to "USER2", 9 to "USER3", 10 to "Close EQ"),
            bypassSlot = 10,
            disconnectOnSave = false,
            peakingGainCompensation = false,
            shelfAlphaCompensation = false,
            sourceUrl = "https://github.com/jeromeof/devicePEQ/blob/0617f382e76629792a5933e6933e4b396a756a93/devicePEQ/usbDeviceConfig.js#L360",
            constraintsUrl = "https://github.com/jeromeof/devicePEQ/blob/0617f382e76629792a5933e6933e4b396a756a93/devicePEQ/peqConstraintsConfig.json#L1029",
            constraintsRef = "peq10Band12dBAllFilters7",
            upstreamExperimental = false,
            bestGuessProductName = false,
            capture = FiioCaptureIdentity(10610, 260, "FIIO KA15", "tests/captures/fiio_fiio_ka15.json"),
            codecBlockers = listOf(),
            evidenceCaveats = listOf("Explicit FiiO route for exact FIIO KA15 2972:0104; ignore upstream WalkPlay-SchemeNo10 collision"),
            fixedBandCount = true,
            qReadbackSlack = true,
            consumeWriteEchoes = true,
            checksumFrames = true,
            needsAudioStream = true,
            userSlotNames = true,
            // Measured 04.10.2026 (research/protocol/PROTOCOL.md, FiiO KA15): shelf Q register = Q * sqrt(2),
            // preamp register = preamp + 12 dB. Intended ranges: shelf Q up to 10 / sqrt(2), preamp -24..0 dB.
            shelfQScale = sqrt(2.0),
            preampOffsetDb = 12.0,
        ),
        FiioConfig(
            productName = "FIIO K13 R2R",
            vendorIds = setOf(10610, 2578),
            reportId = 7,
            maxFilters = 10,
            minGainDb = -24.0,
            maxGainDb = 12.0,
            minQ = 0.1,
            maxQ = 10.0,
            saveCommand = 25,
            userSlots = setOf(160, 161, 162, 163, 164, 165, 166, 167, 168, 169),
            declaredWritableSlots = setOf(160, 161, 162, 163, 164, 165, 166, 167, 168, 169),
            stockSlots = setOf(0, 1, 2, 3, 4, 5, 6, 8),
            slotLabels = mapOf(240 to "BYPASS", 0 to "Jazz", 1 to "Pop", 2 to "Rock", 3 to "Dance", 4 to "R&B", 5 to "Classic", 6 to "Hip-hop", 8 to "Retro", 9 to "sDamp-1", 10 to "sDamp-2", 160 to "USER1", 161 to "USER2", 162 to "USER3", 163 to "USER4", 164 to "USER5", 165 to "USER6", 166 to "USER7", 167 to "USER8", 168 to "USER9", 169 to "USER10"),
            bypassSlot = 240,
            disconnectOnSave = false,
            peakingGainCompensation = false,
            shelfAlphaCompensation = false,
            sourceUrl = "https://github.com/jeromeof/devicePEQ/blob/0617f382e76629792a5933e6933e4b396a756a93/devicePEQ/usbDeviceConfig.js#L375",
            constraintsUrl = "https://github.com/jeromeof/devicePEQ/blob/0617f382e76629792a5933e6933e4b396a756a93/devicePEQ/peqConstraintsConfig.json#L994",
            constraintsRef = "peq10Band12dBWideAllFilters",
            upstreamExperimental = false,
            bestGuessProductName = false,
            capture = null,
            codecBlockers = listOf(),
            evidenceCaveats = listOf("No capture file: the USB product ID was measured on the K13 R2R; of the KA15 hardware flags only the fixed band count"),
            userSlotPicker = true,
            // Hardware 10.10.2026 (Pixel): a COUNT write of 9 read back 10 and band 10 kept its old registers. Without
            // the neutral pad a shorter profile would leave the slot's previous band 10 active and audible.
            fixedBandCount = true,
            // Hardware 10.10.2026: writes 16 ms apart all read back, but after a power cycle only the first one to three
            // survived (USER10: band 1; USER9: preamp and bands 1-2), the rest reverted. With 300 ms per write both
            // slots kept preamp and all ten bands across a power cycle (same day).
            writeGapMs = 300,
        ),
    )

    /** The KA15 by its exact USB identity 2972:0104 (stable builds route by VID:PID, see DeviceTarget.find). */
    val KA15: FiioConfig = rules.single { it.productName == "FIIO KA15" }

    /** The K13 R2R by its exact USB identity 2972:[K13_R2R_PRODUCT_ID] (see DeviceTarget.find). */
    val K13: FiioConfig = rules.single { it.productName == "FIIO K13 R2R" }

    /** Positive route solely for the captured exact VID/PID/name identity; the HID descriptor is still
     * required before any I/O. Every unknown PID/name returns null; no probing writes.
     */
    fun capturedRoute(vendorId: Int, productId: Int, productName: String): FiioConfig? =
        rules.singleOrNull { rule ->
            rule.codecBlockers.isEmpty() && rule.capture?.let {
                it.vendorId == vendorId && it.productId == productId && it.productName == productName
            } == true
        }
}
