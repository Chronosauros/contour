package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.FilterType

/** Protocol facts from devicePEQ 0617f382, usbDeviceConfig.js L1075-1254.
 * Admission means codec candidate, never hardware-qualified support. Exact names are not normalized.
 */
object KtMicroCatalog {
    const val VENDOR_ID = 0x31B2
    const val SOURCE_COMMIT = "0617f382e76629792a5933e6933e4b396a756a93"
    data class RegisterPair(val gainFrequency: Int, val qType: Int)
    data class RegisterMap(val name: String, val productIds: Set<Int>, val bands: List<RegisterPair>,
                           val documentedEnableRegister: Int, val blockedReason: String? = null)
    data class Model internal constructor(
        val productName: String,
        /** Empty means source exact-name rule has no proven PID; no PID was invented. */
        val provenProductIds: Set<Int>,
        val registers: RegisterMap,
        val compensate2X: Boolean = false,
        val peakOnly: Boolean = false,
        val reconnectAfterSave: Boolean = false,
        val blockedReason: String? = null,
    ) {
        val admitted: Boolean get() = blockedReason == null && registers.blockedReason == null
        val nativeTypes: Set<FilterType> get() = if (peakOnly) setOf(FilterType.PEAK)
            else setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF)
        val bandCount: Int get() = 5
        val minGainDb: Double get() = -10.0
        val maxGainDb: Double get() = 10.0
        val minQ: Double get() = 0.1
        val maxQ: Double get() = 5.0
        val minFrequencyHz: Double get() = 20.0
        val maxFrequencyHz: Double get() = 20000.0
        val supportsManualPreamp: Boolean get() = false
        val writableSlots: Set<Int> get() = if (admitted) setOf(3) else emptySet()
    }
    val kt0211 = RegisterMap("KT_0211L", setOf(0x0113),
        List(5) { RegisterPair(0x26 + 2 * it, 0x27 + 2 * it) }, 0x24)
    val kt1132 = RegisterMap("KT_1132L", setOf(0x1132, 0x3006),
        listOf(RegisterPair(0x35, 0x36), RegisterPair(0x37, 0x38), RegisterPair(0x39, 0x3A),
            RegisterPair(0x3B, 0x3C), RegisterPair(0x3D, 0x3F)), 0x34,
        "Enable 0x34 in configuration conflicts with handler 0x24; capture 0x24 is ASCII year, not slot")
    val kt3016 = RegisterMap("KT_3016L", setOf(0x3016),
        listOf(RegisterPair(0x37, 0x38), RegisterPair(0x3B, 0x3C), RegisterPair(0x3D, 0x3F),
            RegisterPair(0x39, 0x3A), RegisterPair(0x35, 0x36)), 0x34,
        "Enable 0x34 in configuration conflicts with handler 0x24; capture 0x24 is ASCII year, not slot")
    val pidRegisterMaps = listOf(kt0211, kt1132, kt3016)
    private const val PREAMP_CONFLICT = "Manual pregain unsupported; upstream write 0x66 contradicts no-pregain constraints; Fission slot 1 is not validated 2/3"
    val models = listOf(
        Model("Kiwi Ears-Allegro PRO", setOf(0x0111), kt0211, reconnectAfterSave = true),
        // Explicit source alias of the captured hyphen variant; not a fuzzy-name match.
        Model("Kiwi Ears Allegro Mini", setOf(0x0111), kt0211, reconnectAfterSave = true),
        Model("Kiwi Ears-Allegro Mini", setOf(0x0111), kt0211, reconnectAfterSave = true),
        Model("KT02H20 HIFI Audio", setOf(0x0111), kt0211, compensate2X = true, peakOnly = true),
        Model("TANCHJIM-ONE DSP", setOf(0x0111), kt0211),
        Model("TANCHJIM BUNNY DSP", setOf(0x1112), kt0211, blockedReason = PREAMP_CONFLICT),
        Model("TANCHJIM FISSION", emptySet(), kt0211, blockedReason = PREAMP_CONFLICT),
        Model("TANCHJIM-FISSION  DSP", setOf(0x1119), kt0211, blockedReason = PREAMP_CONFLICT),
        Model("CDSP", emptySet(), kt0211),
        Model("Chu2 DSP", setOf(0x0113), kt0211),
    )

    /** Exact VID + literal name, plus known PID where evidenced. No VID/PID-only writer fallback.
     * CDSP uses the upstream exact-name rule; its PID remains explicitly unqualified.
     */
    fun find(vendorId: Int, productId: Int, productName: String?): Model? {
        if (vendorId != VENDOR_ID || productId !in 0..0xFFFF || productName == null) return null
        if (productId in setOf(0x0001, 0x1132, 0x3006, 0x3016)) return null
        return models.singleOrNull { it.productName == productName &&
            (it.provenProductIds.isEmpty() || productId in it.provenProductIds) }
    }

    /** Inspection only. Shared 0111 needs exact product name; 0113 does not authorize a writer. */
    fun registerMapForPid(vendorId: Int, productId: Int): RegisterMap? =
        if (vendorId != VENDOR_ID) null else pidRegisterMaps.singleOrNull { productId in it.productIds }
}
