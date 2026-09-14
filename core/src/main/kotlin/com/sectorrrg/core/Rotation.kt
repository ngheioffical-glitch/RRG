package com.sectorrrg.core

/** 資產通唔通過入選閘，唔通過就要講得出點解。 */
enum class GateFail {
    NONE,
    MARKET_OFF,       // SPY 跌穿 200 日線，總閘關閉
    BELOW_SMA200,     // 資產自己跌穿 200 日線
    NEGATIVE_MOM,     // 動能分數為負
    DATA_SUSPECT,     // 數據體檢唔過
}

data class RotationRow(
    val symbol: String,
    val name: String,
    val assetClass: String,
    val rank: Int,
    val mom: MomentumScore,
    val gate: GateFail,
    val eligible: Boolean,
    val selected: Boolean,     // 落到實際持倉格
    val weight: Double,        // 波動率目標倉位，只對 selected 有意義
    val dataNote: String?,     // 數據體檢警告
    val stopWarning: String?,  // 止損距離細過該資產常態回調
)

data class RotationPlan(
    val marketOn: Boolean,
    val spyPrice: Double,
    val spySma200: Double,
    val slots: Int,
    val rows: List<RotationRow>,
    val cashWeight: Double,
) {
    val selected: List<RotationRow> get() = rows.filter { it.selected }
    val eligible: List<RotationRow> get() = rows.filter { it.eligible }
}

object Rotation {

    /** 追蹤止損距離明顯細過該資產常態回調嘅門檻 —— 會被自己嘅波動震走。 */
    private const val WHIPSAW_RATIO = 0.9

    fun plan(
        candidates: List<Triple<String, String, String>>,   // symbol, 中文名, 資產類別
        scores: Map<String, MomentumScore>,
        suspect: Map<String, String>,
        spy: MomentumScore?,
        slots: Int,
        targetVol: Double,
    ): RotationPlan {
        val marketOn = spy != null && spy.aboveSma200

        val ranked = candidates
            .mapNotNull { (sym, name, cls) -> scores[sym]?.let { Triple(sym, name to cls, it) } }
            .sortedByDescending { it.third.score }

        val rows = ArrayList<RotationRow>(ranked.size)
        for ((i, entry) in ranked.withIndex()) {
            val (sym, meta, m) = entry
            val note = suspect[sym]
            val gate = when {
                note != null -> GateFail.DATA_SUSPECT
                !marketOn -> GateFail.MARKET_OFF
                !m.aboveSma200 -> GateFail.BELOW_SMA200
                m.score <= 0.0 -> GateFail.NEGATIVE_MOM
                else -> GateFail.NONE
            }
            // 常態回調用年化波動率換算成月度尺度做粗略代理
            val normalPullback = if (m.annualVol.isNaN()) Double.NaN else m.annualVol * 0.5
            val warn = if (!normalPullback.isNaN() && m.trailPct < normalPullback * WHIPSAW_RATIO) {
                "止損 " + pct(m.trailPct) + " 細過常態回調 " + pct(normalPullback) + "，易被震走"
            } else {
                null
            }
            rows += RotationRow(
                symbol = sym,
                name = meta.first,
                assetClass = meta.second,
                rank = i + 1,
                mom = m,
                gate = gate,
                eligible = gate == GateFail.NONE,
                selected = false,
                weight = 0.0,
                dataNote = note,
                stopWarning = warn,
            )
        }

        val picked = rows.filter { it.eligible }.take(slots)

        // 波動率目標：倉位 = min(100%, 目標波動 ÷ 資產年化波動)，再除以格數。
        // 唔用反波動率 —— 反波動率只係喺多格之間分配，得一格就永遠 100%，
        // 即係榜首係邊個邊個就係全副身家。目標波動先至令一格之下嘅風險可比。
        // 唔加槓桿，所以封頂 100%；差額留現金。
        val weights = picked.map { r ->
            val v = r.mom.annualVol
            val scale = if (v.isNaN() || v <= 0.0) 1.0 else (targetVol / v).coerceAtMost(1.0)
            scale / slots
        }

        val out = rows.map { r ->
            val idx = picked.indexOfFirst { it.symbol == r.symbol }
            if (idx < 0) r else r.copy(selected = true, weight = weights[idx])
        }

        return RotationPlan(
            marketOn = marketOn,
            spyPrice = spy?.price ?: Double.NaN,
            spySma200 = spy?.sma200 ?: Double.NaN,
            slots = slots,
            rows = out,
            cashWeight = (1.0 - weights.sum()).coerceIn(0.0, 1.0),
        )
    }

    private fun pct(x: Double) = String.format("%.0f%%", x * 100)
}
