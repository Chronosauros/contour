package io.github.chronosauros.contour.core

/** Generated from devicePEQ 0617f382 / audited 367-row catalog; labels are NOT USB product names.
 * Exact pairs only. New paths are beta recipes, never a statement of hardware validation. */
object WalkPlayCatalog {
    const val SOURCE_COMMIT = "0617f382e76629792a5933e6933e4b396a756a93"
    data class Row(val vidToken: String, val pidToken: String, val scheme: Int, val decision: String, val catalogId: Int, val label: String) {
        val valid: Boolean get() = token.matches(vidToken) && token.matches(pidToken)
        val vid: Int? get() = if (valid) vidToken.substring(2).toInt(16) else null
        val pid: Int? get() = if (valid) pidToken.substring(2).toInt(16) else null
    }
    private val token = Regex("0x[0-9A-Fa-f]{4}")
    sealed interface Resolution {
        data class Ready(val protocol: DeviceProtocol) : Resolution
        data class Blocked(val reason: String) : Resolution
        data object NeedsName : Resolution
        data object Unknown : Resolution
    }
    private data class Named(val name: String, val vendors: Set<Int>, val scheme: Int, val bands: Int, val qMax: Double,
        val types: Set<Int>, val upstreamExperimental: Boolean, val family: String, val inheritedScheme: Boolean)
    private data class Excluded(val name: String, val vendors: Set<Int>)
    private val names = listOf(
        Named("TANCHJIM-SPACE PRO", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 16, 10, 10.0, setOf(2), false, "walkplay", false),
        Named("TANCHJIM-OLA II DSP", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 10, 8, 5.0, setOf(2), false, "walkplay", false),
        Named("Protocol Max", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 16, 10, 10.0, setOf(2, 1, 3), false, "walkplay", false),
        Named("Octave", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 18, 10, 10.0, setOf(2, 1, 3), false, "walkplay", false),
        Named("CS43131 HiFi Audio DSP", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 11, 8, 10.0, setOf(2, 1), false, "walkplay", false),
        Named("CS43198 HiFi DSP Audio", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 11, 8, 10.0, setOf(2, 1), false, "walkplay", false),
        Named("BGVP MX1", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 15, 8, 10.0, setOf(2, 1, 3), true, "walkplay", false),
        Named("DT04", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 15, 8, 10.0, setOf(2, 1, 3), true, "walkplay", false),
        Named("MD-QT-042", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 15, 8, 10.0, setOf(2, 1, 3), true, "walkplay", false),
        Named("MOONDROP HiFi with PD", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 15, 8, 10.0, setOf(2, 1, 3), true, "walkplay", false),
        Named("DAWN PRO 2", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 15, 8, 10.0, setOf(2, 1, 3), false, "walkplay", false),
        Named("CS431XX", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 15, 8, 10.0, setOf(2, 1, 3), true, "walkplay", false),
        Named("ES9039 ", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 15, 8, 10.0, setOf(2, 1, 3), true, "walkplay", false),
        Named("TANCHJIM-STARGATE II", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 15, 8, 10.0, setOf(2), false, "walkplay", false),
        Named("didiHiFi DSP Cable - Memory", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 15, 8, 10.0, setOf(2, 1, 3), false, "walkplay", false),
        Named("ddHiFi DSP Cable - Memory", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 15, 8, 10.0, setOf(2, 1, 3), false, "walkplay", false),
        Named("Dual CS43198", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 15, 8, 10.0, setOf(2, 1, 3), true, "walkplay", false),
        Named("ES9039 HiFi DSP Audio", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 15, 8, 10.0, setOf(2, 1, 3), true, "walkplay", false),
        Named("TRUTHEAR KEYX", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D), 15, 8, 10.0, setOf(2, 1, 3), false, "walkplay", false),
        Named("Kiwi Ears-Allegro PRO", setOf(0x31B2), 0, 5, 5.0, setOf(), false, "ktmicro", true),
        Named("Kiwi Ears Allegro Mini", setOf(0x31B2), 0, 5, 5.0, setOf(), false, "ktmicro", true),
        Named("Kiwi Ears-Allegro Mini", setOf(0x31B2), 0, 5, 5.0, setOf(), false, "ktmicro", true),
        Named("KT02H20 HIFI Audio", setOf(0x31B2), 0, 5, 5.0, setOf(), false, "ktmicro", true),
        Named("TANCHJIM-ONE DSP", setOf(0x31B2), 0, 5, 5.0, setOf(), false, "ktmicro", true),
        Named("TANCHJIM BUNNY DSP", setOf(0x31B2), 0, 5, 5.0, setOf(), false, "ktmicro", true),
        Named("TANCHJIM FISSION", setOf(0x31B2), 0, 5, 5.0, setOf(), false, "ktmicro", true),
        Named("TANCHJIM-FISSION  DSP", setOf(0x31B2), 0, 5, 5.0, setOf(), false, "ktmicro", true),
        Named("CDSP", setOf(0x31B2), 0, 5, 5.0, setOf(), false, "ktmicro", true),
        Named("Chu2 DSP", setOf(0x31B2), 0, 5, 5.0, setOf(), false, "ktmicro", true)
    )
    private val exclusions = listOf(
        Excluded("Old Fashioned", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("FIIO FX17", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("Rays", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("Marigold", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("MOONDROP Marigold", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("FreeDSP Pro", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("MOONRIVER 3", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("FreeDSP Mini", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("FreeDSP", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("DAWN PRO2", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("Echo A", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("ECHO-B", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("AG Rays", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("DHA15", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("Deco Audio System", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("INN Deco75-DH Audio", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("ddHiFi DSP IEM - Memory", setOf(0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D)),
        Excluded("Space Gaming IEM", setOf(0x31B2))
    )
    val rows: List<Row> = """
0x3302|0x12C1|11|documented_exact_pair_beta_candidate_requires_name_and_readback|72|WP01 - Q|
0x3302|0x1261|11|documented_exact_pair_beta_candidate_requires_name_and_readback|73|E07L|
0x3302|0x1262|11|documented_exact_pair_beta_candidate_requires_name_and_readback|74|RX3 PLUS|
0x3302|0x1251|11|documented_exact_pair_beta_candidate_requires_name_and_readback|75|WP02 - U1|
0x3302|0x13D3|11|documented_exact_pair_beta_candidate_requires_name_and_readback|76|DAE4131HM-TT|
0x3302|0x13C1|11|documented_exact_pair_beta_candidate_requires_name_and_readback|77|WP03-AQ|
0x3302|0x51C0|11|documented_exact_pair_beta_candidate_requires_name_and_readback|78|TT39510B01|
0x3302|0x1266|11|documented_exact_pair_beta_candidate_requires_name_and_readback|79|C01|
0x3302|0xEEEE|1|unknown_scheme_block|80|EQ|
0x3302|0x43D1|11|documented_exact_pair_beta_candidate_requires_name_and_readback|81|1Pro|
0x3302|0x1264|11|documented_exact_pair_beta_candidate_requires_name_and_readback|83|M416|
0x3302|0x12C0|11|documented_exact_pair_beta_candidate_requires_name_and_readback|84|WP01-Q|
0x3302|0x13D7|11|documented_exact_pair_beta_candidate_requires_name_and_readback|86|001|
0x3302|0x93D1|11|documented_exact_pair_beta_candidate_requires_name_and_readback|87|TT39493D01-JK|
0x3302|0x126B|11|documented_exact_pair_beta_candidate_requires_name_and_readback|89|CDE-1|
0x3302|0x98C0|11|documented_exact_pair_beta_candidate_requires_name_and_readback|90|TT39518B01-PRO|
0x3302|0x13D4|11|documented_exact_pair_beta_candidate_requires_name_and_readback|92|EPZ TP13|
0x3302|0x98C1|11|documented_exact_pair_beta_candidate_requires_name_and_readback|93|TT39510C01-PRO|
0x3302|0x12C3|11|documented_exact_pair_beta_candidate_requires_name_and_readback|94|DAT412BHM-TT|
0x3302|0x43D5|11|documented_exact_pair_beta_candidate_requires_name_and_readback|95|TT39510B01|
0x3302|0x12DB|11|documented_exact_pair_beta_candidate_requires_name_and_readback|96|Quark2|
0x3302|0x12C4|11|documented_exact_pair_beta_candidate_requires_name_and_readback|97|LA2108DA-011|
0x0666|0x0888|10|documented_exact_pair_beta_candidate_requires_name_and_readback|98|Need Update|
0x0661|0x0881|10|documented_exact_pair_beta_candidate_requires_name_and_readback|99|TT39518F01-PRO|
0x3302|0x12E9|11|documented_exact_pair_beta_candidate_requires_name_and_readback|100|DAT4122UA-TT|
0x3302|0x13C0|11|documented_exact_pair_beta_candidate_requires_name_and_readback|101|DAE4131HM-TT|
0x3302|0x12B3|11|documented_exact_pair_beta_candidate_requires_name_and_readback|102|K419|
0x3302|0x43E1|16|documented_exact_pair_beta_candidate_requires_name_and_readback|104|TT39518F01-PRO|
0x3302|0x12C5|11|documented_exact_pair_beta_candidate_requires_name_and_readback|105|ECHO-A|
0x3302|0x98C2|11|documented_exact_pair_beta_candidate_requires_name_and_readback|106|TT39510C01/TT39518B01|
0x3302|0x93C0|11|documented_exact_pair_beta_candidate_requires_name_and_readback|109|TT39493F01-PRO|
0x3302|0x12C6|11|documented_exact_pair_beta_candidate_requires_name_and_readback|110|KYERE HiFi Audio|
0x3302|0x93C1|11|documented_exact_pair_beta_candidate_requires_name_and_readback|111|TT39493F01-PRO|
0xB44D|0x4302|11|documented_exact_pair_beta_candidate_requires_name_and_readback|112|FHG SoundFlex Fusion Series Px|
0x3302|0x1269|11|documented_exact_pair_beta_candidate_requires_name_and_readback|113|Muse Dash&Quarks2|
0x3302|0x126C|11|documented_exact_pair_beta_candidate_requires_name_and_readback|114|BORZOI FANCIER AUDIO|
0x3302|0x12C8|11|documented_exact_pair_beta_candidate_requires_name_and_readback|115|BORZOI FANCIER AUDIO|
0x3302|0x12C9|11|documented_exact_pair_beta_candidate_requires_name_and_readback|116|DAT412BHM-TT|
0x3302|0x12CA|11|documented_exact_pair_beta_candidate_requires_name_and_readback|117|Hi-MAX|
0x3302|0x126D|11|documented_exact_pair_beta_candidate_requires_name_and_readback|118|DM7122HM-TT|
0x3302|0x00C0|11|documented_exact_pair_beta_candidate_requires_name_and_readback|119|KT0210 OTA|
0x3302|0x13DF|13|documented_exact_pair_beta_candidate_requires_name_and_readback|120|DSP Gezi(EQ FREE)|
0x3302|0x12CB|11|documented_exact_pair_beta_candidate_requires_name_and_readback|121|C01|
0x3302|0x12CC|11|documented_exact_pair_beta_candidate_requires_name_and_readback|122|DAT4125HM+P-TT|
0x0104|0x3302|11|documented_exact_pair_beta_candidate_requires_name_and_readback|123|CB5100_Dual_CS43198_TFT_EVK|
0x3302|0x0104|11|documented_exact_pair_beta_candidate_requires_name_and_readback|124|CB5100_DualCS43131_TFT|
0x3302|0x12CD|11|documented_exact_pair_beta_candidate_requires_name_and_readback|125|DUNU Headphone|
0x3302|0x43D7|16|documented_exact_pair_beta_candidate_requires_name_and_readback|126|KM-K420_2|
0x0762|0x0880|11|documented_exact_pair_beta_candidate_requires_name_and_readback|127|CB1300 384k EVK|
0x3302|0x12CE|11|documented_exact_pair_beta_candidate_requires_name_and_readback|128|EPZ G20|
0x3302|0x13A3|11|documented_exact_pair_beta_candidate_requires_name_and_readback|129|DAE4131HM-TT|
0x3302|0x13A4|11|documented_exact_pair_beta_candidate_requires_name_and_readback|130|AINR Audio|
0x3302|0x13A5|11|documented_exact_pair_beta_candidate_requires_name_and_readback|133|DAE4131HM-TT|
0x3302|0x43DC|11|documented_exact_pair_beta_candidate_requires_name_and_readback|136|SyLark-AMP01|
0x3302|0x13DC|11|documented_exact_pair_beta_candidate_requires_name_and_readback|137|MD-QT-033|
0x3302|0x43D8|16|documented_exact_pair_beta_candidate_requires_name_and_readback|139|KM-K420_1|
0x3302|0x43DF|15|potential_protocol_override_outside_family|141|ddHiFi DSP IEM - Memory|
0x3302|0x9121|11|documented_exact_pair_beta_candidate_requires_name_and_readback|142|DAT9121-TT|
0x3302|0x43E2|15|documented_exact_pair_beta_candidate_requires_name_and_readback|143|LBZ-04|
0x3302|0x43E3|15|documented_exact_pair_beta_candidate_requires_name_and_readback|144|LC7100|
0x3302|0x98D1|11|documented_exact_pair_beta_candidate_requires_name_and_readback|145|TT39518B01|
0x3302|0x98D2|11|documented_exact_pair_beta_candidate_requires_name_and_readback|146|TT39518B01-PRO|
0x3302|0x23C0|13|documented_exact_pair_beta_candidate_requires_name_and_readback|147|DAE4131HM-TT|
0x3302|0x43E4|16|documented_exact_pair_beta_candidate_requires_name_and_readback|149|KSHF-02(EQ)|
0x3302|0x98D4|16|documented_exact_pair_beta_candidate_requires_name_and_readback|150|KSHF-01(EQ)|
0x3302|0xFF01|11|documented_exact_pair_beta_candidate_requires_name_and_readback|151|DAT412BHM-TT Test|
0x3302|0x43E7|11|documented_exact_pair_beta_candidate_requires_name_and_readback|153|CELEST CD-2|
0x3302|0x43C0|16|potential_protocol_conflict_model_name_override|154|CS431XX|
0x3302|0x43E8|16|protocol_conflict_existing_TRN_hardware_claims|155|Black Pearl|
0x3302|0x98D5|11|documented_exact_pair_beta_candidate_requires_name_and_readback|156|CS43918 HiFi DSP Audio|
0x3302|0x1281|11|documented_exact_pair_beta_candidate_requires_name_and_readback|157|T10|
0x3302|0x43H1|15|invalid_identifier_block|159|TT39510F01|
0x3302|0x126E|11|documented_exact_pair_beta_candidate_requires_name_and_readback|160|DAT4127HM-JH|
0x3302|0x43C3|11|documented_exact_pair_beta_candidate_requires_name_and_readback|163|LLR03-X&Q|
0x3302|0x1282|11|documented_exact_pair_beta_candidate_requires_name_and_readback|164|LLR03-A/E|
0x3302|0x1283|11|documented_exact_pair_beta_candidate_requires_name_and_readback|165|LLR03-C&G|
0x3302|0x23C1|13|documented_exact_pair_beta_candidate_requires_name_and_readback|166|USB AI ENC Audio|
0x3302|0x43EA|15|documented_exact_pair_beta_candidate_requires_name_and_readback|168|TBD|
0x3302|0x43EB|15|unknown_potential_upstream_experimental_override|169|BGVP MX1|
0x3302|0x1284|11|documented_exact_pair_beta_candidate_requires_name_and_readback|170|LHW24-07|
0x3302|0x1285|11|documented_exact_pair_beta_candidate_requires_name_and_readback|171|LHW23-02|
0x3302|0x13A9|13|documented_exact_pair_beta_candidate_requires_name_and_readback|172|X600-ENC|
0x3302|0x1286|11|documented_exact_pair_beta_candidate_requires_name_and_readback|174|PLEXTONE JALLY|
0x3302|0x4370|15|documented_exact_pair_beta_candidate_requires_name_and_readback|175|单CS43XXX 二代HID验证|
0x3302|0x60C1|13|documented_exact_pair_beta_candidate_requires_name_and_readback|177|384kHz AI ENC Audio|
0x2FC6|0xF807|11|documented_exact_pair_beta_candidate_requires_name_and_readback|178|AE3|
0x2FC6|0xF808|16|documented_exact_pair_beta_candidate_requires_name_and_readback|179|AE6|
0x2FC6|0xF806|11|documented_exact_pair_beta_candidate_requires_name_and_readback|180|AE1|
0x3302|0x13AB|11|documented_exact_pair_beta_candidate_requires_name_and_readback|181|C-08 AI ENC|
0x3302|0x43D9|15|documented_exact_pair_beta_candidate_requires_name_and_readback|183|K423|
0x3302|0x39C1|15|unknown_potential_upstream_experimental_override|186|ES9039 |
0x3302|0x126F|11|documented_exact_pair_beta_candidate_requires_name_and_readback|189|TBD|
0x3302|0xEE10|16|documented_exact_pair_beta_candidate_requires_name_and_readback|191|10段 HIFI EQ|
0x3302|0x4352|16|documented_exact_pair_beta_candidate_requires_name_and_readback|192|KM_HA03|
0x3302|0x1272|11|documented_exact_pair_beta_candidate_requires_name_and_readback|193|DITA ANTE DAC|
0x3302|0xEE20|16|documented_exact_pair_beta_candidate_requires_name_and_readback|194|WALKPLAY-10|
0x3302|0x4357|15|documented_exact_pair_beta_candidate_requires_name_and_readback|195|Memory USB|
0x3302|0x1287|11|documented_exact_pair_beta_candidate_requires_name_and_readback|196|DOVE Hi-Fi DSP|
0x3302|0x43C5|16|documented_exact_pair_beta_candidate_requires_name_and_readback|197|Dual CS43198+Dual OPA|
0x3302|0x4353|15|unknown_potential_upstream_experimental_override|198|Dual CS43198|
0x3302|0x43E6|16|documented_exact_pair_beta_candidate_requires_name_and_readback|199|TP35 Pro|
0x3302|0x4351|16|documented_exact_pair_beta_candidate_requires_name_and_readback|200|DA5|
0x3302|0x13D9|11|documented_exact_pair_beta_candidate_requires_name_and_readback|201|SOMIC|
0x3302|0x43DE|16|documented_exact_pair_beta_candidate_requires_name_and_readback|202|Campfire Audio|
0x3302|0x4358|16|documented_exact_pair_beta_candidate_requires_name_and_readback|203|G6|
0x3302|0x4359|16|documented_exact_pair_beta_candidate_requires_name_and_readback|204|G303|
0x3302|0x60E1|13|documented_exact_pair_beta_candidate_requires_name_and_readback|205|TINHIFI AI MIC PRO|
0x3302|0x128A|11|documented_exact_pair_beta_candidate_requires_name_and_readback|206|Titan s2|
0x3302|0x128B|11|documented_exact_pair_beta_candidate_requires_name_and_readback|207|Titan X|
0x3302|0x128C|11|documented_exact_pair_beta_candidate_requires_name_and_readback|208|KSHF-D01/D02(EQ)|
0x3302|0x128D|11|documented_exact_pair_beta_candidate_requires_name_and_readback|209|YunWired1|
0x3302|0x43DB|16|documented_exact_pair_beta_candidate_requires_name_and_readback|211|TC44Grip|
0x3302|0x13AC|13|documented_exact_pair_beta_candidate_requires_name_and_readback|212|USB AI ENC Audio|
0x3302|0x435A|16|documented_exact_pair_beta_candidate_requires_name_and_readback|217|KM_HA04_Pro|
0x3302|0x128E|11|documented_exact_pair_beta_candidate_requires_name_and_readback|218|YTA-UC35|
0x3302|0x4355|16|documented_exact_pair_beta_candidate_requires_name_and_readback|219|TBD|
0x3302|0x13AE|13|documented_exact_pair_beta_candidate_requires_name_and_readback|220|Eagle|
0x3302|0x435C|16|potential_protocol_conflict_model_name_override|221|DT04|
0x3302|0x435D|16|documented_exact_pair_beta_candidate_requires_name_and_readback|222|AFUL SnowyNight Pro|
0x3302|0x435E|16|documented_exact_pair_beta_candidate_requires_name_and_readback|225|BQL001|
0x3302|0x43C1|11|documented_exact_pair_beta_candidate_requires_name_and_readback|226|JM20PRO|
0x3302|0x43EF|16|documented_exact_pair_beta_candidate_requires_name_and_readback|227|LC01|
0x3302|0x128F|11|documented_exact_pair_beta_candidate_requires_name_and_readback|230|G30|
0x3302|0x43EC|16|documented_exact_pair_beta_candidate_requires_name_and_readback|233|Sky-reaching Sword|
0x3302|0x4361|16|documented_exact_pair_beta_candidate_requires_name_and_readback|234|Dual CS43131 HiFi DSP Audio|
0x3302|0x1293|11|documented_exact_pair_beta_candidate_requires_name_and_readback|235|利维坦2A|
0x3302|0x39C2|18|documented_exact_pair_beta_candidate_requires_name_and_readback|237|ES9039Q2M|
0x3302|0x4363|16|documented_exact_pair_beta_candidate_requires_name_and_readback|238|CS43198*2+OPA*2|
0x3302|0x1294|11|documented_exact_pair_beta_candidate_requires_name_and_readback|239|G28 Pro|
0x3302|0x4366|16|documented_exact_pair_beta_candidate_requires_name_and_readback|240|TINHIFI Pulse Pro |
0x3302|0x4364|16|documented_exact_pair_beta_candidate_requires_name_and_readback|241|DA7|
0x3302|0x1295|11|documented_exact_pair_beta_candidate_requires_name_and_readback|242|TEARS DSP-W|
0x3302|0x1296|11|documented_exact_pair_beta_candidate_requires_name_and_readback|243|TEARS DSP-B|
0x3302|0x1297|11|documented_exact_pair_beta_candidate_requires_name_and_readback|244|TINHIFI C1|
0x3302|0x1298|11|documented_exact_pair_beta_candidate_requires_name_and_readback|245|ET-01|
0x3302|0x13B0|13|documented_exact_pair_beta_candidate_requires_name_and_readback|247|SOMIC|
0x3302|0x4360|16|documented_exact_pair_beta_candidate_requires_name_and_readback|248|EF18pro|
0x3302|0x13B2|13|documented_exact_pair_beta_candidate_requires_name_and_readback|251|X600U-ENC|
0x3302|0x4382|16|documented_exact_pair_beta_candidate_requires_name_and_readback|252|DTC 500 X|
0x3302|0x20EE|17|documented_exact_pair_beta_candidate_requires_name_and_readback|253|KT02H20P with EQ|
0x3302|0x4383|16|documented_exact_pair_beta_candidate_requires_name_and_readback|254|TP55|
0x35D8|0x011B|13|documented_exact_pair_beta_candidate_requires_name_and_readback|258|aaaaaa|
0x3302|0x4386|16|documented_exact_pair_beta_candidate_requires_name_and_readback|260|CS43131|
0x3302|0x39C4|18|documented_exact_pair_beta_candidate_requires_name_and_readback|261|CD-30|
0x3302|0x13AF|13|documented_exact_pair_beta_candidate_requires_name_and_readback|263|LX-HE08|
0x3302|0x43C6|16|potential_protocol_conflict_model_name_override|264|CS43198|
0x3302|0x43C7|16|documented_exact_pair_beta_candidate_requires_name_and_readback|265|TBD|
0x35D8|0x011D|16|documented_exact_pair_beta_candidate_requires_name_and_readback|267|1|
0x3302|0x43C8|16|documented_exact_pair_beta_candidate_requires_name_and_readback|268|CD-10 PRO|
0x35D8|0x43DA|16|potential_protocol_override_outside_family|270|MOONRIVER 3|
0x3302|0x1278|11|documented_exact_pair_beta_candidate_requires_name_and_readback|271|SIMGOT EG280|
0x3302|0x20EF|17|documented_exact_pair_beta_candidate_requires_name_and_readback|274|Audiocular|
0x3302|0x13B1|13|documented_exact_pair_beta_candidate_requires_name_and_readback|275|LX-HE05|
0x3302|0x20E1|17|documented_exact_pair_beta_candidate_requires_name_and_readback|276|SIMGOT DEW0S|
0x3302|0x9123|11|documented_exact_pair_beta_candidate_requires_name_and_readback|277|USB C Coaxial|
0x3302|0x1299|11|documented_exact_pair_beta_candidate_requires_name_and_readback|278|G38|
0x3302|0x23EE|19|documented_exact_pair_beta_candidate_requires_name_and_readback|280|KT0231HP DSP|
0x3302|0x43C9|16|documented_exact_pair_beta_candidate_requires_name_and_readback|281|Audiocular-Aura|
0x3302|0x43CA|16|documented_exact_pair_beta_candidate_requires_name_and_readback|283|USB HiFi DSP Audio|
0x3302|0x126A|11|documented_exact_pair_beta_candidate_requires_name_and_readback|284|TFZ-DSP|
0x3302|0x127D|11|documented_exact_pair_beta_candidate_requires_name_and_readback|286|Cipher|
0x3302|0x43CB|15|documented_exact_pair_beta_candidate_requires_name_and_readback|287|1|
0x3302|0x43CC|16|documented_exact_pair_beta_candidate_requires_name_and_readback|289|Protocol Max|
0x3302|0x43CD|16|documented_exact_pair_beta_candidate_requires_name_and_readback|290|AURORA|
0x3302|0x13B9|13|documented_exact_pair_beta_candidate_requires_name_and_readback|291|OA-25009|
0x3302|0x44D1|18|documented_exact_pair_beta_candidate_requires_name_and_readback|292|Truthear 4493S-2|
0x3302|0x43CF|16|documented_exact_pair_beta_candidate_requires_name_and_readback|293|Hakugei-DACpro|
0x3302|0x13B7|20|documented_exact_pair_beta_candidate_requires_name_and_readback|294|CB1300_EQ_BAND10|
0x3302|0x4362|15|documented_exact_pair_beta_candidate_requires_name_and_readback|295|1|
0x3302|0x43B1|16|documented_exact_pair_beta_candidate_requires_name_and_readback|296|KSHF-02 Pro(EQ)|
0x3302|0x13BA|13|documented_exact_pair_beta_candidate_requires_name_and_readback|297|X1stmic-A|
0x3302|0x13BB|13|documented_exact_pair_beta_candidate_requires_name_and_readback|298|XuanJingF1|
0x3302|0x129C|11|documented_exact_pair_beta_candidate_requires_name_and_readback|300|TBD|
0x34BE|0x0004|11|documented_exact_pair_beta_candidate_requires_name_and_readback|301|TBD|
0x3302|0x44D2|18|documented_exact_pair_beta_candidate_requires_name_and_readback|302|AK4493SEQ|
0x3302|0x13BF|13|documented_exact_pair_beta_candidate_requires_name_and_readback|303|XIBERIA SHADOW|
0x3302|0x1320|13|documented_exact_pair_beta_candidate_requires_name_and_readback|304|T6|
0x3302|0x1230|11|documented_exact_pair_beta_candidate_requires_name_and_readback|305|HUAN|
0x3302|0x129F|11|documented_exact_pair_beta_candidate_requires_name_and_readback|306|Rightear|
0x3302|0x1321|13|documented_exact_pair_beta_candidate_requires_name_and_readback|307|U-02|
0x3302|0x1231|11|documented_exact_pair_beta_candidate_requires_name_and_readback|308|TBD|
0x3302|0x127A|11|documented_exact_pair_beta_candidate_requires_name_and_readback|309|Microphone|
0x3302|0x201D|17|documented_exact_pair_beta_candidate_requires_name_and_readback|310|Note|
0x3302|0x1233|11|documented_exact_pair_beta_candidate_requires_name_and_readback|311|G10|
0x3302|0x13B4|13|documented_exact_pair_beta_candidate_requires_name_and_readback|312|1|
0x35D8|0x98D5|11|documented_exact_pair_beta_candidate_requires_name_and_readback|313|MD-QT-033|
0x3302|0x43C2|16|documented_exact_pair_beta_candidate_requires_name_and_readback|314|HiFi DSP Audio Pro|
0x3302|0x39C6|18|documented_exact_pair_beta_candidate_requires_name_and_readback|315|XUANWU|
0x3302|0x43D6|15|documented_exact_pair_beta_candidate_requires_name_and_readback|316|Audiocular D11|
0x3302|0x1323|20|documented_exact_pair_beta_candidate_requires_name_and_readback|317|USB DSP Audio - 10EQ|
0x3302|0x44D3|18|documented_exact_pair_beta_candidate_requires_name_and_readback|318|Truthear 4493S-2|
0x0666|0x0883|11|protocol_conflict_catalog_vs_first_PID_match|320|SPV6040 10-EQ|
0x3302|0x127E|11|documented_exact_pair_beta_candidate_requires_name_and_readback|322|G99P USB Audio|
0x3302|0x1326|13|documented_exact_pair_beta_candidate_requires_name_and_readback|323|ToneSphere_E30T|
0x3302|0x43B7|16|documented_exact_pair_beta_candidate_requires_name_and_readback|324|HC02|
0x3302|0x43B8|16|documented_exact_pair_beta_candidate_requires_name_and_readback|325|HC03|
0x3302|0x20FF|17|documented_exact_pair_beta_candidate_requires_name_and_readback|326|DAT420PHM-TT-KEY|
0x3302|0x20E2|17|documented_exact_pair_beta_candidate_requires_name_and_readback|327|OG10|
0x3302|0x13B6|13|documented_exact_pair_beta_candidate_requires_name_and_readback|331|THUNDEROBOT HG50|
0x3302|0x20E3|17|documented_exact_pair_beta_candidate_requires_name_and_readback|332|AD1|
0x373B|0x120C|13|documented_exact_pair_beta_candidate_requires_name_and_readback|334|ATK Horizon|
0x3302|0x123F|11|documented_exact_pair_beta_candidate_requires_name_and_readback|335|XIBERIA  X-Blade|
0x3302|0x1240|11|documented_exact_pair_beta_candidate_requires_name_and_readback|336|Colorful Sigapes Audio|
0x3302|0x1241|11|documented_exact_pair_beta_candidate_requires_name_and_readback|337|TBD|
0x3302|0x4380|16|documented_exact_pair_beta_candidate_requires_name_and_readback|338|Rouyin-shuimo|
0x3302|0x43B6|16|documented_exact_pair_beta_candidate_requires_name_and_readback|339|TBD|
0x3302|0x23E2|19|documented_exact_pair_beta_candidate_requires_name_and_readback|340|TBD|
0x3302|0x1327|13|documented_exact_pair_beta_candidate_requires_name_and_readback|341|Leviathan2|
0x3302|0x1328|13|documented_exact_pair_beta_candidate_requires_name_and_readback|342|TBD|
0x011D|0x35D8|15|documented_exact_pair_beta_candidate_requires_name_and_readback|343|1|
0x3302|0x20E5|17|documented_exact_pair_beta_candidate_requires_name_and_readback|344|KT02H20P+OPA|
0x3302|0x43BE|16|documented_exact_pair_beta_candidate_requires_name_and_readback|345|H800|
0x3302|0x13BE|13|documented_exact_pair_beta_candidate_requires_name_and_readback|346|M762 Ultra|
0x0663|0x08F2|21|documented_exact_pair_beta_candidate_requires_name_and_readback|348|CB1300D 7.1 TEST|
0x3302|0x3DC1|21|documented_exact_pair_beta_candidate_requires_name_and_readback|349|CB1300D CH7.1|
0x3302|0x1243|11|documented_exact_pair_beta_candidate_requires_name_and_readback|350|HuoMangXing|
0x3302|0x43BF|16|documented_exact_pair_beta_candidate_requires_name_and_readback|351|CS43918+SGM8262|
0x3302|0x1292|11|documented_exact_pair_beta_candidate_requires_name_and_readback|352|CB1200AU|
0x3302|0x1244|11|documented_exact_pair_beta_candidate_requires_name_and_readback|353|CANYON QD03|
0x3302|0x1245|11|documented_exact_pair_beta_candidate_requires_name_and_readback|354|CANYON QD03|
0x3302|0x1329|13|documented_exact_pair_beta_candidate_requires_name_and_readback|355|CB1300|
0x3302|0x13F2|21|documented_exact_pair_beta_candidate_requires_name_and_readback|357|CB1300D 7.1CH|
0x3302|0x39C7|18|documented_exact_pair_beta_candidate_requires_name_and_readback|358|Seerish.17|
0x3302|0x3DC4|21|documented_exact_pair_beta_candidate_requires_name_and_readback|359|CB1300D CH7.1 + PD60W|
0x0663|0x0880|21|protocol_conflict_catalog_vs_first_PID_match|360|1|
0x36A7|0xA862|11|documented_exact_pair_beta_candidate_requires_name_and_readback|361|WL HUAN IEM|
0x35D8|0x0123|13|documented_exact_pair_beta_candidate_requires_name_and_readback|362|仅验证用|
0x3302|0x4301|16|documented_exact_pair_beta_candidate_requires_name_and_readback|363|CS43198|
0x3302|0x1248|11|documented_exact_pair_beta_candidate_requires_name_and_readback|364|icon|
0x3302|0x124B|11|documented_exact_pair_beta_candidate_requires_name_and_readback|365|VIDVIE-2026|
0x3302|0x43BC|16|documented_exact_pair_beta_candidate_requires_name_and_readback|366|Verum Motus|
0x3302|0x124C|11|documented_exact_pair_beta_candidate_requires_name_and_readback|367|Ocean’s Tear|
0x3302|0x124D|11|documented_exact_pair_beta_candidate_requires_name_and_readback|368|TFZ COCO-V1|
0x3302|0x1249|11|documented_exact_pair_beta_candidate_requires_name_and_readback|370|TBD|
0x3302|0x129B|11|documented_exact_pair_beta_candidate_requires_name_and_readback|371|Colorful Sigapes Audio|
0x3302|0x124A|11|documented_exact_pair_beta_candidate_requires_name_and_readback|372|SIVGA-SM100|
0x3302|0x129A|11|documented_exact_pair_beta_candidate_requires_name_and_readback|373|Colorful Sigapes Audio|
0x3302|0x4302|16|protocol_conflict_catalog_vs_first_PID_match|374|HDK-224|
0x3302|0x4304|16|documented_exact_pair_beta_candidate_requires_name_and_readback|375|evoX HiFi DSP Audio|
0x3302|0x4305|16|documented_exact_pair_beta_candidate_requires_name_and_readback|376|N3 HiFi DSP Audio|
0x3302|0x132A|13|documented_exact_pair_beta_candidate_requires_name_and_readback|378|XuanJing Pro|
0x3302|0x124E|11|documented_exact_pair_beta_candidate_requires_name_and_readback|379|HD10|
0x3302|0x231E|19|documented_exact_pair_beta_candidate_requires_name_and_readback|380|PLEXTONE-AKM|
0x3302|0x4306|16|documented_exact_pair_beta_candidate_requires_name_and_readback|381|USB Audio-CUBE|
0x3302|0x20E8|17|documented_exact_pair_beta_candidate_requires_name_and_readback|382|HDK224-1|
0x3302|0x60C0|13|documented_exact_pair_beta_candidate_requires_name_and_readback|383|DAE4131HM-TT|
0x3302|0x2320|19|documented_exact_pair_beta_candidate_requires_name_and_readback|384|iKF USB-C Audio|
0x3302|0x20EC|17|documented_exact_pair_beta_candidate_requires_name_and_readback|385|BQEYZ C30|
0x3302|0x2030|17|documented_exact_pair_beta_candidate_requires_name_and_readback|386|CD-3|
0x3302|0x231F|19|documented_exact_pair_beta_candidate_requires_name_and_readback|387|PLEXTONE Audio|
0x3302|0x60D1|13|documented_exact_pair_beta_candidate_requires_name_and_readback|389|USB AI ENC Audio|
0x3302|0x2323|19|documented_exact_pair_beta_candidate_requires_name_and_readback|390|CANYON QD03|
0x3302|0x430D|16|documented_exact_pair_beta_candidate_requires_name_and_readback|391|YOURAN-D43198 PRO |
0x3302|0x430E|16|documented_exact_pair_beta_candidate_requires_name_and_readback|394|YOURAN-D43198 PRO MAX|
0x3302|0x2010|17|documented_exact_pair_beta_candidate_requires_name_and_readback|395|AZLA-SMART DAC|
0x3302|0x60C3|13|documented_exact_pair_beta_candidate_requires_name_and_readback|396|384kHz AI ENC Audio|
0x3302|0x430F|16|documented_exact_pair_beta_candidate_requires_name_and_readback|397|BLUE ROSE|
0x3302|0xC204|11|documented_exact_pair_beta_candidate_requires_name_and_readback|398|YeYing2|
0x3302|0x1288|11|documented_exact_pair_beta_candidate_requires_name_and_readback|400|betavo|
0x3302|0x1289|11|documented_exact_pair_beta_candidate_requires_name_and_readback|401|EVAN|
0x3302|0xC207|11|documented_exact_pair_beta_candidate_requires_name_and_readback|402|J10|
0x3302|0xC208|11|documented_exact_pair_beta_candidate_requires_name_and_readback|403|384kHz 32bit|
0x3302|0x9124|11|documented_exact_pair_beta_candidate_requires_name_and_readback|404|USB-C SPDIF|
0x3302|0x9125|11|documented_exact_pair_beta_candidate_requires_name_and_readback|405|TAC550|
0x35D8|0x012A|15|documented_exact_pair_beta_candidate_requires_name_and_readback|407|TBD|
0x3302|0x60D2|11|documented_exact_pair_beta_candidate_requires_name_and_readback|408|USB AI ENC Audio|
0x3302|0xC209|11|documented_exact_pair_beta_candidate_requires_name_and_readback|409|TC50 DSP|
0x3302|0xC20A|11|documented_exact_pair_beta_candidate_requires_name_and_readback|410|DSP.H.268|
0x3302|0x20E7|17|documented_exact_pair_beta_candidate_requires_name_and_readback|411|USB Audio|
0x3302|0x20EA|17|documented_exact_pair_beta_candidate_requires_name_and_readback|412|Audiocular C18|
0x3302|0x4312|16|documented_exact_pair_beta_candidate_requires_name_and_readback|413|Oshun DECO|
0x3302|0x201E|17|documented_exact_pair_beta_candidate_requires_name_and_readback|414|USB Audio Pro|
0x3302|0x44D6|18|documented_exact_pair_beta_candidate_requires_name_and_readback|416|AK4493SEQ HiFi DSP Audio|
0x3302|0xC20F|11|existing_Micro_preserve_raw_codec|417|Protocol Micro|
0x3302|0x39C9|18|documented_exact_pair_beta_candidate_requires_name_and_readback|419|TBD|
0x3302|0x4313|16|documented_exact_pair_beta_candidate_requires_name_and_readback|420|Cube01|
0x3302|0x2036|17|documented_exact_pair_beta_candidate_requires_name_and_readback|421|Stealthbite DAC|
0x3302|0x2DC1|17|documented_exact_pair_beta_candidate_requires_name_and_readback|422|KT02H20P USB Audio|
0x3302|0x4381|16|documented_exact_pair_beta_candidate_requires_name_and_readback|423|TBD|
0x3302|0x4316|16|documented_exact_pair_beta_candidate_requires_name_and_readback|424|TP16|
0x3302|0xC211|11|documented_exact_pair_beta_candidate_requires_name_and_readback|425|USB Audio|
0x3302|0x2038|17|documented_exact_pair_beta_candidate_requires_name_and_readback|426|EI-01|
0x3302|0x1237|11|documented_exact_pair_beta_candidate_requires_name_and_readback|427|WHIZZER BEAT DAC|
0x3302|0xC214|11|documented_exact_pair_beta_candidate_requires_name_and_readback|428|NineMeet-JiuPai|
0x3302|0x203A|17|documented_exact_pair_beta_candidate_requires_name_and_readback|429|白羽|
0x3302|0xC212|11|documented_exact_pair_beta_candidate_requires_name_and_readback|430|SIGMOT|
0x3302|0xC213|11|documented_exact_pair_beta_candidate_requires_name_and_readback|431|ZS-01|
0x3302|0x132B|11|documented_exact_pair_beta_candidate_requires_name_and_readback|432|OTA用|
0x3302|0x4319|16|documented_exact_pair_beta_candidate_requires_name_and_readback|433|CS43198 + AD8397|
0x3302|0x1333|13|documented_exact_pair_beta_candidate_requires_name_and_readback|434|AUSDOM|
0x3302|0x39CD|18|documented_exact_pair_beta_candidate_requires_name_and_readback|435|AX8|
0x3302|0xC215|11|documented_exact_pair_beta_candidate_requires_name_and_readback|436|TBD|
0x3302|0xC217|11|documented_exact_pair_beta_candidate_requires_name_and_readback|437|G20 Pro|
0x3302|0x3DC9|21|documented_exact_pair_beta_candidate_requires_name_and_readback|438|骨传导录音EQ用|
0x3302|0x4318|16|documented_exact_pair_beta_candidate_requires_name_and_readback|439|TBD|
0x3302|0x1332|13|documented_exact_pair_beta_candidate_requires_name_and_readback|441|BS29 ENC|
0x3302|0xC219|11|catalog_only_documented_scheme_read_first|442|K300PRO|
0x3302|0x431D|16|documented_exact_pair_beta_candidate_requires_name_and_readback|443|Hercules DAC|
0x3302|0xC216|11|catalog_only_documented_scheme_read_first|444|HiFi-LC|
0x3302|0x8901|24|unknown_scheme_block|445|USB DSP Audio|
0x3302|0xC21C|11|catalog_only_documented_scheme_read_first|447|6060|
0x3302|0xC21F|11|catalog_only_documented_scheme_read_first|448|Orbit|
0x3302|0xC223|11|catalog_only_documented_scheme_read_first|449|XJ01|
0x3302|0xC210|11|catalog_only_documented_scheme_read_first|450|XuanJingF1|
0x3302|0x232B|19|documented_exact_pair_beta_candidate_requires_name_and_readback|451|611|
0x3302|0x232C|19|documented_exact_pair_beta_candidate_requires_name_and_readback|452|612|
0x3302|0xC224|11|catalog_only_documented_scheme_read_first|453|G-Turbo X1|
0x3302|0x39CE|18|documented_exact_pair_beta_candidate_requires_name_and_readback|455|ARS-A-01|
0x3302|0x3DCD|21|documented_exact_pair_beta_candidate_requires_name_and_readback|457|BGVP M01|
0x3302|0x3DCC|21|documented_exact_pair_beta_candidate_requires_name_and_readback|458|USB 7.1CH AUDIO|
0x3302|0x3DC7|21|documented_exact_pair_beta_candidate_requires_name_and_readback|459|TBD|
0x3302|0x3DC5|21|documented_exact_pair_beta_candidate_requires_name_and_readback|460|USB 7.1CH AUDIO|
0x3302|0x3DC6|21|documented_exact_pair_beta_candidate_requires_name_and_readback|461|CB1300D DSP 7.1CH|
0x3302|0x3DCA|21|documented_exact_pair_beta_candidate_requires_name_and_readback|462|GS35|
0x3302|0xC227|11|catalog_only_documented_scheme_read_first|463|ET-01|
0x3302|0xC228|11|catalog_only_documented_scheme_read_first|464|XIBERIA MX06|
0x3302|0x60C6|13|documented_exact_pair_beta_candidate_requires_name_and_readback|465|K67|
0x3302|0x44D7|18|documented_exact_pair_beta_candidate_requires_name_and_readback|466|WHIZZER DA6+|
0x3302|0x60C7|13|documented_exact_pair_beta_candidate_requires_name_and_readback|467|TBD|
0x3302|0x4321|16|documented_exact_pair_beta_candidate_requires_name_and_readback|468|F4|
0x3302|0xC22B|11|catalog_only_documented_scheme_read_first|469|Orion|
0x3302|0x4324|16|documented_exact_pair_beta_candidate_requires_name_and_readback|470|BGVP MX1 Pro|
0x3302|0x8902|24|unknown_scheme_block|471|USB Audio|
0x3302|0x4323|16|documented_exact_pair_beta_candidate_requires_name_and_readback|472|HF001|
0x3302|0x203E|17|documented_exact_pair_beta_candidate_requires_name_and_readback|473|DAT4205HM+P-TT|
0x3302|0x3DC8|21|documented_exact_pair_beta_candidate_requires_name_and_readback|474|X4-1&2|
0x3302|0x60C9|13|documented_exact_pair_beta_candidate_requires_name_and_readback|475|C7|
0x3302|0x4325|16|documented_exact_pair_beta_candidate_requires_name_and_readback|476|TP55 PRO|
0x3302|0x2040|17|documented_exact_pair_beta_candidate_requires_name_and_readback|477|RY-01|
0x3302|0xC22C|11|catalog_only_documented_scheme_read_first|478|H-Turbo S1|
0x3302|0x39A0|18|documented_exact_pair_beta_candidate_requires_name_and_readback|479|ES9039Q2M+SGM8262*2|
0x3302|0x1334|13|documented_exact_pair_beta_candidate_requires_name_and_readback|480|TBD|
0x373B|0x129F|19|protocol_conflict_catalog_vs_first_PID_match|481|ATK Horizon|
0x3302|0xC229|11|catalog_only_documented_scheme_read_first|483|1|
0x3302|0x39CF|18|documented_exact_pair_beta_candidate_requires_name_and_readback|484|K605|
0x3302|0x9201|16|documented_exact_pair_beta_candidate_requires_name_and_readback|485|TT39219J01|
0x3302|0x3DB0|21|documented_exact_pair_beta_candidate_requires_name_and_readback|486|SK02|
0x3302|0xC230|11|catalog_only_documented_scheme_read_first|487|DITA|
0x3302|0x39A2|18|documented_exact_pair_beta_candidate_requires_name_and_readback|488|S1|
0x3302|0x39E0|16|documented_exact_pair_beta_candidate_requires_name_and_readback|489|K605|
0x3302|0x2043|17|documented_exact_pair_beta_candidate_requires_name_and_readback|490|MUSE HiFi U3|
0x3302|0xC233|11|catalog_only_documented_scheme_read_first|491|H-Turbo S2|
0x3302|0xC234|11|catalog_only_documented_scheme_read_first|492|Song 夏鸣|
0x3302|0x4326|16|documented_exact_pair_beta_candidate_requires_name_and_readback|493|TBD|
0x3302|0x39A3|18|documented_exact_pair_beta_candidate_requires_name_and_readback|494|ET1|
0x0909|0x39C8|18|documented_exact_pair_beta_candidate_requires_name_and_readback|496|ATT-PHA002|
0x3302|0x4329|16|documented_exact_pair_beta_candidate_requires_name_and_readback|497|DUYOU CS43198 PRO MAX|
0x3302|0x432A|16|documented_exact_pair_beta_candidate_requires_name_and_readback|498|DUYOU CS43198 PRO MAX|
0x3302|0x2326|19|documented_exact_pair_beta_candidate_requires_name_and_readback|499|EWEADN-VX07|
0x3302|0xC22D|11|catalog_only_documented_scheme_read_first|500|WHIZZER BEAT DAC|
0x3302|0x2044|17|documented_exact_pair_beta_candidate_requires_name_and_readback|501|VY-C-MIC01|
0x3302|0x1330|13|documented_exact_pair_beta_candidate_requires_name_and_readback|502|X1 stmic-I|
0x3302|0x60CA|13|documented_exact_pair_beta_candidate_requires_name_and_readback|504|X1 Audio-1|
0x3302|0xB301|13|documented_exact_pair_beta_candidate_requires_name_and_readback|506|CB1300 + BLE|
0x3302|0x4311|16|documented_exact_pair_beta_candidate_requires_name_and_readback|507|Azure Cloud Sword|
0x3302|0x60CB|13|documented_exact_pair_beta_candidate_requires_name_and_readback|508|FUMO MIX|
0x3302|0xC232|11|catalog_only_documented_scheme_read_first|509|1|
0x3302|0x4322|16|documented_exact_pair_beta_candidate_requires_name_and_readback|510|TBD|
0x3302|0x432C|16|documented_exact_pair_beta_candidate_requires_name_and_readback|511|TBD|
0x3302|0x2048|17|documented_exact_pair_beta_candidate_requires_name_and_readback|512|Turbo 5 UAC2.0|
0x3302|0x2049|17|documented_exact_pair_beta_candidate_requires_name_and_readback|513|Turbo 5 UAC1.0|
0x3302|0xB302|13|documented_exact_pair_beta_candidate_requires_name_and_readback|514|CB1300 方案13 测试有线HID|
0x3302|0x4327|16|documented_exact_pair_beta_candidate_requires_name_and_readback|515|U-CUBE|
0x3302|0x44D9|18|documented_exact_pair_beta_candidate_requires_name_and_readback|516|AFUL EMBER-01|
0x3302|0xC22F|11|catalog_only_documented_scheme_read_first|517|CABLE DAC|
0x3302|0x2321|19|documented_exact_pair_beta_candidate_requires_name_and_readback|518|KT0231HP|
0x3302|0x2334|19|documented_exact_pair_beta_candidate_requires_name_and_readback|519|KT0231HP+MAX97220|
""".trim().lines().map { line ->
        val p = line.split('|', limit = 7)
        Row(p[0], p[1], p[2].toInt(), p[3], p[4].toInt(), p[5])
    }
    val literalNames: List<String> get() = names.map { it.name }
    private val capturedPairs = setOf(0x3302 to 0x39C3, 0x3302 to 0x4357, 0x3302 to 0x4367, 0x3302 to 0x43CC, 0x3302 to 0x43D4, 0x3302 to 0x51C0)
    private val pairs = rows.filter { it.valid }.associateBy { it.vid!! to it.pid!! }
    fun candidate(vid: Int, pid: Int, name: String?, advanced: Boolean): Boolean =
        (vid == 0x3302 && pid == 0xC20F) || (vid == 0x3302 && pid == 0x43CC) || (advanced && ((vid == 0x3302 && pid == 0x43E8) ||
            (vid to pid) in pairs || (vid to pid) in capturedPairs || names.any { it.family == "walkplay" && vid in it.vendors && it.name == name }))

    fun resolve(vid: Int, pid: Int, name: String?, advanced: Boolean): Resolution {
        if (vid == 0x3302 && pid == 0xC20F) return Resolution.Ready(DeviceProtocol.MICRO)
        // CrinEar Protocol Max: the stable 1.3.0 MAX target in every build, before the catalog path (no name needed).
        if (vid == 0x3302 && pid == 0x43CC) return Resolution.Ready(DeviceProtocol.MAX)
        if (!advanced) return Resolution.Unknown
        if (vid == 0x3302 && pid == 0x43E8) return Resolution.Ready(DeviceProtocol.TRN)
        val row = pairs[vid to pid]
        if (name.isNullOrEmpty()) return if (row != null || (vid to pid) in capturedPairs) Resolution.NeedsName else Resolution.Unknown
        if (exclusions.any { it.name == name && vid in it.vendors }) return Resolution.Blocked("Exact USB name routes to another handler")
        val named = names.singleOrNull { it.name == name && vid in it.vendors }
        if (named?.family == "ktmicro") return Resolution.Blocked("KT Micro requires its separate driver; WalkPlay disabled")
        if (row != null && row.decision !in setOf("documented_exact_pair_beta_candidate_requires_name_and_readback",
                "catalog_only_documented_scheme_read_first", "existing_Micro_preserve_raw_codec")) {
            // Experimental names with identical recipes are admissible; conflicts/unknown schemes never are.
            if (row.decision != "unknown_potential_upstream_experimental_override" || named == null || named.scheme != row.scheme)
                return Resolution.Blocked(row.decision)
        }
        if (named != null) {
            val scheme = if (named.inheritedScheme && row != null) row.scheme else named.scheme
            return Resolution.Ready(spec(vid, pid, named.name, scheme, named.bands, named.qMax, named.types, named.upstreamExperimental))
        }
        if (row == null) return Resolution.Unknown
        return schemeSpec(vid, pid, row.label, row.scheme)?.let { Resolution.Ready(it) }
            ?: Resolution.Blocked("Unknown WalkPlay scheme")
    }
    private fun spec(vid: Int, pid: Int, name: String, scheme: Int, bands: Int, qMax: Double, native: Set<Int>, upstreamExperimental: Boolean = false): DeviceProtocol {
        val types = native.mapNotNull { when (it) {
            1 -> FilterType.LOW_SHELF; 2 -> FilterType.PEAK; 3 -> FilterType.HIGH_SHELF; else -> null
        } }.toSet()
        return DeviceProtocol.walkplay(ProtocolMicro.CAPABILITIES.copy(name = name, vendorId = vid, productId = pid,
            bands = bands, qMax = qMax, types = types), scheme, upstreamExperimental)
    }
    private fun schemeSpec(vid: Int, pid: Int, name: String, scheme: Int): DeviceProtocol? {
        val bands = when (scheme) { 17 -> 5; 19 -> 6; 10, 11, 15, 21 -> 8; 13, 16, 18, 20 -> 10; else -> return null }
        val native = when (scheme) { 10, 20, 21 -> setOf(2); 11 -> setOf(1, 2); else -> setOf(1, 2, 3) }
        return spec(vid, pid, name, scheme, bands, 10.0, native)
    }

    /** Strict HID short-item parser. Accept only unambiguous vendor Input + Output report 4B;
     * sizes include the report ID. A long/malformed item or ambiguous collection fails closed. */
    data class Reports(val inputBytes: Int, val outputBytes: Int)
    fun reports(descriptor: ByteArray): Reports {
        data class Global(val size: Int = 0, val count: Int = 0, val id: Int = 0, val page: Int = 0)
        var g = Global(); val stack = ArrayList<Global>(); val collections = ArrayList<Boolean>()
        val bits = HashMap<Pair<Int, Int>, Int>(); val vendor = HashMap<Pair<Int, Int>, Boolean>()
        val owners = HashMap<Pair<Int, Int>, Int>()
        val ownerStack = ArrayList<Int>(); var nextOwner = 0
        var i = 0
        while (i < descriptor.size) {
            val prefix = descriptor[i++].toInt() and 255
            require(prefix != 0xFE) { "Unsupported HID long item" }
            val size = when (prefix and 3) { 3 -> 4; else -> prefix and 3 }
            require(i + size <= descriptor.size) { "Truncated HID descriptor" }
            var value = 0
            repeat(size) { value = value or ((descriptor[i++].toInt() and 255) shl (8 * it)) }
            when (prefix and 0xFC) {
                0x04 -> g = g.copy(page = value)
                0x74 -> g = g.copy(size = value)
                0x94 -> g = g.copy(count = value)
                0x84 -> { require(value in 1..255); g = g.copy(id = value) }
                0xA4 -> stack.add(g)
                0xB4 -> { require(stack.isNotEmpty()); g = stack.removeAt(stack.lastIndex) }
                0xA0 -> {
                    ownerStack.add(ownerStack.firstOrNull() ?: ++nextOwner)
                    collections.add(g.page in 0xFF00..0xFFFF || collections.lastOrNull() == true)
                }
                0xC0 -> { require(collections.isNotEmpty()); collections.removeAt(collections.lastIndex); ownerStack.removeAt(ownerStack.lastIndex) }
                0x80, 0x90 -> {
                    require(g.size in 1..32 && g.count in 1..8192 && collections.isNotEmpty())
                    val key = g.id to (prefix and 0xFC)
                    val owner = ownerStack.first()
                    require(key !in owners || owners[key] == owner) { "Ambiguous HID report collection" }
                    owners[key] = owner
                    bits[key] = (bits[key] ?: 0) + g.size * g.count
                    vendor[key] = (vendor[key] ?: true) && (collections.last() || g.page in 0xFF00..0xFFFF)
                }
            }
        }
        require(stack.isEmpty() && collections.isEmpty()) { "Unclosed HID descriptor state" }
        fun bytes(kind: Int): Int {
            val key = 0x4B to kind; val b = bits[key] ?: error("Missing report 4B")
            require(vendor[key] == true && b % 8 == 0 && b / 8 in 37..1023) { "Invalid vendor report 4B length" }
            return 1 + b / 8
        }
        require(owners[0x4B to 0x80] == owners[0x4B to 0x90]) { "Input/Output in different HID collections" }
        return Reports(bytes(0x80), bytes(0x90))
    }
}
