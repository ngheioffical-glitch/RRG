package com.sectorrrg.data

import com.sectorrrg.core.Bar
import java.time.LocalDate
import kotlin.math.abs

/**
 * 數據體檢。真正嘅風險唔係「報價唔準」，係源靜靜雞停止更新、
 * 或者未復權嘅拆股偽裝成一次暴跌污染咗動能分數。
 * 任何一項唔過，該資產標黃並且唔准入選。
 */
object Quality {

    private const val MAX_DAILY_MOVE = 0.35
    private const val MAX_STALE_DAYS = 6L

    fun check(bars: List<Bar>, today: LocalDate): String? {
        if (bars.isEmpty()) return "冇數據"

        val last = LocalDate.ofEpochDay(bars.last().epochDay)
        val age = today.toEpochDay() - last.toEpochDay()
        if (age > MAX_STALE_DAYS) return "數據停留喺 " + last + "，已經 " + age + " 日冇更新"

        val from = maxOf(1, bars.size - 260)
        for (i in from until bars.size) {
            val p = bars[i - 1].close
            if (p <= 0.0) continue
            val r = bars[i].close / p - 1.0
            if (abs(r) > MAX_DAILY_MOVE) {
                return "單日 " + String.format("%+.0f%%", r * 100) +
                    "（" + LocalDate.ofEpochDay(bars[i].epochDay) + "），疑似未復權拆股"
            }
        }

        val recent = bars.takeLast(20)
        if (recent.count { it.volume <= 0.0 } >= 15) return "近 20 日幾乎冇成交量，代號可能已失效"

        return null
    }

    /** 跨源抽驗：兩個源嘅最新收市價差距超過門檻就報。 */
    fun crossCheck(primary: Double, secondary: Double, tolerance: Double = 0.015): String? {
        if (primary <= 0.0 || secondary <= 0.0) return null
        val diff = abs(primary / secondary - 1.0)
        return if (diff > tolerance) {
            "兩個源報價差 " + String.format("%.1f%%", diff * 100) + "，請自行核實"
        } else {
            null
        }
    }
}
