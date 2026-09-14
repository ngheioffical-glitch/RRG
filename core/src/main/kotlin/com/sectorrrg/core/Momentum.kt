package com.sectorrrg.core

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 交易日口徑嘅回望窗。1M≈21、3M≈63、6M≈126、12M≈252。 */
object Lookback {
    const val M1 = 21
    const val M3 = 63
    const val M6 = 126
    const val M12 = 252
}

data class MomentumScore(
    val symbol: String,
    val score: Double,       // 13612W 加權年化動能
    val r1: Double,
    val r3: Double,
    val r6: Double,
    val r12: Double,
    val annualVol: Double,
    val price: Double,
    val sma200: Double,
    val aboveSma200: Boolean,
    val atrPct: Double,
    val trailPct: Double,
    val trailStop: Double,   // 由最近高位往下計
    val peak: Double,
)

/** 由 n 個交易日之前計到而家嘅含息總報酬。資料唔夠就回 NaN。 */
fun totalReturn(bars: List<Bar>, days: Int): Double {
    if (bars.size <= days) return Double.NaN
    val a = bars[bars.size - 1 - days].close
    val b = bars.last().close
    return if (a <= 0.0) Double.NaN else b / a - 1.0
}

/**
 * 扣咗股息預扣稅嘅淨報酬。
 *
 * 含息總報酬用復權價，價格報酬用未復權價，兩者相減就係派息貢獻。
 * 香港投資者收美股股息被預扣 30%，所以派息嗰部分只收到七成。
 * 唔扣嘅話，TLT / IEF / XLU / XLP 呢類高派息資產喺榜上面會被系統性高估 ——
 * 而佢哋正正就係衰退兜底嗰條腿，排錯位代價唔細。
 */
fun netReturn(bars: List<Bar>, days: Int, withholding: Double): Double {
    if (bars.size <= days) return Double.NaN
    val i = bars.size - 1 - days
    val a = bars[i].close
    val b = bars.last().close
    if (a <= 0.0) return Double.NaN
    val total = b / a - 1.0

    val ra = bars[i].rawClose
    val rb = bars.last().rawClose
    if (ra <= 0.0 || rb <= 0.0) return total
    val price = rb / ra - 1.0
    val dividend = total - price
    return price + (1.0 - withholding) * dividend
}

private fun weekStart(epochDay: Long): Long {
    val d = LocalDate.ofEpochDay(epochDay)
    return d.minusDays((d.dayOfWeek.value - 1).toLong()).toEpochDay()
}

/**
 * 最後一根「已完成嘅週」嘅收市索引。
 *
 * 200 日線係慢速嘅市場狀態指標，唔應該用每日節奏去評估 ——
 * 價格喺線上下反覆穿插好常見，每日檢查會製造大量假訊號。
 * 快速風險切斷交返俾 ATR 追蹤止損，佢先係要每日睇嗰個。
 */
fun lastCompletedWeekIndex(bars: List<Bar>): Int {
    if (bars.size < 2) return bars.size - 1
    val current = weekStart(bars.last().epochDay)
    for (i in bars.indices.reversed()) {
        if (weekStart(bars[i].epochDay) != current) return i
    }
    return bars.size - 1
}

fun sma(bars: List<Bar>, n: Int): Double {
    if (bars.size < n) return Double.NaN
    var s = 0.0
    for (i in bars.size - n until bars.size) s += bars[i].close
    return s / n
}

/** 年化已實現波動率，用最近 window 日嘅日報酬。 */
fun annualVol(bars: List<Bar>, window: Int = 60): Double {
    if (bars.size < window + 1) return Double.NaN
    val rets = ArrayList<Double>(window)
    for (i in bars.size - window until bars.size) {
        val p = bars[i - 1].close
        if (p <= 0.0) continue
        rets += bars[i].close / p - 1.0
    }
    if (rets.size < 2) return Double.NaN
    val m = rets.sum() / rets.size
    val v = rets.sumOf { (it - m) * (it - m) } / rets.size
    return sqrt(v) * sqrt(252.0)
}

object Momentum {

    /**
     * 13612W（Keller）：(12·r1 + 4·r3 + 2·r6 + 1·r12) ÷ 4。
     *
     * 點解唔用 RRG 嘅 RS-Ratio 做排名：RS-Ratio 係對每隻標的自己過去一年做 z 標準化，
     * 佢答「相對自己舊年強唔強」，而標準化正正把跨資產嘅量級差異除乾淨。
     * BTC 跑贏 SPY 40% 同 XLK 跑贏 5% 可以得出同一個 z 值。
     * 排名要保留量級，所以行原始總報酬。
     */
    fun score(
        symbol: String,
        daily: List<Bar>,
        trailFloor: Double,
        trailCap: Double,
        withholding: Double = 0.0,
    ): MomentumScore? {
        if (daily.size < Lookback.M12 + 5) return null
        val r1 = netReturn(daily, Lookback.M1, withholding)
        val r3 = netReturn(daily, Lookback.M3, withholding)
        val r6 = netReturn(daily, Lookback.M6, withholding)
        val r12 = netReturn(daily, Lookback.M12, withholding)
        if (r1.isNaN() || r3.isNaN() || r6.isNaN() || r12.isNaN()) return null

        val s = (12.0 * r1 + 4.0 * r3 + 2.0 * r6 + 1.0 * r12) / 4.0
        val price = daily.last().close

        // 200 日線閘用「上週收市」評估，唔用今日收市
        val gi = lastCompletedWeekIndex(daily)
        val gateBars = daily.subList(0, gi + 1)
        val ma = sma(gateBars, 200)
        val gateClose = gateBars.last().close
        val a = atr(daily, 20)
        val atrPct = if (a.isNaN() || price <= 0.0) Double.NaN else a / price

        // 波動率自適應追蹤止損：低波動資產唔會被過緊嘅止損震走，
        // 高波動資產亦唔會拖到一個荒謬嘅離場距離。
        val trail = if (atrPct.isNaN()) trailCap
        else min(trailCap, max(trailFloor, 10.0 * atrPct))

        val peak = daily.takeLast(Lookback.M6).maxOf { it.close }

        return MomentumScore(
            symbol = symbol,
            score = s,
            r1 = r1, r3 = r3, r6 = r6, r12 = r12,
            annualVol = annualVol(daily),
            price = price,
            sma200 = ma,
            aboveSma200 = !ma.isNaN() && gateClose > ma,
            atrPct = atrPct,
            trailPct = trail,
            trailStop = peak * (1.0 - trail),
            peak = peak,
        )
    }
}
