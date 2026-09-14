package com.sectorrrg.data.net

import com.sectorrrg.core.Bar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneOffset

class YahooPriceSource(private val client: OkHttpClient) : PriceSource {

    override val id = "yahoo"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(symbol: String, interval: Interval, years: Int): List<Bar> =
        withContext(Dispatchers.IO) {
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/" + wire(symbol) +
                "?range=" + years + "y&interval=" + interval.wire + "&includePrePost=false"
            val req = Request.Builder()
                .url(url)
                // 預設 OkHttp UA 會被擋，一定要帶瀏覽器 UA
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .build()
            val body = try {
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw PriceSourceUnavailable(id, symbol)
                    r.body?.string() ?: throw PriceSourceUnavailable(id, symbol)
                }
            } catch (e: Exception) {
                throw PriceSourceUnavailable(id, symbol, e)
            }
            parse(json.decodeFromString(ChartEnvelope.serializer(), body), symbol)
        }

    private fun parse(env: ChartEnvelope, symbol: String): List<Bar> {
        val res = env.chart.result?.firstOrNull() ?: throw PriceSourceUnavailable(id, symbol)
        val ts = res.timestamp ?: throw PriceSourceUnavailable(id, symbol)
        val q = res.indicators.quote.firstOrNull() ?: throw PriceSourceUnavailable(id, symbol)
        val adj = res.indicators.adjclose?.firstOrNull()?.adjclose
        val out = ArrayList<Bar>(ts.size)
        for (i in ts.indices) {
            val c = q.close?.getOrNull(i) ?: continue
            if (c <= 0.0) continue
            // 一定要用復權收市價：未復權嘅話，拆股會變成假暴跌污染動能分數，
            // 而派息完全唔計入報酬 —— TLT / IEF 呢類靠派息嘅資產會被系統性低估。
            // OHLC 按同一個比例縮放，等 ATR、FVG 都行喺同一套基準上面。
            val a = adj?.getOrNull(i)
            val f = if (a != null && a > 0.0) a / c else 1.0
            val o = (q.open?.getOrNull(i) ?: c) * f
            val h = (q.high?.getOrNull(i) ?: c) * f
            val l = (q.low?.getOrNull(i) ?: c) * f
            val v = q.volume?.getOrNull(i) ?: 0.0
            // Yahoo 週線 timestamp 係該週交易起始日，跨標的對齊得到
            val day = Instant.ofEpochSecond(ts[i]).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
            out += Bar(day, o, h, l, c * f, v, rawClose = c)
        }
        return out.distinctBy { it.epochDay }.sortedBy { it.epochDay }
    }

    /** BRK.B 之類類別股嘅代號差異。 */
    private fun wire(symbol: String) = symbol.replace('.', '-')

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    }
}

@Serializable
private data class ChartEnvelope(val chart: Chart)

@Serializable
private data class Chart(val result: List<ChartResult>? = null)

@Serializable
private data class ChartResult(
    val timestamp: List<Long>? = null,
    val indicators: QuoteIndicators,
)

@Serializable
private data class QuoteIndicators(
    val quote: List<Quote> = emptyList(),
    val adjclose: List<AdjClose>? = null,
)

@Serializable
private data class AdjClose(val adjclose: List<Double?>? = null)

@Serializable
private data class Quote(
    val open: List<Double?>? = null,
    val high: List<Double?>? = null,
    val low: List<Double?>? = null,
    val close: List<Double?>? = null,
    val volume: List<Double?>? = null,
)
