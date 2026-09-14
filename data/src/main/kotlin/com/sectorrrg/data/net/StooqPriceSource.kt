package com.sectorrrg.data.net

import com.sectorrrg.core.Bar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate

/** 後備源：Yahoo 掛咗照樣有數。CSV 格式，冇 volume 就當 0。 */
class StooqPriceSource(private val client: OkHttpClient) : PriceSource {

    override val id = "stooq"

    override suspend fun fetch(symbol: String, interval: Interval, years: Int): List<Bar> =
        withContext(Dispatchers.IO) {
            val i = if (interval == Interval.WEEKLY) "w" else "d"
            val from = LocalDate.now().minusYears(years.toLong()).toString().replace("-", "")
            val url = "https://stooq.com/q/d/l/?s=" + wire(symbol) + "&d1=" + from + "&i=" + i
            val text = try {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw PriceSourceUnavailable(id, symbol)
                    r.body?.string() ?: throw PriceSourceUnavailable(id, symbol)
                }
            } catch (e: Exception) {
                throw PriceSourceUnavailable(id, symbol, e)
            }
            val lines = text.trim().lines()
            if (lines.size < 2 || !lines[0].startsWith("Date")) {
                throw PriceSourceUnavailable(id, symbol)
            }
            lines.drop(1).mapNotNull { line ->
                val f = line.split(',')
                if (f.size < 5) return@mapNotNull null
                val c = f[4].toDoubleOrNull() ?: return@mapNotNull null
                val day = runCatching { LocalDate.parse(f[0]).toEpochDay() }.getOrNull()
                    ?: return@mapNotNull null
                Bar(
                    epochDay = day,
                    open = f[1].toDoubleOrNull() ?: c,
                    high = f[2].toDoubleOrNull() ?: c,
                    low = f[3].toDoubleOrNull() ?: c,
                    close = c,
                    volume = f.getOrNull(5)?.toDoubleOrNull() ?: 0.0,
                )
            }.sortedBy { it.epochDay }
        }

    private fun wire(symbol: String): String =
        symbol.lowercase().replace('.', '-') + ".us"
}
