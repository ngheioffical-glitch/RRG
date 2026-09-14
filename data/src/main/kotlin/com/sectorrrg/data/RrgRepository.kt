package com.sectorrrg.data

import android.content.Context
import com.sectorrrg.core.Bar
import com.sectorrrg.core.Momentum
import com.sectorrrg.core.MomentumScore
import com.sectorrrg.core.Quadrant
import com.sectorrrg.core.Rotation
import com.sectorrrg.core.RotationPlan
import com.sectorrrg.core.Rrg
import com.sectorrrg.core.RrgTrail
import com.sectorrrg.core.toWeekly
import com.sectorrrg.data.net.Interval
import com.sectorrrg.data.net.PriceSource
import com.sectorrrg.data.net.PriceSourceUnavailable
import com.sectorrrg.data.net.StooqPriceSource
import com.sectorrrg.data.net.YahooPriceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class SectorView(
    val symbol: String,
    val name: String,
    val colorArgb: Long,
    val trail: RrgTrail,
    val weeksInQuadrant: Int,
    val headingDeg: Double?,
    val healthy: Boolean,
    val regimeTag: String?,
    val isMacro: Boolean = false,
) {
    val quadrant: Quadrant get() = trail.quadrant

    val status: String
        get() = when {
            quadrant == Quadrant.LEADING && !healthy -> "TRIM"
            quadrant == Quadrant.LEADING && weeksInQuadrant <= 3 -> "NEW_LEADING"
            quadrant == Quadrant.LEADING -> "LEADING"
            quadrant == Quadrant.IMPROVING && healthy -> "WATCH"
            quadrant == Quadrant.WEAKENING -> "TRIM"
            else -> "AVOID"
        }
}

data class Snapshot(
    val asOfEpochDay: Long,
    val plan: RotationPlan,
    val sectors: List<SectorView>,
    val macro: List<SectorView>,
    val daysToRebalance: Int,
    val sourceId: String,
)

sealed interface RefreshState {
    data object Idle : RefreshState
    data class Running(val done: Int, val total: Int, val label: String) : RefreshState
    data class Failed(val message: String) : RefreshState
    data class Done(val atMs: Long) : RefreshState
}

class RrgRepository(ctx: Context) {

    private val app = ctx.applicationContext
    private val cache = Cache(app)
    private val refreshLock = Mutex()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val yahoo = YahooPriceSource(http)
    private val stooq = StooqPriceSource(http)
    private val sources: List<PriceSource> = listOf(yahoo, stooq)

    private val _state = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val state = _state.asStateFlow()

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot = _snapshot.asStateFlow()

    private val _signals = MutableStateFlow(cache.readSignals())
    val signals = _signals.asStateFlow()

    fun markSignalsSeen() {
        val seen = _signals.value.map { it.copy(seen = true) }
        _signals.value = seen
        cache.writeSignals(seen)
    }

