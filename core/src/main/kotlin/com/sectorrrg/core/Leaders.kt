package com.sectorrrg.core

import kotlin.math.sqrt

data class LeaderInput(
    val symbol: String,
    val sector: String,
    val relRatio: Double,      // 個股 vs 板塊 ETF 嘅 RS-Ratio
    val relMomentum: Double,
    val sectorStrength: Double,
    val downCapture: Double,
    val setup: EntrySetup,
    val price: Double,
    val advUsd: Double,
)

data class LeaderScore(
    val symbol: String,
    val sector: String,
    val total: Double,
    val relRatio: Double,
    val relMomentum: Double,
    val downCapture: Double,
    val setup: EntrySetup,
    val price: Double,
)

object Leaders {

    const val MIN_PRICE = 10.0
    const val MIN_ADV_USD = 50_000_000.0

    private const val W_RATIO = 0.35
    private const val W_MOM = 0.25
    private const val W_SECTOR = 0.20
    private const val W_DOWN = 0.20

    fun rank(
        inputs: List<LeaderInput>,
        top: Int = 10,
        minPrice: Double = MIN_PRICE,
        minAdvUsd: Double = MIN_ADV_USD,
    ): List<LeaderScore> {
        val ok = inputs.filter {
            it.price >= minPrice &&
                it.advUsd >= minAdvUsd &&
                !it.relRatio.isNaN() &&
                !it.relMomentum.isNaN()
        }
        if (ok.isEmpty()) return emptyList()

        val zR = z(ok.map { it.relRatio })
        val zM = z(ok.map { it.relMomentum })
        val zS = z(ok.map { it.sectorStrength })
        // downCapture 可能係 NaN（樣本不足），當中性 0 處理
        val zD = z(ok.map { if (it.downCapture.isNaN()) 0.0 else it.downCapture })

        return ok.mapIndexed { i, v ->
            LeaderScore(
                symbol = v.symbol,
                sector = v.sector,
                total = W_RATIO * zR[i] + W_MOM * zM[i] + W_SECTOR * zS[i] + W_DOWN * zD[i],
                relRatio = v.relRatio,
                relMomentum = v.relMomentum,
                downCapture = v.downCapture,
                setup = v.setup,
                price = v.price,
            )
        }.sortedByDescending { it.total }.take(top)
    }

    private fun z(xs: List<Double>): DoubleArray {
        val n = xs.size
        if (n <= 1) return DoubleArray(n)
        val mean = xs.sum() / n
        val sd = sqrt(xs.sumOf { (it - mean) * (it - mean) } / n)
        return if (sd <= 1e-12) DoubleArray(n) else DoubleArray(n) { (xs[it] - mean) / sd }
    }
}
