package com.fgogotran.translation

import com.fgogotran.text.*
import com.fgogotran.overlay.RubyRenderText
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assume.assumeNotNull
import kotlin.test.*

/** Opt-in replay of paid results through the production field contract, codec, cache and parser. */
class EmphasisCollectedResponseTest {
    @Test fun `collected A B results never leak controls or drop translated body`() {
        val path=System.getenv("FGO_EMPHASIS_EVAL_RESPONSES")
        assumeNotNull(path)
        val initial=Json.parseToJsonElement(File(requireNotNull(path)).readText(Charsets.UTF_8)).jsonObject
        val runs=initial.getValue("runs").jsonArray.map { it.jsonObject }.toMutableList()
        System.getenv("FGO_EMPHASIS_EVAL_CORRECTION")?.let { correctedPath ->
            val corrections=Json.parseToJsonElement(File(correctedPath).readText(Charsets.UTF_8)).jsonObject.getValue("runs").jsonArray
            fun identity(obj:JsonObject)=listOf("id","target","variant","trial").map { obj.getValue(it).toString() }
            for (element in corrections) {
                val item=element.jsonObject;val index=runs.indexOfFirst { identity(it)==identity(item) }
                require(index>=0);runs[index]=item
            }
        }
        assertEquals(144,runs.size)
        var requested=0;var kept=0
        for (run in runs) {
            assertNull(run["error"])
            val raw=run.getValue("content").jsonPrimitive.content
            val expected=run.getValue("expects_emphasis").jsonPrimitive.boolean
            val fields=if(run.getValue("format").jsonPrimitive.content=="array")
                GeneralTranslationResponseContract.parseStrings(Json.parseToJsonElement(raw),2) else listOf(raw)
            for ((i,field) in fields.withIndex()) {
                val owns=expected && i==0
                val source=if(owns)EmphasisText.encode("source",listOf(EmphasisRange(0,6))) else "source"
                val packed=EmphasisText.fromResponse(source,field,annotationRequest=expected)
                val visible=EmphasisText.decode(packed)
                assertEquals(field.replace("⟦","").replace("⟧",""),visible.text)
                assertTrue(visible.text.none { it in '\uE100'..'\uE102' || it=='⟦' || it=='⟧' })
                if(owns){requested++;if(visible.ranges.isNotEmpty())kept++}else assertTrue(visible.ranges.isEmpty())
                val stored=EmphasisCachePayload.encode(source,packed)
                assertEquals(packed,EmphasisCachePayload.decode(source,stored))
                val display=RubyRenderText.parse(packed)
                assertTrue(display.body.none { it in '\uE100'..'\uE102' })
            }
        }
        assertEquals(48,requested)
        assertTrue(kept.toDouble()/requested>=.95,"$kept/$requested preserved")
        val directory=File(requireNotNull(path)).parentFile
        File(directory,"production-replay.json").writeText("""{"responses":144,"requested":$requested,"preserved":$kept,"controlLeaks":0,"contractErrors":0}""")
    }
}
