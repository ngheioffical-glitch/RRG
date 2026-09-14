package com.sectorrrg.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sectorrrg.core.GateFail
import com.sectorrrg.core.Quadrant
import com.sectorrrg.core.RotationRow
import com.sectorrrg.data.RefreshState
import com.sectorrrg.data.SectorView
import com.sectorrrg.data.Snapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DF: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val MUTED = Color(0xFF9CA3AF)
private val FAINT = Color(0xFF6B7280)
private val GREEN = Color(0xFF22C55E)
private val AMBER = Color(0xFFF59E0B)
private val RED = Color(0xFFEF4444)
private val BLUE = Color(0xFF3B82F6)

private fun pct(x: Double): String = if (x.isNaN()) "—" else String.format("%+.1f%%", x * 100)
private fun pct0(x: Double): String = if (x.isNaN()) "—" else String.format("%.0f%%", x * 100)
private fun money(x: Double): String = if (x.isNaN()) "—" else String.format("%.2f", x)

fun statusColor(status: String): Color = when (status) {
    "NEW_LEADING" -> GREEN
    "LEADING" -> Color(0xFF16A34A)
    "WATCH", "IMPROVING" -> BLUE
    "TRIM", "WEAKENING" -> AMBER
    "LAGGING", "AVOID", "EXIT" -> RED
    else -> FAINT
}

fun statusLabel(status: String): String = when (status) {
    "NEW_LEADING" -> "新入領先"
    "LEADING" -> "領先"
    "WATCH" -> "觀察"
    "TRIM" -> "收緊止盈"
    "AVOID" -> "避開"
    else -> status
}

private fun gateLabel(g: GateFail): String = when (g) {
    GateFail.NONE -> "合格"
    GateFail.MARKET_OFF -> "總閘關閉"
    GateFail.BELOW_SMA200 -> "200日線之下"
    GateFail.NEGATIVE_MOM -> "動能為負"
    GateFail.DATA_SUSPECT -> "數據存疑"
}

/* ================= 今日行動 ================= */