    suspend fun refresh(force: Boolean = false): Result<Snapshot> = refreshLock.withLock {
        runCatching {
            withContext(Dispatchers.IO) {
                val today = LocalDate.now()

                val daily = fetchMany(Universe.allTopLevel, TOP_YEARS, force, "資產行情")
                val spyDaily = daily[Universe.BENCHMARK]
                    ?: error("拉唔到基準 " + Universe.BENCHMARK + "，冇基準乜都做唔到")

                // --- 數據體檢 ---
                val suspect = HashMap<String, String>()
                for ((sym, bars) in daily) Quality.check(bars, today)?.let { suspect[sym] = it }

                // --- 動能排名 ---
                val scores = HashMap<String, MomentumScore>()
                for ((sym, bars) in daily) {
                    Momentum.score(
                        sym, bars,
                        RotationConfig.TRAIL_FLOOR,
                        RotationConfig.TRAIL_CAP,
                        RotationConfig.DIVIDEND_WITHHOLDING,
                    )?.let { scores[sym] = it }
                }
                val spyScore = scores[Universe.BENCHMARK]

                var plan = Rotation.plan(
                    candidates = Universe.rotation.map { Triple(it.symbol, it.name, it.assetClass) },
                    scores = scores,
                    suspect = suspect,
                    spy = spyScore,
                    slots = RotationConfig.SLOTS,
                    targetVol = RotationConfig.TARGET_VOL,
                )

                // --- 跨源抽驗：只驗持倉同頭三名，成本細 ---
                val toVerify = (plan.selected.map { it.symbol } +
                    plan.rows.take(RotationConfig.CROSS_CHECK_TOP).map { it.symbol }).distinct()
                val extra = crossCheck(toVerify, daily)
                if (extra.isNotEmpty()) {
                    suspect.putAll(extra)
                    plan = Rotation.plan(
                        candidates = Universe.rotation.map { Triple(it.symbol, it.name, it.assetClass) },
                        scores = scores,
                        suspect = suspect,
                        spy = spyScore,
                        slots = RotationConfig.SLOTS,
                        targetVol = RotationConfig.TARGET_VOL,
                    )
                }

                // --- RRG：前望視角，睇邊個資產喺 Improving 準備接棒 ---
                val weeklyOf = daily.mapValues { toWeekly(it.value) }
                val benchWeekly = weeklyOf[Universe.BENCHMARK].orEmpty()
                val sectorViews = Universe.sectors
                    .mapNotNull { view(it, weeklyOf[it.symbol], benchWeekly, false) }
                    .sortedByDescending { it.trail.strength() }
                val macroViews = Universe.macro
                    .mapNotNull { view(it, weeklyOf[it.symbol], benchWeekly, true) }


                val all = sectorViews + macroViews
                val fresh = diffSignals(all, plan)
                if (fresh.isNotEmpty()) {
                    val existing = _signals.value.map { it.id }.toHashSet()
                    val merged = fresh.filter { it.id !in existing } + _signals.value
                    _signals.value = merged.take(300)
                    cache.writeSignals(merged)
                }
                cache.writeQuadStates(
                    all.map { QuadState(it.symbol, it.quadrant.name, it.healthy, it.trail.last.epochDay) }
                )

                Snapshot(
                    asOfEpochDay = spyDaily.last().epochDay,
                    plan = plan,
                    sectors = sectorViews,
                    macro = macroViews,
                    daysToRebalance = daysToMonthEnd(today),
                    sourceId = cache.sourceOf(Universe.BENCHMARK, WIRE) ?: "cache",
                ).also {
                    _snapshot.value = it
                    _state.value = RefreshState.Done(System.currentTimeMillis())
                }
            }
        }.onFailure { e ->
            _state.value = RefreshState.Failed(e.message ?: "刷新失敗")
        }
    }

    /** 月底換入；每日離場由 200 日線同追蹤止損負責，唔等呢個日子。 */
    private fun daysToMonthEnd(today: LocalDate): Int {
        val last = YearMonth.from(today).atEndOfMonth()
        return (last.toEpochDay() - today.toEpochDay()).toInt().coerceAtLeast(0)
    }

    private suspend fun crossCheck(
        symbols: List<String>,
        daily: Map<String, List<Bar>>,
    ): Map<String, String> {
        val out = HashMap<String, String>()
        for (sym in symbols) {
            val mine = daily[sym]?.lastOrNull()?.close ?: continue
            val other = runCatching { stooq.fetch(sym, Interval.DAILY, 1) }.getOrNull()
                ?.lastOrNull()?.close ?: continue
            Quality.crossCheck(mine, other)?.let { out[sym] = it }
            delay(120)
        }
        return out
    }

    private fun view(
        def: SectorDef,
        weekly: List<Bar>?,
        bench: List<Bar>,
        macro: Boolean,
    ): SectorView? {
        val bars = weekly ?: return null
        if (bench.isEmpty()) return null
        val trail = Rrg.compute(def.symbol, bars, bench) ?: return null
        val t = trail.tail()
        return SectorView(
            symbol = def.symbol,
            name = def.name,
            colorArgb = def.colorArgb,
            trail = trail,
            weeksInQuadrant = trail.weeksInQuadrant(),
            headingDeg = t?.headingDeg,
            healthy = t?.healthy ?: false,
            regimeTag = Universe.regimeTag(def.symbol),
            isMacro = macro,
        )
    }


