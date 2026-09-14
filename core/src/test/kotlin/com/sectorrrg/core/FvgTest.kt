package com.sectorrrg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FvgTest {

    private fun bar(d: Long, h: Double, l: Double, c: Double) =
        Bar(d, (h + l) / 2, h, l, c, 1e7)

    @Test
    fun detectsBullishGapAndInvalidatesOnceFilled() {
        // i-2 high = 10，i low = 12 → 缺口 (10, 12)
        val up = listOf(
            bar(1, 10.0, 9.0, 9.5),
            bar(2, 11.5, 10.5, 11.0),
            bar(3, 13.0, 12.0, 12.8),
        )
        assertEquals(1, Fvg.unfilledBullish(up).size)
        assertEquals(10.0, Fvg.unfilledBullish(up)[0].lo, 1e-9)

        val filled = up + bar(4, 12.5, 9.8, 10.0)
        assertTrue(Fvg.unfilledBullish(filled).isEmpty())
    }

    @Test
    fun parabolicMoveDoesNotTriggerEntry() {
        val bars = (0 until 80).map { i ->
            val c = 100.0 + i * 2.0   // 單邊急升，遠離 EMA20
            bar(i.toLong(), c * 1.005, c * 0.995, c)
        }
        assertTrue(!Timing.check(bars).triggered)
    }

    @Test
    fun shortHistoryNeverTriggers() {
        val bars = (0 until 20).map { i -> bar(i.toLong(), 101.0, 99.0, 100.0) }
        assertTrue(!Timing.check(bars).triggered)
    }
}

class WeeklyTest {

    private fun day(d: Long, o: Double, h: Double, l: Double, c: Double, v: Double) =
        Bar(d, o, h, l, c, v)

    @Test
    fun aggregatesDailyIntoIsoWeeks() {
        // 2024-01-01 係星期一，epochDay 19723
        val mon = 19723L
        val daily = listOf(
            day(mon + 0, 10.0, 11.0, 9.5, 10.5, 100.0),
            day(mon + 1, 10.5, 12.0, 10.0, 11.5, 200.0),
            day(mon + 4, 11.5, 13.0, 9.0, 12.0, 300.0),   // 同一週嘅星期五
            day(mon + 7, 12.0, 12.5, 11.0, 11.2, 400.0),  // 下一個星期一
        )
        val w = toWeekly(daily)
        assertEquals(2, w.size)
        assertEquals(mon, w[0].epochDay)
        assertEquals(10.0, w[0].open, 1e-9)   // 首日開市
        assertEquals(13.0, w[0].high, 1e-9)   // 全週最高
        assertEquals(9.0, w[0].low, 1e-9)     // 全週最低
        assertEquals(12.0, w[0].close, 1e-9)  // 尾日收市
        assertEquals(600.0, w[0].volume, 1e-9)
        assertEquals(mon + 7, w[1].epochDay)
    }

    @Test
    fun holidayWeekStillAnchorsToMonday() {
        val mon = 19723L
        // 星期一休市，由星期二開始
        val daily = listOf(
            day(mon + 1, 10.0, 10.5, 9.8, 10.2, 100.0),
            day(mon + 2, 10.2, 10.8, 10.0, 10.6, 100.0),
        )
        val w = toWeekly(daily)
        assertEquals(1, w.size)
        assertEquals(mon, w[0].epochDay)   // 照樣錨星期一，同其他標的對得齊
    }

    @Test
    fun emptyInputIsSafe() {
        assertTrue(toWeekly(emptyList()).isEmpty())
    }
}

class MomentumTest {

    /** range = 日內波幅佔價格嘅比例。唔俾佢寫死，否則止損帶測試會變成空測。 */
    private fun series(n: Int, range: Double = 0.01, f: (Int) -> Double): List<Bar> =
        (0 until n).map { i ->
            val c = f(i)
            Bar(20000L + i, c, c * (1 + range), c * (1 - range), c, 1e7)
        }

    @Test
    fun scoreIsPositiveForUptrendAndNegativeForDowntrend() {
        val up = Momentum.score("UP", series(400) { 100.0 * Math.pow(1.001, it.toDouble()) }, 0.10, 0.20)!!
        val dn = Momentum.score("DN", series(400) { 100.0 * Math.pow(0.999, it.toDouble()) }, 0.10, 0.20)!!
        assertTrue("升勢分數應該為正，實際 " + up.score, up.score > 0)
        assertTrue("跌勢分數應該為負，實際 " + dn.score, dn.score < 0)
        assertTrue(up.aboveSma200)
        assertTrue(!dn.aboveSma200)
    }

    /** 排名要保留量級：跑贏得多嘅資產分數必須高過跑贏得少嘅。 */
    @Test
    fun scorePreservesMagnitudeAcrossAssets() {
        val mild = Momentum.score("MILD", series(400) { 100.0 * Math.pow(1.0005, it.toDouble()) }, 0.10, 0.20)!!
        val wild = Momentum.score("WILD", series(400) { 100.0 * Math.pow(1.0020, it.toDouble()) }, 0.10, 0.20)!!
        assertTrue("量級被抹平咗", wild.score > mild.score * 2)
    }

