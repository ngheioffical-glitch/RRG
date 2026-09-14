package com.sectorrrg.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** span 定義同 pandas ewm(span, adjust=false) 一致，seed 用第一個值。 */
fun ema(src: DoubleArray, span: Int): DoubleArray {
    require(span >= 1)
    val out = DoubleArray(src.size)
    if (src.isEmpty()) return out
    val a = 2.0 / (span + 1.0)
    out[0] = src[0]
    for (i in 1 until src.size) out[i] = a * src[i] + (1.0 - a) * out[i - 1]
    return out
}

fun emaList(src: List<Double>, span: Int): DoubleArray = ema(src.toDoubleArray(), span)

/**
 * 滾動 z-score。窗內樣本數不足 minPeriods 或標準差近乎 0 時回 NaN。
 * n 大約 260，O(n·win) 夠快，唔值得為此犧牲可讀性。
 */
fun rollingZ(src: DoubleArray, win: Int, minPeriods: Int = win / 2): DoubleArray {
    val out = DoubleArray(src.size) { Double.NaN }
    for (i in src.indices) {
        val start = max(0, i - win + 1)
        val n = i - start + 1
        if (n < minPeriods) continue
        var mean = 0.0
        var bad = false
        for (j in start..i) {
            if (src[j].isNaN()) { bad = true; break }
            mean += src[j]
        }
        if (bad) continue
        mean /= n
        var v = 0.0
        for (j in start..i) { val d = src[j] - mean; v += d * d }
        val sd = sqrt(v / n)
        if (sd <= 1e-12) continue
        out[i] = (src[i] - mean) / sd
    }
    return out
}

fun atr(bars: List<Bar>, period: Int = 14): Double {
    if (bars.size < period + 1) return Double.NaN
    var sum = 0.0
    for (i in bars.size - period until bars.size) {
        val prev = bars[i - 1].close
        sum += maxOf(
            bars[i].high - bars[i].low,
            abs(bars[i].high - prev),
            abs(bars[i].low - prev),
        )
    }
    return sum / period
}

/** 20 日平均成交金額（美元），做流動性硬過濾。 */
fun advUsd(bars: List<Bar>, window: Int = 20): Double {
    if (bars.isEmpty()) return 0.0
    val from = max(0, bars.size - window)
    var s = 0.0
    for (i in from until bars.size) s += bars[i].close * bars[i].volume
    return s / (bars.size - from)
}

/**
 * 逆市抗跌：只統計基準跌幅超過 threshold 嘅交易日，計個股相對基準嘅平均超額收益。
 * 正值代表大盤跌嗰日佢比大盤扛得住，通常係有資金托住嘅痕跡。
 */
fun downCapture(
    stock: List<Bar>,
    bench: List<Bar>,
    threshold: Double = -0.005,
    window: Int = 60,
    minDays: Int = 8,
): Double {
    val bm = bench.associateBy { it.epochDay }
    var sum = 0.0
    var n = 0
    val from = max(1, stock.size - window)
    for (i in from until stock.size) {
        val b1 = bm[stock[i].epochDay] ?: continue
        val b0 = bm[stock[i - 1].epochDay] ?: continue
        if (b0.close <= 0.0 || stock[i - 1].close <= 0.0) continue
        val br = b1.close / b0.close - 1.0
        if (br >= threshold) continue
        val sr = stock[i].close / stock[i - 1].close - 1.0
        sum += sr - br
        n++
    }
    return if (n < minDays) Double.NaN else sum / n
}
