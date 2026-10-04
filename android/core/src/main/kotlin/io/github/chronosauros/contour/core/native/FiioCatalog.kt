package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.FilterType

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
    // Official web app (fiiocontrol.fiio.com, captured 03.10.2026): byte 3 = host sequence, byte before EE =
    // CRC-8/MAXIM over header..data. The device also answers zeros, so this is fidelity, not a requirement.
    val checksumFrames: Boolean = false,
    // Hardware-observed (KA15 on Android, 03.10.2026): HID replies stop while no USB audio is streaming;
    // the app keeps a silent output track on the DAC for the whole HID session.
    val needsAudioStream: Boolean = false,
    // Official web app (KA15 capture, 03.10.2026): command 0x30 reads and writes the USER slot names,
    // index = position in the sorted userSlots (USER1 = 0).
    val userSlotNames: Boolean = false,
) {
    val hardwareGates: List<String> get() = listOf(
        "Verify exact USB identity and HID interface/descriptor/report size before I/O",
        "Verify selection, complete backup, post-save native readback on actual firmware",
        "Persistence across power cycle and acoustic behaviour unverified",
    )
}

object FiioCatalog {
    const val PINNED_COMMIT = "0617f382e76629792a5933e6933e4b396a756a93"

    /** Contour stable: the FiiO KA15 only (hardware-tested on a Pixel, 04.10.2026). Exact literal rule from the
     * pinned upstream source, extended with what the KA15 showed on hardware (see the flags below).
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
        ),
    )

    /** The KA15 by its exact USB identity 2972:0104 (stable builds route by VID:PID, see DeviceTarget.find). */
    val KA15: FiioConfig = rules.single { it.productName == "FIIO KA15" }

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
