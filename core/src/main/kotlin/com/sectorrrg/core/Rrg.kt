package com.sectorrrg.core

import kotlin.math.atan2
import kotlin.math.hypot

data class RrgParams(
    val fast: Int = 10,      // 週
    val slow: Int = 30,
    val mom: Int = 4,
    val zWindow: Int = 52,
    val scale: Double = 2.5, // 只影響視覺尺度；象限邊界永遠係 z = 0
)

data class RrgPoint(val epochDay: Long, val ratio: Double, val momentum: Double) {
    val quadrant: Quadrant get() = Quadrant.of(ratio, momentum)
}

data class TailMetrics(
    val dRatio: Double,
    val dMomentum: Double,
    val headingDeg: Double,
    val speed: Double,
) {
    /** 動能仍向上 = 軌跡健康。轉頭向下就係收緊止盈嘅訊號。 */
    val healthy: Boolean get() = dMomentum > 0.0
    val towardLeading: Boolean get() = headingDeg > 0.0 && headingDeg < 90.0
}

data class RrgTrail(
    val symbol: String,
    val points: List<RrgPoint>,
) {
    val last: RrgPoint get() = points.last()
    val quadrant: Quadrant get() = last.quadrant

    /** 連續留喺當前象限嘅週數。 */
    fun weeksInQuadrant(): Int {
        val q = quadrant
        var n = 0
        for (p in points.asReversed()) if (p.quadrant == q) n++ else break
        return n
    }

    fun tail(lookback: Int = 4): TailMetrics? {
        if (points.size <= lookback) return null
        val a = points[points.size - 1 - lookback]
        val b = last
        val dR = b.ratio - a.ratio
        val dM = b.momentum - a.momentum
        val deg = ((Math.toDegrees(atan2(dM, dR)) % 360.0) + 360.0) % 360.0
        return TailMetrics(dR, dM, deg, hypot(dR, dM))
    }

    /** 綜合強度，用嚟排板塊同做龍頭榜嘅 sector 加權。 */
    fun strength(): Double {
        val t = tail()
        return (last.ratio - 100.0) + 0.5 * (last.momentum - 100.0) +
            when {
                t == null -> 0.0
                t.healthy -> 1.0
                else -> -1.0
            }
    }
}

object Rrg {

    const val MIN_BARS = 80

    /**
     * 行為等價於 StockCharts 嘅 JdK RS-Ratio / RS-Momentum：象限判斷同順時針旋轉特性一致，
     * 但官方公式未公開，數值唔會逐點對得上。內部一致比對得上第三方更重要。
     */
    fun compute(
        symbol: String,
        series: List<Bar>,
        bench: List<Bar>,
        p: RrgParams = RrgParams(),
    ): RrgTrail? {
        val a = align(series, bench) ?: return null
        if (a.dates.size < MIN_BARS) return null

        val rs = DoubleArray(a.dates.size) { 100.0 * a.self[it] / a.bench[it] }

        // RS-Ratio：相對強度嘅中長期趨勢，z 標準化後掛到 100 中軸
        val f = ema(rs, p.fast)
        val s = ema(rs, p.slow)
        val trend = DoubleArray(rs.size) { 100.0 * (f[it] / s[it] - 1.0) }
        val zTrend = rollingZ(trend, p.zWindow)

        val first = zTrend.indexOfFirst { !it.isNaN() }
        if (first < 0) return null

        val z = DoubleArray(rs.size - first) { zTrend[first + it] }
        val ratio = DoubleArray(z.size) { 100.0 + p.scale * z[it] }

        // RS-Momentum：對趨勢量本身求動能。順時針旋轉就係由呢一步嚟 ——
        // momentum 有 ratio 導數嘅性質，所以天然領先 ratio 一個相位。
        //
        // 一定要喺未縮放嘅 z 上面計，唔可以喺 ratio 上面計：ratio = 100 + scale·z，
        // 喺佢上面做比值嘅話 scale 會經個分母滲入動能，令一個純顯示參數改變象限判斷。
        val zEma = ema(z, p.mom)
        val momRaw = DoubleArray(z.size) { z[it] - zEma[it] }
        val zMom = rollingZ(momRaw, p.zWindow)

        val pts = ArrayList<RrgPoint>(z.size)
        for (i in z.indices) {
            if (zMom[i].isNaN() || ratio[i].isNaN()) continue
            pts += RrgPoint(a.dates[first + i], ratio[i], 100.0 + p.scale * zMom[i])
        }
        return if (pts.size < 8) null else RrgTrail(symbol, pts)
    }

    private class Aligned(val dates: LongArray, val self: DoubleArray, val bench: DoubleArray)

    private fun align(a: List<Bar>, b: List<Bar>): Aligned? {
        val bm = b.associateBy { it.epochDay }
        val d = ArrayList<Long>(a.size)
        val x = ArrayList<Double>(a.size)
        val y = ArrayList<Double>(a.size)
        for (bar in a) {
            val o = bm[bar.epochDay] ?: continue
            if (bar.close <= 0.0 || o.close <= 0.0) continue
            d += bar.epochDay; x += bar.close; y += o.close
        }
        if (d.size < 2) return null
        return Aligned(d.toLongArray(), x.toDoubleArray(), y.toDoubleArray())
    }
}
