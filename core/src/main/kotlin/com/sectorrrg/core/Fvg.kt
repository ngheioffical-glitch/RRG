package com.sectorrrg.core

import kotlin.math.abs
import kotlin.math.max

/** Bullish fair value gap：low[i] > high[i-2]，缺口區間 = (high[i-2], low[i])。 */
data class FvgZone(val epochDay: Long, val lo: Double, val hi: Double) {
    fun contains(price: Double) = price in lo..hi
}

object Fvg {

    /**
     * 未填補嘅 bullish FVG。填補判定：價格 low 觸及或穿破缺口下沿即作廢，
     * 只保留仍然有效嘅支撐區。
     */
    fun unfilledBullish(bars: List<Bar>, lookback: Int = 60): List<FvgZone> {
        val open = ArrayList<FvgZone>()
        val start = max(2, bars.size - lookback)
        for (i in start until bars.size) {
            open.removeAll { bars[i].low <= it.lo }
            val lo = bars[i - 2].high
            val hi = bars[i].low
            if (hi > lo) open += FvgZone(bars[i].epochDay, lo, hi)
        }
        return open.sortedByDescending { it.hi }
    }
}

data class EntrySetup(
    val triggered: Boolean,
    val reason: String,
    val entry: Double,
    val stop: Double,
    val zoneLo: Double?,
    val zoneHi: Double?,
)

object Timing {

    /**
     * 微觀擇時：價格回踩到有效結構而趨勢未破。
     * 呢個唔入評分 —— 佢答「幾時買」，唔係答「買邊隻」。
     */
    fun check(bars: List<Bar>, emaTolerance: Double = 0.02): EntrySetup {
        val last = bars.lastOrNull() ?: return miss()
        if (bars.size < 50) return miss()
        val closes = bars.map { it.close }
        val e20 = emaList(closes, 20).last()
        val e50 = emaList(closes, 50).last()
        val a = atr(bars, 14).let { if (it.isNaN()) 0.0 else it }
        val swingLow = bars.takeLast(20).minOf { it.low }

        // 趨勢未破係前提，唔想喺跌勢裡面接刀
        if (last.close < e50) return miss()

        val zone = Fvg.unfilledBullish(bars).firstOrNull { it.contains(last.close) }
        if (zone != null) {
            return EntrySetup(
                triggered = true,
                reason = "回踩未填補 FVG",
                entry = last.close,
                stop = minOf(zone.lo, swingLow) - 0.5 * a,
                zoneLo = zone.lo,
                zoneHi = zone.hi,
            )
        }
        if (abs(last.close - e20) / e20 <= emaTolerance) {
            return EntrySetup(
                triggered = true,
                reason = "回踩 EMA20",
                entry = last.close,
                stop = minOf(e50, swingLow) - 0.5 * a,
                zoneLo = null,
                zoneHi = null,
            )
        }
        return miss()
    }

    private fun miss() = EntrySetup(false, "未回踩", 0.0, 0.0, null, null)
}