@Composable
fun TodayScreen(
    snap: Snapshot?,
    state: RefreshState,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Header(snap, state, onRefresh)
        if (snap == null) {
            EmptyState(state, onRefresh)
            return@Column
        }
        val plan = snap.plan
        val breaches = plan.selected.filter {
            it.mom.price <= it.mom.trailStop || !it.mom.aboveSma200
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                val headline = when {
                    breaches.isNotEmpty() -> "今日要離場"
                    !plan.marketOn -> "總閘關閉 · 持現金"
                    snap.daysToRebalance == 0 -> "今日月底 · 執行換倉"
                    else -> "今日無動作"
                }
                val tone = when {
                    breaches.isNotEmpty() -> RED
                    !plan.marketOn -> AMBER
                    snap.daysToRebalance == 0 -> BLUE
                    else -> GREEN
                }
                Text(
                    headline,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = tone,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                Text(
                    "距離月底換倉仲有 " + snap.daysToRebalance + " 日",
                    fontSize = 12.sp, color = MUTED,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }

            items(breaches, key = { "b_" + it.symbol }) { r ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(r.symbol + " " + r.name, fontWeight = FontWeight.Bold, color = RED)
                        Text(
                            if (!r.mom.aboveSma200) {
                                "跌穿 200 日線（" + money(r.mom.sma200) + "），即日離場去現金"
                            } else {
                                "觸及追蹤止損 " + money(r.mom.trailStop) + "，即日離場去現金"
                            },
                            fontSize = 12.sp, color = MUTED,
                        )
                        Text(
                            "離場之後等下個月底先再部署，唔即刻補倉。",
                            fontSize = 11.sp, color = FAINT,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            item { GateCard(snap) }
            item {
                Text(
                    "現時配置",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
                )
            }
            if (plan.selected.isEmpty()) {
                item {
                    Text(
                        "冇資產通過入選閘，全倉現金。",
                        fontSize = 13.sp, color = AMBER,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            items(plan.selected, key = { "s_" + it.symbol }) { HoldingCard(it, onOpen) }
            if (plan.cashWeight > 0.001) {
                item {
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("現金", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(pct0(plan.cashWeight), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun GateCard(snap: Snapshot) {
    val plan = snap.plan
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("總閘 SPY 200 日線", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Pill(if (plan.marketOn) "開啟" else "關閉", if (plan.marketOn) GREEN else RED)
            }
            Text(
                "現價 " + money(plan.spyPrice) + " · 200日線 " + money(plan.spySma200),
                fontSize = 11.sp, color = MUTED, fontFamily = FontFamily.Monospace,
            )
            if (!plan.marketOn) {
                Text(
                    "跌穿總閘期間唔再入場。呢個係整套系統避開大回撤嘅主要機制。",
                    fontSize = 11.sp, color = AMBER, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun HoldingCard(r: RotationRow, onOpen: (String) -> Unit) {
    val dist = if (r.mom.price > 0) (r.mom.price - r.mom.trailStop) / r.mom.price else Double.NaN
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.symbol, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Text(r.name, fontSize = 12.sp, color = MUTED)
                Spacer(Modifier.weight(1f))
                Text(pct0(r.weight), fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "現價 " + money(r.mom.price) + " · 止損 " + money(r.mom.trailStop) +
                    "（" + pct0(r.mom.trailPct) + "）· 距離止損 " + pct0(dist),
                fontSize = 11.sp, color = MUTED, fontFamily = FontFamily.Monospace,
            )
            r.stopWarning?.let {
                Text(it, fontSize = 11.sp, color = AMBER, modifier = Modifier.padding(top = 4.dp))
            }
            r.dataNote?.let {
                Text("數據體檢：" + it, fontSize = 11.sp, color = RED, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            LinkChip("喺富途開") { onOpen(r.symbol) }
        }
    }
}

/* ================= 輪動榜 ================= */

@Composable
fun RotationScreen(snap: Snapshot?, onOpen: (String) -> Unit) {
    if (snap == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp)) {
        item {
            Text("輪動榜", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "13612W 動能分數 = (12×1個月 + 4×3個月 + 2×6個月 + 1×12個月) ÷ 4，" +
                    "用復權價，跨資產可比。入選要過兩道閘：SPY 企 200 日線之上，該資產亦要企自己嘅。",
                fontSize = 10.sp, color = FAINT,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
        }
        items(snap.plan.rows, key = { it.symbol }) { RotationRowCard(it, onOpen) }
        item {
            Text(
                "執行層鎖死一格。呢個資金規模之下，拆多過一格嘅來回摩擦會食清策略優勢。",
                fontSize = 10.sp, color = FAINT,
                modifier = Modifier.padding(top = 14.dp, bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun RotationRowCard(r: RotationRow, onOpen: (String) -> Unit) {
    val tone = when {
        r.selected -> GREEN
        r.eligible -> BLUE
        else -> FAINT
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.rank.toString(),
                    fontFamily = FontFamily.Monospace, color = FAINT, fontSize = 13.sp,
                    modifier = Modifier.width(22.dp),
                )
                Text(
                    r.symbol,
                    fontWeight = if (r.selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (r.eligible) Color.White else MUTED,
                )
                Spacer(Modifier.width(6.dp))
                Text(r.name, fontSize = 11.sp, color = MUTED)
                Spacer(Modifier.weight(1f))
                if (r.selected) Pill("持倉 " + pct0(r.weight), GREEN)
                else Pill(gateLabel(r.gate), tone)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "分數 " + String.format("%+.2f", r.mom.score) +
                    " · 1M " + pct(r.mom.r1) + " · 3M " + pct(r.mom.r3) +
                    " · 12M " + pct(r.mom.r12),
                fontSize = 11.sp, color = MUTED, fontFamily = FontFamily.Monospace,
            )
            Text(
                "年化波動 " + pct0(r.mom.annualVol) + " · " +
                    (if (r.mom.aboveSma200) "企穩 200 日線" else "跌穿 200 日線") +
                    " · 建議止損 " + pct0(r.mom.trailPct),
                fontSize = 11.sp, color = MUTED, fontFamily = FontFamily.Monospace,
            )
            r.dataNote?.let {
                Text("數據體檢：" + it, fontSize = 11.sp, color = RED, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            LinkChip("喺富途開") { onOpen(r.symbol) }
        }
    }
}

/* ================= RRG ================= */

@Composable
fun RrgScreen(snap: Snapshot?, state: RefreshState, onRefresh: () -> Unit) {
    var tail by remember { mutableIntStateOf(8) }
    var frac by remember { mutableFloatStateOf(1f) }
    var sel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(snap?.asOfEpochDay) { frac = 1f }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Header(snap, state, onRefresh)
        if (snap == null) {
            EmptyState(state, onRefresh)
            return@Column
        }
        Text(
            "輪動榜係回望嘅，RRG 係前望嘅：睇邊個資產喺 Improving 準備接棒。呢度唔做排名。",
            fontSize = 10.sp, color = FAINT, modifier = Modifier.padding(bottom = 6.dp),
        )
        val plotted = snap.sectors + snap.macro
        RrgChart(
            sectors = plotted,
            tailWeeks = tail,
            endFraction = frac,
            highlighted = sel,
            onSelect = { sel = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        val end = plotted.firstOrNull()?.trail?.points?.let {
            it[((it.size - 1) * frac).toInt().coerceIn(0, it.size - 1)]
        }
        Text(
            end?.let { "週期 " + LocalDate.ofEpochDay(it.epochDay).format(DF) } ?: "",
            fontSize = 11.sp, color = MUTED,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Slider(value = frac, onValueChange = { frac = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("尾巴 " + tail + " 週", fontSize = 12.sp, color = MUTED)
            Spacer(Modifier.width(8.dp))
            Slider(
                value = tail.toFloat(),
                onValueChange = { tail = it.toInt() },
                valueRange = 4f..20f,
                modifier = Modifier.weight(1f),
            )
        }
        sel?.let { s -> plotted.firstOrNull { it.symbol == s }?.let { SectorCard(it) } }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun SectorCard(v: SectorView) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Color(v.colorArgb.toInt()), RoundedCornerShape(5.dp)))
                Spacer(Modifier.width(8.dp))
                Text(v.symbol, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(v.name, fontSize = 12.sp, color = MUTED)
                Spacer(Modifier.weight(1f))
                Pill(statusLabel(v.status), statusColor(v.status))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                String.format(
                    "RS %.1f · 動能 %.1f · 已停 %d 週 · %s%s",
                    v.trail.last.ratio, v.trail.last.momentum, v.weeksInQuadrant,
                    v.headingDeg?.let { arrow(it) } ?: "",
                    v.regimeTag?.let { " · " + it } ?: "",
                ),
                fontSize = 11.sp, color = MUTED, fontFamily = FontFamily.Monospace,
            )
            if (v.quadrant == Quadrant.LEADING && !v.healthy) {
                Text("動能轉頭向下 — 勿追", fontSize = 11.sp, color = AMBER)
            }
        }
    }
}

private fun arrow(deg: Double): String = when {
    deg < 22.5 || deg >= 337.5 -> "→"
    deg < 67.5 -> "↗ 朝 Leading"
    deg < 112.5 -> "↑"
    deg < 157.5 -> "↖"
    deg < 202.5 -> "←"
    deg < 247.5 -> "↙"
    deg < 292.5 -> "↓"
    else -> "↘"
}

/* ================= 共用 ================= */

@Composable
private fun Header(snap: Snapshot?, state: RefreshState, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Sector RRG", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                when (state) {
                    is RefreshState.Running -> state.label + " " + state.done + "/" + state.total
                    is RefreshState.Failed -> state.message
                    else -> snap?.let {
                        LocalDate.ofEpochDay(it.asOfEpochDay).format(DF) + " · " + it.sourceId
                    } ?: "未有數據"
                },
                fontSize = 11.sp,
                color = if (state is RefreshState.Failed) RED else MUTED,
            )
        }
        if (state is RefreshState.Running) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onRefresh) { Text("刷新") }
        }
    }
}

@Composable
private fun EmptyState(state: RefreshState, onRefresh: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (state is RefreshState.Failed) state.message
                else "首次載入要拉 6 年日線，大約一分鐘。",
                fontSize = 13.sp, color = MUTED,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRefresh) { Text("載入數據") }
        }
    }
}

@Composable
private fun LinkChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 11.sp,
        color = Color(0xFF93C5FD),
        modifier = Modifier
            .background(Color(0x223B82F6), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun Pill(text: String, bg: Color) {
    Text(
        text, fontSize = 10.sp, color = Color.White,
        modifier = Modifier
            .background(bg.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
