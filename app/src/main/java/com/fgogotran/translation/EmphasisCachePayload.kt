package com.fgogotran.translation

import com.fgogotran.text.EmphasisRange
import com.fgogotran.text.EmphasisText
import kotlinx.serialization.json.*

/** Existing Room string column, with a separate conditional cache key and no schema migration. */
internal object EmphasisCachePayload {
    fun encode(source: String, translated: String): String {
        if (!EmphasisText.has(source)) return translated
        val parsed = EmphasisText.decode(translated)
        return buildJsonObject {
            put("emphasis_cache",1)
            put("text",parsed.text)
            put("ranges",buildJsonArray { parsed.ranges.forEach { range ->
                add(buildJsonArray { add(range.start); add(range.endExclusive) })
            } })
        }.toString()
    }

    fun decode(source: String, stored: String): String? {
        if (!EmphasisText.has(source)) return stored
        return runCatching {
            val value=Json.parseToJsonElement(stored).jsonObject
            require(value["emphasis_cache"]?.jsonPrimitive?.int == 1)
            val text=value.getValue("text").jsonPrimitive.content
            var end=0
            val ranges=value.getValue("ranges").jsonArray.map { element ->
                val pair=element.jsonArray
                require(pair.size==2)
                EmphasisRange(pair[0].jsonPrimitive.int,pair[1].jsonPrimitive.int).also {
                    require(it.start >= end && it.endExclusive > it.start && it.endExclusive <= text.length)
                    end=it.endExclusive
                }
            }
            EmphasisText.encode(text,ranges)
        }.getOrNull()
    }
}
