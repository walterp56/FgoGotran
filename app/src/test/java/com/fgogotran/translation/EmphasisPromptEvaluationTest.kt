package com.fgogotran.translation

import com.fgogotran.text.*
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic FGO-shaped fixtures; exports production builders only, never makes network calls. */
class EmphasisPromptEvaluationTest {
    private data class Case(val id:String,val source:String,val words:List<String>,val choice:Boolean=false)
    private val cases=listOf(
        Case("reason","これが本当の理由だ。",listOf("本当の理由")),
        Case("enemy","彼は人類の敵ではない。私たちの味方だ。",listOf("人類の敵")),
        Case("glossary","カルデアは、私たちの帰る場所です。",listOf("カルデア")),
        Case("master","マスター、今度は私があなたを守ります。",listOf("私が")),
        Case("contrast","それは夢ではない。現実だ。",listOf("夢","現実")),
        Case("foreign","メルシー、マスター。あなたのおかげです。",listOf("メルシー")),
        Case("choice","行こう、マシュ！",listOf("行こう"),true),
        Case("two_rows","ここは安全です。\nでも、油断は禁物ですよ。",listOf("油断は禁物")),
        Case("control_plain","先輩、準備はできましたか？",emptyList()),
        Case("control_ruby","大令呪〈シリウスライト〉を使う。",emptyList()),
        Case("control_choice","ゴッフ！？",emptyList(),true),
        Case("control_symbols","即ち、※※※※※※※※が設置された世界であり───",emptyList())
    )

    @Test fun `export bounded paired general prompts and separate Sakura compatibility fixtures`() {
        val builder=PromptBuilder()
        val requests=buildJsonArray {
            for (case in cases) for (target in listOf("zh-Hans","zh-Hant","en")) for (variant in listOf("baseline","candidate")) {
                val ranges=if (variant=="candidate") case.words.map { word ->
                    val start=case.source.indexOf(word);require(start>=0);EmphasisRange(start,start+word.length)
                } else emptyList()
                val source=EmphasisText.encode(case.source,ranges)
                val context=builder.buildPromptContext(
                    if (case.choice) PromptOutputFormat.JSON_ARRAY else PromptOutputFormat.PLAIN_TEXT,
                    case.source,targetLanguage=target,isDialogue=!case.choice,isChoiceBatch=case.choice)
                val glossary=listOf(TranslationGlossaryEntry("カルデア",if (target=="en") "Chaldea" else "迦勒底"),
                    TranslationGlossaryEntry("マシュ",if (target=="en") "Mash" else if (target=="zh-Hant") "瑪修" else "玛修","female"))
                    .filter { case.source.contains(it.source) }
                val user=if (case.choice) buildGeneralBatchUserPrompt(listOf(source,"待って！"),
                    translateAsChoices=true,glossaryEntries=glossary) else
                    buildGeneralSingleUserPrompt(builder,source,emptyList(),glossaryEntries=glossary)
                add(buildJsonObject {
                    put("id",case.id);put("target",target);put("variant",variant)
                    put("source",case.source);put("format",if (case.choice) "array" else "text")
                    put("expects_emphasis",ranges.isNotEmpty())
                    put("messages",buildJsonArray {
                        add(buildJsonObject { put("role","system");put("content",EmphasisText.wire(builder.buildSystemPrompt(context))) })
                        add(buildJsonObject { put("role","user");put("content",EmphasisText.wire(user)) })
                    })
                })
            }
        }
        val sakura=buildJsonArray {
            for (case in cases) for (variant in listOf("baseline","candidate")) {
                val source=EmphasisText.encode(case.source,if (variant=="candidate") case.words.map {
                    EmphasisRange(case.source.indexOf(it),case.source.indexOf(it)+it.length)
                } else emptyList())
                val context=builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT,case.source,isDialogue=!case.choice)
                val user=SakuraPromptBuilder.buildSingleRequest(source,emptyList(),"",context,translateAsChoices=case.choice).userPrompt
                assertFalse(user.contains("visual emphasis"))
                add(buildJsonObject {
                    put("id",case.id);put("variant",variant)
                    put("system",SakuraPromptBuilder.buildSystemPrompt());put("user",EmphasisText.wire(user))
                })
            }
        }
        assertEquals(72,requests.size)
        for (case in cases.filter { it.words.isEmpty() }) for (target in listOf("zh-Hans","zh-Hant","en")) {
            val pair=requests.filter { it.jsonObject["id"]!!.jsonPrimitive.content==case.id && it.jsonObject["target"]!!.jsonPrimitive.content==target }
            assertEquals(pair[0].jsonObject["messages"],pair[1].jsonObject["messages"])
        }
        val root=generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it,"settings.gradle.kts").exists() }
        val out=File(requireNotNull(root),"app/build/reports/emphasis-eval/requests.json")
        requireNotNull(out.parentFile).mkdirs()
        out.writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("scope","emphasis");put("synthetic_cases",true);put("requests",requests);put("sakura_offline",sakura)
        }))
    }
}
