package com.sectorrrg.core

/** 單根 K 線。epochDay 為該根 K 線起始日；週線用該週交易首日，跨標的天然對齊。 */
data class Bar(
    val epochDay: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    /**
     * 未復權收市價。close 係復權後（含息），兩者相減就係派息部分。
     * 香港投資者收美股股息要俾 30% 預扣，所以排名要用扣稅後嘅淨報酬。
     * 冇未復權數據嘅源（例如 Stooq）填返 close，派息部分自然變 0。
     */
    val rawClose: Double = close,
)

enum class Quadrant {
    LEADING, WEAKENING, LAGGING, IMPROVING;

    val zh: String
        get() = when (this) {
            LEADING -> "領先"
            WEAKENING -> "轉弱"
            LAGGING -> "落後"
            IMPROVING -> "改善"
        }

    companion object {
        fun of(ratio: Double, momentum: Double): Quadrant = when {
            ratio >= 100.0 && momentum >= 100.0 -> LEADING
            ratio >= 100.0 -> WEAKENING
            momentum < 100.0 -> LAGGING
            else -> IMPROVING
        }
    }
}
