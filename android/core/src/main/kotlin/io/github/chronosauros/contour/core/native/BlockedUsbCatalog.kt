package io.github.chronosauros.contour.core.native

/** Known unsupported USB identities, checked BEFORE any positive family route.
 * This is an exclusion/diagnostic catalog, never permission to open a HID or send a probe.
 * A null reason means UNKNOWN TO THIS DENY-LIST, not a programmable or supported DAC.
 * Exact VID/PID exclusions use source-observed identity metadata even if productName is null.
 * Named-only rules are explicitly informational: source section VID + literal, case-sensitive
 * name, no invented PID, trimming, fuzzy matching, or blanket vendor exclusion.
 */
object BlockedUsbCatalog {
    const val PINNED_COMMIT = "0617f382e76629792a5933e6933e4b396a756a93"
    private const val SOURCE = "https://github.com/jeromeof/devicePEQ/blob/$PINNED_COMMIT/"

    private data class Rule(
        val vendorIds: Set<Int>,
        val productId: Int?,
        val productName: String?,
        val reason: String,
        val sourceUrl: String,
    ) {
        fun matches(vid: Int, pid: Int, name: String?): Boolean =
            vid in vendorIds && (productId == null || pid == productId) &&
                (productName == null || name == productName)
    }

    private const val CONEXANT = "Conexant PEQ blocked: no native readback; upstream pull fabricates flat bands and writes flash per band. Not WalkPlay."
    private const val FIIO_SERIAL = "FiiO USB serial blocked: serial transport is not implemented; user-bank and bypass maps conflict. Not a FiiO HID route."
    private const val JDS_SERIAL = "JDS Labs USB serial blocked: serial transport is not implemented; 10/12-band layout, input/output routing and preamp policies conflict. Not Fosi."
    // usbDeviceConfig.js vendor section L667 onward. These are section membership, NOT
    // observed VID evidence for every named model. Never deny this whole vendor set.
    private val moondropSectionVids = setOf(
        0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C,
        0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D,
    )

    private val rules = listOf(
        Rule(setOf(0x0A12), 0x4005, null,
            "Qudelix-5K USB DAC 48KHz blocked: USB receive/readback and firmware-specific framing are unproven. Not FiiO despite shared VID.",
            "${SOURCE}tests/captures/qudelix_qudelix_5k.json#L3-L6"),
        // Literal DX1II requested by the owner; the source establishes VID/PID, not a
        // universal USB name. Blocking the exact pair also covers unreadable/other names.
        Rule(setOf(0x152A), 0x8750, null,
            "Topping DX1II blocked: channel/output routing, preamp and save/persistence are unresolved. Not Fosi despite shared VID.",
            "${SOURCE}devicePEQ/usbDeviceConfig.js#L1279-L1281"),
        Rule(setOf(0x35D8), 0x1496, null, CONEXANT,
            "${SOURCE}tests/captures/moondrop_freedsp_conexant.json#L3-L6"),
        Rule(setOf(0x35D8), 0x149B, null, CONEXANT,
            "${SOURCE}tests/captures/moondrop_echob_conexant.json#L3-L6"),
        Rule(setOf(0x1A86), 0x55D3, null, FIIO_SERIAL,
            "${SOURCE}devicePEQ/usbSerialDeviceConfig.js#L74-L89"),
        Rule(setOf(0x152A), 0x88FA, null, JDS_SERIAL,
            "${SOURCE}devicePEQ/usbSerialDeviceConfig.js#L16-L35"),
        Rule(moondropSectionVids, null, "FreeDSP", "Informational exact-name exclusion; PID unqualified. $CONEXANT",
            "${SOURCE}devicePEQ/usbDeviceConfig.js#L870-L876"),
        Rule(moondropSectionVids, null, "ECHO-B", "Informational exact-name exclusion; PID unqualified. $CONEXANT",
            "${SOURCE}devicePEQ/usbDeviceConfig.js#L896-L902"),
        Rule(setOf(0x2972, 0x0A12), null, "FIIO DM15 R2R",
            "Informational exact-name exclusion; PID unqualified. FiiO DM15 R2R source suspects CDC/serial (FZ=-1); standard HID and native layout unproven. Do not fall through FiiO or WalkPlay.",
            "${SOURCE}devicePEQ/usbDeviceConfig.js#L645-L665"),
        Rule(setOf(0x31B2), null, "Space Gaming IEM",
            "Informational exact-name exclusion; PID unqualified. FiiO-vs-KT firmware, receive traffic and compensate2X/preamp policy unresolved. Do not fall through KT Micro.",
            "${SOURCE}devicePEQ/usbDeviceConfig.js#L1126-L1147"),
    )

    fun reason(vendorId: Int, productId: Int, productName: String?): String? {
        if (vendorId !in 0..0xFFFF || productId !in 0..0xFFFF) return null
        return rules.firstOrNull { it.matches(vendorId, productId, productName) }?.reason
    }

    /** Candidate for a blocked-device diagnostic ONLY, never a native read/write candidate. */
    fun candidate(vendorId: Int, productId: Int, productName: String?): Boolean =
        reason(vendorId, productId, productName) != null
}