    @Test
    fun trailingStopStaysInsideConfiguredBand() {
        // 日內波幅 0.3%（似國債）→ 10×ATR 大約 6%，應該俾下限托住
        val calm = Momentum.score("CALM", series(400, 0.003) { 100.0 + it * 0.01 }, 0.10, 0.20)!!
        // 日內波幅 3%（似 BTC）→ 10×ATR 大約 30%，應該俾上限封住
        val rough = Momentum.score("ROUGH", series(400, 0.03) { 100.0 + it * 0.01 }, 0.10, 0.20)!!
        assertEquals(0.10, calm.trailPct, 1e-6)
        assertEquals(0.20, rough.trailPct, 1e-6)

        // 中間檔要真係落喺中間，唔可以次次都撞邊界
        val mid = Momentum.score("MID", series(400, 0.0065) { 100.0 + it * 0.01 }, 0.10, 0.20)!!
        assertTrue("中間檔應該喺帶內部，實際 " + mid.trailPct, mid.trailPct > 0.105 && mid.trailPct < 0.195)
    }

    @Test
    fun insufficientHistoryReturnsNull() {
        assertEquals(null, Momentum.score("SHORT", series(100) { 100.0 }, 0.10, 0.20))
    }

    @Test
    fun trailStopSitsBelowRecentPeak() {
        val m = Momentum.score("UP", series(400, 0.01) { 100.0 + it * 0.05 }, 0.10, 0.20)!!
        assertTrue(m.trailStop < m.peak)
        assertEquals(m.peak * (1.0 - m.trailPct), m.trailStop, 1e-6)
    }
}

class RotationTest {

    private fun bars(n: Int, range: Double, f: (Int) -> Double): List<Bar> =
        (0 until n).map { i ->
            val c = f(i)
            Bar(20000L + i, c, c * (1 + range), c * (1 - range), c, 1e7)
        }

    /**
     * 平穩升勢 + 指定嘅日波動，用嚟造出可預期嘅年化波動。
     *
     * 趨勢要夠強：13612W 俾 1 個月報酬 12 倍權重，趨勢弱過雜訊嘅話，
     * 最後 21 日嘅雜訊方向就決定咗總分正負，測試變成擲骰。
     * 日升 0.4% 之下，即使 ±3% 雜訊令 1 個月報酬係負數，總分仍然穩定為正。
     */
    private fun asset(dailyVol: Double): List<Bar> =
        bars(400, 0.005) { i -> 100.0 * (1.0 + 0.004 * i) * (1.0 + dailyVol * kotlin.math.sin(i * 1.9)) }

    private fun score(sym: String, b: List<Bar>) = Momentum.score(sym, b, 0.10, 0.20)!!

    @Test
    fun volatilityTargetShrinksPositionForVolatileAssets() {
        val calm = score("CALM", asset(0.003))
        val wild = score("WILD", asset(0.030))
        val plan = Rotation.plan(
            candidates = listOf(Triple("WILD", "波動大", "測試")),
            scores = mapOf("WILD" to wild),
            suspect = emptyMap(),
            spy = calm,
            slots = 1,
            targetVol = 0.20,
        )
        val w = plan.selected.single().weight
        assertTrue("波動大嘅資產應該縮倉，實際 $w", w < 1.0)
        assertEquals("倉位加現金要等於全部", 1.0, w + plan.cashWeight, 1e-9)
    }

    @Test
    fun lowVolatilityAssetGetsFullPositionNeverLeverage() {
        val calm = score("CALM", asset(0.001))
        val plan = Rotation.plan(
            candidates = listOf(Triple("CALM", "波動細", "測試")),
            scores = mapOf("CALM" to calm),
            suspect = emptyMap(),
            spy = calm,
            slots = 1,
            targetVol = 0.20,
        )
        val w = plan.selected.single().weight
        assertTrue("唔准加槓桿，實際 $w", w <= 1.0 + 1e-9)
        assertEquals(1.0, w, 1e-9)
    }

    /** 總閘關閉（SPY 跌穿 200 日線）嗰陣，乜都唔准入選。 */
    @Test
    fun marketGateBlocksEverything() {
        val strong = score("STRONG", asset(0.005))
        val fallingSpy = score("SPY", bars(400, 0.005) { 100.0 * Math.pow(0.999, it.toDouble()) })
        val plan = Rotation.plan(
            candidates = listOf(Triple("STRONG", "強勢", "測試")),
            scores = mapOf("STRONG" to strong),
            suspect = emptyMap(),
            spy = fallingSpy,
            slots = 1,
            targetVol = 0.20,
        )
        assertTrue(!plan.marketOn)
        assertTrue("總閘關閉仲有嘢入選", plan.selected.isEmpty())
        assertEquals(1.0, plan.cashWeight, 1e-9)
        assertEquals(GateFail.MARKET_OFF, plan.rows.single().gate)
    }

    /** 數據體檢唔過就唔准入選，就算排第一都係。 */
    @Test
    fun suspectDataBlocksSelection() {
        val strong = score("STRONG", asset(0.005))
        val plan = Rotation.plan(
            candidates = listOf(Triple("STRONG", "強勢", "測試")),
            scores = mapOf("STRONG" to strong),
            suspect = mapOf("STRONG" to "疑似未復權拆股"),
            spy = strong,
            slots = 1,
            targetVol = 0.20,
        )
        assertEquals(GateFail.DATA_SUSPECT, plan.rows.single().gate)
        assertTrue(plan.selected.isEmpty())
    }
}
