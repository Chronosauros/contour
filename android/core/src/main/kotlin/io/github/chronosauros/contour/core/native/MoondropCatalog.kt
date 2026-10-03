package io.github.chronosauros.contour.core.native

/** Literal named rules, independent of WalkPlay routing and inherited PID groups.
 * Source: https://github.com/jeromeof/devicePEQ/blob/0617f382e76629792a5933e6933e4b396a756a93/devicePEQ/usbDeviceConfig.js#L789-L939
 * Constraints: https://github.com/jeromeof/devicePEQ/blob/0617f382e76629792a5933e6933e4b396a756a93/devicePEQ/peqConstraintsConfig.json
 * Candidate means beta READ-ONLY diagnostics, not validated hardware support or profile import.
 */
object MoondropCatalog {
    enum class Family { NEW, OLD_FASHIONED }
    data class Model internal constructor(
        val productName: String,
        val vendorIds: Set<Int>,
        val provenProductIds: Set<Int>,
        val bandCount: Int,
        val reportId: Int,
        val reasonReadOnly: String,
        val family: Family,
        val nativeTypes: Set<Int>,
        val sourceLine: Int,
        val blockedReason: String? = null,
        val manufacturer: String = "Moondrop",
    ) {
        val readOnlyCandidate: Boolean get() = blockedReason == null && family == Family.NEW
    }
    data class Match(val model: Model?, val reason: String, val readOnlyCandidate: Boolean)

    // This broad vendor list is SOURCE DATA only. It never constitutes a VID-wide route.
    private val sourceVendorIds = setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666,
        0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B,
        0x60C1, 0x60E1, 0xB445, 0xB44D)
    const val WRITE_BLOCKED_REASON = "Writes unavailable: source Custom 101 conflicts with hardcoded bank 7; " +
        "pregain read 03 differs from write 23, zero is omitted, automatic headroom varies by model, " +
        "and enable is a no-op. No bank selection, commit, pregain or enable writes are implemented."
    const val OLD_BLOCKED_REASON = "Old Fashioned source accepts ANY inputreport without register/command echo validation; " +
        "no native RX fixture proves response correlation. Catalog imports moondropOldFashionedUsbHID but source exports " +
        "oldFashionedUsbHidHandler, falling through to WalkPlay. Automatic read/import blocked; diagnostic register parsing " +
        "requires explicit echoes. Writable slots -1/count 0 conflict with single-bank writer; pregain is unknown."
    const val MARIGOLD_BLOCKED_REASON = "Pinned MOONDROP Marigold capture 35D8:011C explicitly uses WalkPlay SchemeNo10 " +
        "(80 09 00 reads), conflicting with the named native Moondrop handler (80 09 18). " +
        "Do not reinterpret this capture or normalize it into Marigold; native read/import blocked."

    private fun newModel(name: String, line: Int, shelves: Boolean = true, blocked: String? = null,
                         provenPids: Set<Int> = emptySet()) = Model(name, sourceVendorIds, provenPids,
        8, 0x4B, WRITE_BLOCKED_REASON, Family.NEW, if (shelves) setOf(1, 2, 3) else setOf(2), line, blocked)
    val models: List<Model> = listOf(
        Model("Old Fashioned", sourceVendorIds, emptySet(), 5, 0x4B, OLD_BLOCKED_REASON,
            Family.OLD_FASHIONED, setOf(2), 789, OLD_BLOCKED_REASON),
        newModel("Rays", 825, shelves = false),
        newModel("Marigold", 833, shelves = false),
        // PID belongs ONLY to the captured tuple 35D8:011C; not every VID in sourceVendorIds.
        newModel("MOONDROP Marigold", 840, shelves = false, blocked = MARIGOLD_BLOCKED_REASON,
            provenPids = setOf(0x011C)),
        newModel("FreeDSP Pro", 847),
        newModel("MOONRIVER 3", 855),
        newModel("FreeDSP Mini", 863),
        newModel("DAWN PRO2", 881),
        newModel("Echo A", 888, shelves = false),
        newModel("AG Rays", 907, shelves = false),
        newModel("DHA15", 915),
        newModel("Deco Audio System", 922),
        newModel("INN Deco75-DH Audio", 929),
        newModel("ddHiFi DSP IEM - Memory", 936, shelves = false),
    )

    /** Exact case-sensitive descriptor name + source VID only, never prefix/trim/PID-group routing.
     * Empty provenProductIds means unproven PID, NOT validated support for all PIDs.
     * Known conflicting tuples fail closed even when presented with another literal name.
     */
    fun find(vendorId: Int, productId: Int, productName: String?): Match {
        if (vendorId !in 0..0xFFFF || productId !in 0..0xFFFF)
            return Match(null, "Invalid USB vendor/product identifier", false)
        if (vendorId == 0x35D8 && productId in setOf(0x1496, 0x149B))
            return Match(null, "Known Conexant write-only tuple; not native Moondrop HID. Upstream capture has invalid byte integers.", false)
        if (vendorId == 0x35D8 && productId == 0x011C)
            return Match(models.singleOrNull { it.productName == productName && it.productName == "MOONDROP Marigold" },
                MARIGOLD_BLOCKED_REASON, false)
        val model = models.singleOrNull { it.productName == productName }
            ?: return Match(null, when (productName) {
                "FreeDSP", "Moondrop FreeDSP", "ECHO-B", "Moondrop ECHO-B" ->
                    "Conexant identity, not the native Moondrop protocol; no readback support"
                "DAWN PRO 2" -> "DAWN PRO 2 is not the literal native Moondrop rule DAWN PRO2; no name normalization"
                else -> "No exact native Moondrop product-name rule; no VID-wide fallback"
            }, false)
        if (vendorId !in model.vendorIds) return Match(null, "Exact name but VID is not in the source named rule", false)
        if (model.blockedReason != null) return Match(model, model.blockedReason, false)
        return Match(model, "Beta read-only exact-name + VID candidate; PID and native RX layout remain hardware-unvalidated. " +
            model.reasonReadOnly, true)
    }
}
