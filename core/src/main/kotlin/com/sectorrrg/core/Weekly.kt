package com.sectorrrg.core

import java.time.LocalDate

/**
 * 日線聚合成週線，錨定 ISO 週（星期一）。
 *
 * 點解唔直接攞行情源嘅週線：股票同加密根本唔係同一套曆。美股遇著假期嗰週會由星期二開始，
 * 加密 24/7 有啲源錨星期日。兩邊 epochDay 對唔上嘅話，align() 會靜靜雞逐根丟走，
 * 最壞情況係整個標的算唔出 RRG 而且完全唔報錯。自己合成就一把尺到底。
 */
fun toWeekly(daily: List<Bar>): List<Bar> {
    if (daily.isEmpty()) return emptyList()
    val out = ArrayList<Bar>(daily.size / 5 + 2)

    var weekStart = -1L
    var open = 0.0
    var high = Double.NEGATIVE_INFINITY
    var low = Double.POSITIVE_INFINITY
    var close = 0.0
    var volume = 0.0
    var has = false

    fun flush() {
        if (has) out += Bar(weekStart, open, high, low, close, volume)
    }

    for (b in daily.sortedBy { it.epochDay }) {
        val d = LocalDate.ofEpochDay(b.epochDay)
        val monday = d.minusDays((d.dayOfWeek.value - 1).toLong()).toEpochDay()
        if (monday != weekStart) {
            flush()
            weekStart = monday
            open = b.open
            high = b.high
            low = b.low
            close = b.close
            volume = b.volume
            has = true
        } else {
            if (b.high > high) high = b.high
            if (b.low < low) low = b.low
            close = b.close
            volume += b.volume
        }
    }
    flush()
    return out
}
