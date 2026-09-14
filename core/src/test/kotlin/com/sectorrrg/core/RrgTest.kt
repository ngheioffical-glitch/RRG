package com.sectorrrg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RrgTest {

    private fun toBars(xs: List<Double>): List<Bar> = xs.mapIndexed { i, c ->
        Bar(epochDay = 20000L + i * 7L, open = c, high = c * 1.01, low = c * 0.99, close = c, volume = 1e7)
    }

    private fun flat(n: Int, v: Double): List<Bar> = toBars(List(n) { v })

    /** 確定性「雜訊」：兩個不可通約嘅正弦。唔用亂數，測試先至可重現。 */
    private fun noise(i: Int): Double = 0.004 * (sin(i * 1.7) + sin(i * 0.43)) / 2.0

    /**
     * 模擬一次真實輪動：頭 shiftAt 根貼住基準行，之後每週多賺／少賺 relAfter。
     * 回傳 (板塊, 基準)。
     */
    private fun regime(n: Int, shiftAt: Int, relAfter: Double): Pair<List<Bar>, List<Bar>> {
        val bench = ArrayList<Double>(n)
        val self = ArrayList<Double>(n)
        bench += 100.0
        self += 100.0
        for (i in 1 until n) {
            val bm = 0.0015 + noise(i)
            val rel = if (i < shiftAt) 0.0 else relAfter
            bench += bench.last() * (1.0 + bm)
            self += self.last() * (1.0 + bm + rel + noise(i + 500))
        }
        return toBars(self) to toBars(bench)
    }

    @Test
    fun quadrantBoundariesAreAt100() {
        assertEquals(Quadrant.LEADING, Quadrant.of(100.0, 100.0))
        assertEquals(Quadrant.WEAKENING, Quadrant.of(100.0, 99.99))
        assertEquals(Quadrant.LAGGING, Quadrant.of(99.99, 99.99))
        assertEquals(Quadrant.IMPROVING, Quadrant.of(99.99, 100.0))
    }

    /**
     * 板塊轉強之後應該企喺圖嘅右半邊（RS-Ratio > 100），而且期間至少掂過 Leading 一次。
     *
     * 唔會斷言佢「永遠停喺 Leading」：RS-Ratio 係對自己過去一年做標準化，
     * 用同一速度長期跑贏最終會 normalize 返去中性，動能亦都會歸零。
     * 呢個係指標嘅設計，唔係 bug。
     */
    @Test
    fun sectorTurningStrongSitsInRightHalf() {
        val (self, bench) = regime(200, 150, 0.006)
        val t = Rrg.compute("STRONG", self, bench)
        assertNotNull(t)
        val post = t!!.points.takeLast(50)
        val right = post.count { it.ratio >= 100.0 }
        assertTrue("右半邊只得 $right/${post.size} 點", right >= post.size * 9 / 10)
        assertTrue("完全冇入過 Leading", post.any { it.quadrant == Quadrant.LEADING })
    }

    @Test
    fun sectorTurningWeakSitsInLeftHalf() {
        val (self, bench) = regime(200, 150, -0.006)
        val t = Rrg.compute("WEAK", self, bench)!!
        val post = t.points.takeLast(50)
        val left = post.count { it.ratio < 100.0 }
        assertTrue("左半邊只得 $left/${post.size} 點", left >= post.size * 9 / 10)
        assertTrue("完全冇入過 Lagging", post.any { it.quadrant == Quadrant.LAGGING })
    }

    /**
     * RRG 嘅核心特性：軌跡順時針行。
     * 即係由 Leading 出去嘅時候，去 Weakening 應該遠多過去 Improving。
     */
    @Test
    fun cyclicalRelativeStrengthRotatesClockwise() {
        val bench = flat(400, 100.0)
        val sector = toBars((0 until 400).map { 100.0 * (1.0 + 0.25 * sin(2 * PI * it / 90.0)) })
        val t = Rrg.compute("CYCLE", sector, bench)!!
        val qs = t.points.map { it.quadrant }
        assertTrue("只掃到 " + qs.distinct(), qs.distinct().size >= 3)

        var toWeakening = 0
        var toImproving = 0
        for (i in 1 until qs.size) {
            if (qs[i - 1] != Quadrant.LEADING || qs[i] == Quadrant.LEADING) continue
            when (qs[i]) {
                Quadrant.WEAKENING -> toWeakening++
                Quadrant.IMPROVING -> toImproving++
                else -> Unit
            }
        }
        assertTrue(
            "順時針應該由 Leading 走去 Weakening：$toWeakening vs $toImproving",
            toWeakening > toImproving,
        )
    }

    /** scale 純粹係顯示尺度，唔准影響象限判斷。 */
    @Test
    fun scaleDoesNotChangeQuadrants() {
        for (rel in listOf(0.006, -0.006)) {
            val (self, bench) = regime(200, 150, rel)
            val a = Rrg.compute("A", self, bench, RrgParams(scale = 1.0))!!
            val b = Rrg.compute("A", self, bench, RrgParams(scale = 9.0))!!
            assertEquals(
                "rel=$rel 之下 scale 改變咗象限",
                a.points.map { it.quadrant },
                b.points.map { it.quadrant },
            )
        }
    }

    @Test
    fun insufficientDataReturnsNull() {
        val bench = flat(30, 100.0)
        val sector = flat(30, 101.0)
        assertEquals(null, Rrg.compute("SHORT", sector, bench))
    }
}
