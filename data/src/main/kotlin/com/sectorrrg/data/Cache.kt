package com.sectorrrg.data

import android.content.Context
import com.sectorrrg.core.Bar
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CachedBar(
    val d: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Double,
    val rc: Double = 0.0,
)

@Serializable
data class CachedSeries(
    val fetchedAtMs: Long,
    val source: String,
    val bars: List<CachedBar>,
    val years: Int = 0,
)

@Serializable
data class QuadState(val symbol: String, val quadrant: String, val healthy: Boolean, val epochDay: Long)

@Serializable
data class Signal(
    val id: String,
    val tsMs: Long,
    val type: String,
    val symbol: String,
    val note: String,
    val seen: Boolean = false,
)

/**
 * 行情快取用檔案，唔用資料庫。資料量細（大約 120 個標的 × 260 根），
 * 冇 schema 遷移、冇註解處理器，建構鏈短好多。
 */
class Cache(ctx: Context) {

    private val dir = File(ctx.filesDir, "series").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private fun f(symbol: String, interval: String) =
        File(dir, symbol.replace('.', '_').replace('/', '_') + "_" + interval + ".json")

    fun read(symbol: String, interval: String): CachedSeries? = runCatching {
        val file = f(symbol, interval)
        if (!file.exists()) null else json.decodeFromString(CachedSeries.serializer(), file.readText())
    }.getOrNull()

    fun write(symbol: String, interval: String, source: String, bars: List<Bar>, years: Int) {
        runCatching {
            val s = CachedSeries(
                fetchedAtMs = System.currentTimeMillis(),
                source = source,
                bars = bars.map { CachedBar(it.epochDay, it.open, it.high, it.low, it.close, it.volume, it.rawClose) },
                years = years,
            )
            f(symbol, interval).writeText(json.encodeToString(CachedSeries.serializer(), s))
        }
    }

    fun sourceOf(symbol: String, interval: String): String? = read(symbol, interval)?.source

    // ---- 象限狀態（用嚟 diff 出轉變事件）----

    private val quadFile = File(ctx.filesDir, "quad_state.json")

    fun readQuadStates(): List<QuadState> = runCatching {
        if (!quadFile.exists()) emptyList()
        else json.decodeFromString(ListSerializer(QuadState.serializer()), quadFile.readText())
    }.getOrDefault(emptyList())

    fun writeQuadStates(rows: List<QuadState>) {
        runCatching { quadFile.writeText(json.encodeToString(ListSerializer(QuadState.serializer()), rows)) }
    }

    // ---- 訊號 ----

    private val signalFile = File(ctx.filesDir, "signals.json")

    fun readSignals(): List<Signal> = runCatching {
        if (!signalFile.exists()) emptyList()
        else json.decodeFromString(ListSerializer(Signal.serializer()), signalFile.readText())
    }.getOrDefault(emptyList())

    fun writeSignals(rows: List<Signal>) {
        runCatching { signalFile.writeText(json.encodeToString(ListSerializer(Signal.serializer()), rows.take(300))) }
    }
}

fun CachedSeries.toBars(): List<Bar> = bars.map {
    // 舊快取冇 rc 欄位，rc = 0 就當冇未復權數據，派息部分變 0
    Bar(it.d, it.o, it.h, it.l, it.c, it.v, if (it.rc > 0.0) it.rc else it.c)
}