    private suspend fun fetchMany(
        symbols: List<String>,
        years: Int,
        force: Boolean,
        label: String,
    ): Map<String, List<Bar>> = coroutineScope {
        val list = symbols.distinct()
        val gate = Semaphore(4)
        val done = AtomicInteger(0)
        _state.value = RefreshState.Running(0, list.size, label)
        val jobs = list.map { sym ->
            async {
                gate.withPermit {
                    delay(120)
                    val bars = runCatching { load(sym, years, force) }.getOrNull()
                    _state.value = RefreshState.Running(done.incrementAndGet(), list.size, label)
                    sym to bars
                }
            }
        }
        jobs.awaitAll()
            .mapNotNull { (s, b) -> if (b.isNullOrEmpty()) null else s to b }
            .toMap()
    }

    private suspend fun load(symbol: String, years: Int, force: Boolean): List<Bar> {
        val cached = cache.read(symbol, WIRE)
        val fresh = cached != null &&
            System.currentTimeMillis() - cached.fetchedAtMs < TTL_MS &&
            cached.bars.size >= MIN_DAILY &&
            cached.years >= years
        if (!force && fresh) return cached!!.toBars()

        var lastErr: Throwable? = null
        for (src in sources) {
            try {
                val bars = src.fetch(symbol, Interval.DAILY, years)
                if (bars.size < MIN_DAILY) {
                    lastErr = PriceSourceUnavailable(src.id, symbol)
                    continue
                }
                cache.write(symbol, WIRE, src.id, bars, years)
                return bars
            } catch (e: Exception) {
                lastErr = e
            }
        }
        if (cached != null && cached.bars.size >= MIN_DAILY) return cached.toBars()
        throw lastErr ?: PriceSourceUnavailable("all", symbol)
    }

    /**
     * 推送嘅不對稱：止損同 200 日線穿破要即時知，排名日常變動唔出聲。
     */
    private fun diffSignals(views: List<SectorView>, plan: RotationPlan): List<Signal> {
        val prev = cache.readQuadStates().associateBy { it.symbol }
        val now = System.currentTimeMillis()
        val day = now / 86_400_000L
        val out = ArrayList<Signal>()

        if (!plan.marketOn) {
            out += Signal(
                "MARKET_OFF|$day", now, "EXIT", Universe.BENCHMARK,
                "SPY 喺 200 日線之下，總閘關閉：唔再入場",
            )
        }
        for (r in plan.selected) {
            if (r.mom.price <= r.mom.trailStop) {
                out += Signal(
                    "STOP|" + r.symbol + "|" + day, now, "EXIT", r.symbol,
                    r.name + " 觸及追蹤止損 " + String.format("%.2f", r.mom.trailStop) + "，即日離場去現金",
                )
            } else if (!r.mom.aboveSma200) {
                out += Signal(
                    "SMA|" + r.symbol + "|" + day, now, "EXIT", r.symbol,
                    r.name + " 跌穿 200 日線，即日離場",
                )
            }
        }
        for (v in views) {
            val p = prev[v.symbol] ?: continue
            if (p.quadrant != Quadrant.IMPROVING.name && v.quadrant == Quadrant.IMPROVING && v.healthy) {
                out += Signal(
                    "IMPROVING|" + v.symbol + "|" + v.trail.last.epochDay, now, "IMPROVING", v.symbol,
                    v.name + " 轉入 Improving 且動能向上，下輪換馬候選",
                )
            }
        }
        return out
    }

    private companion object {
        const val WIRE = "1d"
        const val TTL_MS = 8 * 3600_000L
        const val MIN_DAILY = 200
        const val TOP_YEARS = 6
    }
}
