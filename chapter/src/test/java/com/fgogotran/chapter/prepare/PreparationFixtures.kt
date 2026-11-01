package com.fgogotran.chapter.prepare

import kotlinx.serialization.json.*
import java.io.File

internal val preparationJp = "＄synthetic\n[bgm 1]\n[charaSet A 1 1 名前]\n＠A：名前\n台詞[#星見台:カルデア][%1][&彼:彼女]。\n[k]\n？1：選択\n＠A：名前\n返事。\n[k]\n？！\n[end]\n"
internal fun localized(text: String) = text.replace("名前", "Name").replace("台詞", "Dialogue").replace("星見台:カルデア", "Observatory:Chaldea")
    .replace("彼:彼女", "he:she").replace("選択", "Choice").replace("返事", "Reply")

internal fun preparationInfo(hash: String = "abcdef") = PreparationFiles.canonical(buildJsonObject {
    AtlasEndpoints.regions.forEach { put(it, buildJsonObject { put("hash", hash); put("timestamp", 1) }) }
})
internal fun preparationWar(region: String, ids: List<String>): ByteArray = PreparationFiles.canonical(buildJsonObject {
    put("id", 100)
    put("spots", buildJsonArray { add(buildJsonObject { put("quests", buildJsonArray { add(buildJsonObject {
        put("id", 1000000); put("warId", 100); put("type", "main"); put("phases", buildJsonArray { add(1) })
        put("phaseScripts", buildJsonArray { add(buildJsonObject {
            put("phase", 1); put("scripts", buildJsonArray { ids.forEach { id -> add(buildJsonObject {
                put("scriptId", id); put("script", "https://static.atlasacademy.io/$region/Script/01/$id.txt")
            }) } })
        }) })
    }) }) }) })
})
internal fun preparationFetch(ids: List<String>, text: String = preparationJp, intercept: (String) -> Unit = {}): AtlasFetch = AtlasFetch { url, _ ->
    intercept(url)
    when {
        url.endsWith("/info") -> preparationInfo()
        "/war/" in url -> preparationWar(url.split('/')[4], ids)
        else -> (if ("/JP/" in url) text else localized(text)).toByteArray(Charsets.UTF_8)
    }
}
internal fun preparationSnapshot(repository: File, ids: List<String>, text: String = preparationJp): AtlasSnapshot =
    AtlasSnapshot.download(repository, setOf(100), fetch = preparationFetch(ids, text))
