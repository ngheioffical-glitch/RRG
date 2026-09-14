package com.sectorrrg.data.net

import com.sectorrrg.core.Bar

enum class Interval(val wire: String) { WEEKLY("1wk"), DAILY("1d") }

/**
 * 行情源抽象。Yahoo 嘅 chart endpoint 係非公開 API，隨時可以改或者封，
 * 大量抓取喺 ToS 上面係灰色地帶。所以呢層一定要係 interface，
 * 換源只需要換一個實作。
 */
interface PriceSource {
    val id: String
    suspend fun fetch(symbol: String, interval: Interval, years: Int): List<Bar>
}

class PriceSourceUnavailable(source: String, symbol: String, cause: Throwable? = null) :
    Exception("$source 拉唔到 $symbol", cause)
