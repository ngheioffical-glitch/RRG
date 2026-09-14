package com.sectorrrg.data

data class SectorDef(val symbol: String, val name: String, val colorArgb: Long)

data class AssetDef(
    val symbol: String,
    val name: String,
    val assetClass: String,
    val colorArgb: Long,
)

object Universe {
    const val BENCHMARK = "SPY"
    val sectors = listOf(
        SectorDef("XLK", "科技", 0xFF3B82F6),
        SectorDef("XLC", "通訊", 0xFF8B5CF6),
        SectorDef("XLY", "可選消費", 0xFFEC4899),
        SectorDef("XLI", "工業", 0xFF14B8A6),
        SectorDef("XLB", "原材料", 0xFF84CC16),
        SectorDef("XLE", "能源", 0xFFF97316),
        SectorDef("XLF", "金融", 0xFF0EA5E9),
        SectorDef("XLV", "醫療", 0xFF22C55E),
        SectorDef("XLP", "必需消費", 0xFFEAB308),
        SectorDef("XLU", "公用事業", 0xFF6366F1),
        SectorDef("XLRE", "房地產", 0xFFF43F5E),
    )

    /** 非板塊資產。照樣以 SPY 為基準畫 RRG，但唔參與板塊強度排序。 */
    val macro = listOf(
        SectorDef("TLT", "20年期國債", 0xFF64748B),
        SectorDef("IEF", "7-10年國債", 0xFF94A3B8),
        SectorDef("GLD", "黃金", 0xFFD4AF37),
        SectorDef("EFA", "成熟市場(美國以外)", 0xFF06B6D4),
        SectorDef("EEM", "新興市場", 0xFFA855F7),
    )

    /** 輪動池：排名同入選就係喺呢個集合入面做。 */
    val rotation: List<AssetDef> =
        sectors.map { AssetDef(it.symbol, it.name, "板塊", it.colorArgb) } +
            listOf(
                AssetDef("TLT", "20年期國債", "債券", 0xFF64748B),
                AssetDef("IEF", "7-10年國債", "債券", 0xFF94A3B8),
                AssetDef("GLD", "黃金", "商品", 0xFFD4AF37),
                AssetDef("EFA", "成熟市場(美國以外)", "股票", 0xFF06B6D4),
                AssetDef("EEM", "新興市場", "股票", 0xFFA855F7),
            )

    val sectorSymbols: List<String> = listOf(BENCHMARK) + sectors.map { it.symbol }
    val allTopLevel: List<String> = (sectorSymbols + macro.map { it.symbol }).distinct()

    fun def(symbol: String) = (sectors + macro).firstOrNull { it.symbol == symbol }
    fun rotationDef(symbol: String) = rotation.firstOrNull { it.symbol == symbol }
    fun isMacro(symbol: String) = macro.any { it.symbol == symbol }

    fun regimeTag(symbol: String): String? = when (symbol) {
        "XLK", "XLY", "XLI" -> "Risk-On"
        "XLV", "XLP", "XLU" -> "Risk-Off"
        "TLT", "IEF" -> "通縮對沖"
        "GLD" -> "通脹對沖"
        else -> null
    }

}

/**
 * 實際持倉格數。13,000 HKD 之下，一格 ETF 嘅來回摩擦大約 0.24%；
 * 拆成三格就變 0.7% 以上，直接食清策略優勢。所以鎖死一格。
 */
object RotationConfig {
    const val SLOTS = 1
    /**
     * 組合年化波動目標。得一格嘅時候，榜首係邊個邊個就係全副身家 ——
     * 反波動率權重只喺多格之間分配，一格之下完全冇作用。
     * 波動率目標先係一格之下真正控制風險嘅機制。
     */
    const val TARGET_VOL = 0.20
    /** 美股股息對非美國稅務居民嘅預扣稅率。香港投資者係 30%。 */
    const val DIVIDEND_WITHHOLDING = 0.30
    /** 跨源抽驗嘅範圍：持倉 + 榜首幾名。只驗少數標的，成本細。 */
    const val CROSS_CHECK_TOP = 3
    const val TRAIL_FLOOR = 0.10
    const val TRAIL_CAP = 0.20
    const val UNLOCK_2_SLOTS_USD = 8_000
    const val UNLOCK_3_SLOTS_USD = 15_000
}
