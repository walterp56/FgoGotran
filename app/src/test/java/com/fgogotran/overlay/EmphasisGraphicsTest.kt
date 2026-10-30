package com.fgogotran.overlay

import android.graphics.*
import com.fgogotran.text.*
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

/** Native Android shaping/rasterization, with the actual bundled FGO fonts; no device install. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmphasisGraphicsTest {
    private val root=generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .first { File(it,"settings.gradle.kts").isFile }

    @Test fun `ruby visible right edge follows the previous base endpoint in all bundled fonts`() {
        val bounds=RectF(0f,0f,1500f,250f)
        for ((font,text) in listOf(
            "FgoStory.ttf" to "我们回到观星台〈迦勒底〉吧。",
            "FgoStory_td.otf" to "我們回到觀星臺〈迦勒底〉吧。",
            "FgoStory_en.ttf" to "Return to Chaldea〈カルデア〉."
        )) {
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface=Typeface.createFromFile(File(root,"app/src/main/assets/fonts/$font"))
                textSize=53f
            }
            val parsed=RubyRenderText.parse(text)
            val annotation=parsed.annotations.single()
            val plan=assertNotNull(RubyTextPainter.planExact(paint,parsed,100f,150f,bounds,0f,5.3f)).single()
            val ink=Rect().also { paint.getTextBounds(parsed.body,annotation.start,annotation.end,it) }
            val start=100f+paint.getRunAdvance(parsed.body,0,parsed.body.length,0,parsed.body.length,false,annotation.start)
            assertEquals(start+ink.right,plan.right,.02f,font)
            assertEquals(53f*.4f,plan.textSize,.01f)
            assertFalse(plan.emphasis)
        }
    }

    @Test fun `FGO reference dot and ruby examples produce complete visible annotation bands`() {
        val image=Bitmap.createBitmap(1600,580,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image).apply { drawColor(Color.rgb(27,48,78)) }
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface=Typeface.createFromFile(File(root,"app/src/main/assets/fonts/FgoStory_td.otf"))
            textSize=53f;color=Color.rgb(245,245,240)
        }
        val samples=listOf(
            EmphasisText.encode("こういうことになる可能性もあったのかよ。",listOf(EmphasisRange(0,12))),
            EmphasisText.encode("那一定，\n是笑著活下去的人死去的時候。",listOf(EmphasisRange(4,17))),
            "我們回到觀星臺〈迦勒底〉吧。",
            "這裡是異聞帶〈Lostbelt〉。"
        )
        var baseline=90f
        for (sample in samples) for (line in EmphasisText.lines(sample)) {
            val parsed=RubyRenderText.parse(line)
            val plan=assertNotNull(RubyTextPainter.planExact(paint,parsed,60f,baseline,
                RectF(20f,0f,1580f,580f),0f,paint.textSize*RubyTextPainter.GAP_RATIO))
            canvas.drawText(parsed.body,60f,baseline,paint)
            RubyTextPainter.drawPlanned(canvas,paint,plan,paint.color,1f)
            for (dot in plan.filter { it.emphasis }) {
                assertTrue(dot.right-dot.left in 10f..12f,"Reference FGO dots should be ~10–11 px, not 3 px")
                val centerX=((dot.left+dot.right)/2f).toInt()
                val centerY=((dot.top+dot.bottom)/2f).toInt()
                assertTrue(Color.red(image.getPixel(centerX,centerY))>220,"Dot center is not solid")
            }
            baseline+=105f
        }
        val directory=File(root,"app/build/reports/emphasis-eval").apply { mkdirs() }
        File(directory,"fgo-reference-style.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }

    @Test fun `each shaped character has one centered proportional dot in actual story fonts`() {
        val image=Bitmap.createBitmap(1600,640,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image).apply { drawColor(Color.rgb(27,48,78)) }
        val cases=listOf("這才是真正的理由。","我们回迦勒底吧。","This is the real reason.","Wi, Adio\u0301s!")
        var verified=0
        for ((row,body) in cases.withIndex()) {
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface=Typeface.createFromFile(File(root,"app/src/main/assets/fonts/"+
                    if (row==0) "FgoStory_td.otf" else if (row==1) "FgoStory.ttf" else "FgoStory_en.ttf"))
                textSize=if(row<2)54f else 48f
                color=Color.rgb(245,245,240)
            }
            val parsed=RubyRenderText.parse(EmphasisText.encode(body,listOf(EmphasisRange(0,body.length))))
            val x=60f;val y=100f+145f*row
            val planned=assertNotNull(RubyTextPainter.planExact(paint,parsed,x,y,RectF(24f,24f,1576f,630f),24f,paint.textSize*.1f))
            assertEquals(parsed.annotations.size,planned.size)
            assertTrue(planned.map { it.baseline }.distinct().size <= 1, "Dots must share one horizontal band")
            for ((annotation,dot) in parsed.annotations.zip(planned)) {
                val left=x+paint.getRunAdvance(body,0,body.length,0,body.length,false,annotation.start)
                val right=x+paint.getRunAdvance(body,0,body.length,0,body.length,false,annotation.end)
                assertEquals((left+right)/2f,(dot.left+dot.right)/2f,.02f)
                assertEquals(paint.textSize*.4f,dot.textSize,.01f)
                assertTrue(dot.emphasis)
                assertEquals(paint.textSize*.20f,dot.right-dot.left,.01f)
                assertEquals(dot.right-dot.left,dot.bottom-dot.top,.01f)
                assertTrue(dot.bottom < y)
                verified++
            }
            canvas.drawText(body,x,y,paint)
            RubyTextPainter.drawPlanned(canvas,paint,planned,paint.color,1f)
        }
        assertTrue(verified>40)
        val directory=File(root,"app/build/reports/emphasis-eval").apply { mkdirs() }
        File(directory,"actual-fonts.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }

    @Test fun `joint shrinking keeps dots and body proportional and rejects clipping`() {
        val body="Real reason"
        val parsed=RubyRenderText.parse(EmphasisText.encode(body,listOf(EmphasisRange(0,body.length))))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.DEFAULT }
        for(size in listOf(60f,40f,24f,12f)) {
            paint.textSize=size
            val dots=assertNotNull(RubyTextPainter.planExact(paint,parsed,10f,100f,RectF(0f,0f,1000f,200f),0f,size*.1f))
            assertEquals(parsed.annotations.size,dots.size)
            assertTrue(dots.all { kotlin.math.abs(it.textSize-size*.4f)<.01f })
        }
        paint.textSize=60f
        assertNull(RubyTextPainter.planExact(paint,parsed,10f,20f,RectF(0f,0f,1000f,200f),0f,6f))
    }

    @Test fun `reported missing dot translations render every marked character across both rows`() {
        val field=FgoTypefaceProvider::class.java.getDeclaredField("cachedTraditionalTypeface").apply { isAccessible=true }
        val previous=field.get(null)
        field.set(null,Typeface.createFromFile(File(root,"app/src/main/assets/fonts/FgoStory_td.otf")))
        val source=Bitmap.createBitmap(2340,1080,Bitmap.Config.ARGB_8888)
        try {
            val cases=listOf(
                Triple("worlds","那樣的世界還有六個。",listOf("那樣的世界還有六個")),
                Triple("memory","那與我的體質是同樣的東西。\n我所記憶的5分鐘，無論發生什麼都會持續留存下去。",
                    listOf("我所記憶的5分鐘","無論發生什麼都會持續留存下去"))
            )
            for ((name,body,words) in cases) {
                val ranges=words.map { val start=body.indexOf(it);EmphasisRange(start,start+it.length) }
                val region=ClassifiedRegion(TextRegion.DIALOGUE_BOX,emptyList(),Rect(316,833,2015,1052),com.fgogotran.ocr.OcrEngineId.PADDLE_OCR)
                val renderer=OverlayRenderer(RuntimeEnvironment.getApplication())
                val instruction=RenderInstruction(region,body,targetLocale="zh-Hant",emphasisRanges=ranges)
                renderer.prepareDialoguePlayback(source,listOf(instruction),DialoguePlaybackSettings(DialogueTextSpeed.NORMAL,1)).use { frame ->
                    val panel=frame.panels.single()
                    assertEquals(body.filterNot(Char::isWhitespace),panel.rows.joinToString("") { it.text }.filterNot(Char::isWhitespace))
                    assertEquals(words.sumOf { it.length },panel.rows.sumOf { it.readings.size })
                    assertTrue(panel.rows.flatMap { it.readings }.all { reading -> reading.layer.top>=panel.bounds.top })
                    val directory=File(root,"app/build/reports/emphasis-eval").apply { mkdirs() }
                    File(directory,"dialogue-$name-recovered.png").outputStream().use { frame.bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
            }
        } finally { source.recycle();field.set(null,previous) }
    }

    @Test fun `actual mono and bilingual dialogue retain body positions and reveal dots per character`() {
        val provider=FgoTypefaceProvider::class.java
        val fontFields=listOf("cachedSimplifiedTypeface" to "FgoStory.ttf",
            "cachedTraditionalTypeface" to "FgoStory_td.otf","cachedEnglishTypeface" to "FgoStory_en.ttf")
        val originals=fontFields.map { (name,file) ->
            val field=provider.getDeclaredField(name).apply { isAccessible=true }
            val previous=field.get(null)
            field.set(null,Typeface.createFromFile(File(root,"app/src/main/assets/fonts/$file")))
            field to previous
        }
        val context=RuntimeEnvironment.getApplication()
        val source=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888)
        try {
            for (target in listOf("zh-Hans","zh-Hant","en")) for (bilingual in listOf(false,true)) {
                val text=if(target=="en") "This is the real reason.\nLet's return to Chaldea." else
                    if(target=="zh-Hant") "這才是真正的理由。\n我們回迦勒底吧。" else "这才是真正的理由。\n我们回迦勒底吧。"
                val word=if(target=="en")"real reason" else "真正的理由"
                val begin=text.indexOf(word)
                val region=ClassifiedRegion(TextRegion.DIALOGUE_BOX,emptyList(),Rect(35,830,1805,1055),com.fgogotran.ocr.OcrEngineId.PADDLE_OCR)
                val instruction=RenderInstruction(region,text,sourceText="これが本当の理由だ。\nカルデアに帰ろう。",targetLocale=target,showOriginalText=bilingual)
                val renderer=OverlayRenderer(context)
                val settings=DialoguePlaybackSettings(DialogueTextSpeed.NORMAL,1)
                val annotatedInstruction=instruction.copy(emphasisRanges=listOf(EmphasisRange(begin,begin+word.length)))
                renderer.prepareDialoguePlayback(source,listOf(instruction),settings).use { plain ->
                    renderer.prepareDialoguePlayback(source,listOf(annotatedInstruction),settings).use { marked ->
                        val before=plain.panels.single();val after=marked.panels.single()
                        assertEquals(before.bounds.left,after.bounds.left,"$target/$bilingual")
                        assertEquals(before.bounds.right,after.bounds.right,"$target/$bilingual")
                        assertEquals(before.bounds.bottom,after.bounds.bottom,"$target/$bilingual")
                        // The ink cover may grow upward to cover the larger FGO-sized circles;
                        // body row origins and the available dialogue plane must not move.
                        val decorationHeight=after.rows.flatMap { it.readings }.maxOf { it.layer.bitmap.height }
                        assertTrue(after.bounds.top<=before.bounds.top && after.bounds.top>=before.bounds.top-decorationHeight,
                            "$target/$bilingual: circle cover escaped the reserved band")
                        assertEquals(before.rows.map { it.layer.top to it.x },after.rows.map { it.layer.top to it.x },"$target/$bilingual")
                        assertEquals(text.filterNot(Char::isWhitespace),after.rows.joinToString("") { it.text }.filterNot(Char::isWhitespace))
                        assertEquals(word.count { it.isLetterOrDigit() },after.rows.sumOf { it.readings.size })
                        assertTrue(after.originalRows.all { it.readings.isEmpty() })
                        assertTrue(after.rows.flatMap { it.readings }.map { it.end }.distinct().size>1)
                        val directory=File(root,"app/build/reports/emphasis-eval").apply { mkdirs() }
                        File(directory,"dialogue-$target-${if(bilingual)"bilingual" else "mono"}.png").outputStream().use {
                            marked.bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                        }
                    }
                }
            }
        } finally {
            source.recycle()
            originals.forEach { (field,previous)->field.set(null,previous) }
        }
    }

    @Test fun `styled history removes controls without losing quote colour spans`() {
        val body="「Real reason」"
        val packed=EmphasisText.encode(body,listOf(EmphasisRange(1,12)))
        val text=android.text.SpannableStringBuilder(packed)
        val colour=android.text.style.ForegroundColorSpan(Color.BLUE)
        text.setSpan(colour,text.length-1,text.length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val view=RubyTextView(RuntimeEnvironment.getApplication())
        view.setRubyText(text)
        assertEquals(body,view.text.toString())
        val styled=view.text as android.text.Spanned
        assertEquals(body.length-1,styled.getSpanStart(colour))
        assertEquals(body.length,styled.getSpanEnd(colour))
    }

    @Test fun `choice dots leave translated and Japanese body pixels unchanged`() {
        val context=RuntimeEnvironment.getApplication()
        val source=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888)
        try {
            for (target in listOf("zh-Hans","zh-Hant","en")) for (bilingual in listOf(false,true)) {
                val body=if(target=="en")"This is the real reason." else if(target=="zh-Hant")"這才是真正的理由。" else "这才是真正的理由。"
                val word=if(target=="en")"real reason" else "真正的理由"
                val begin=body.indexOf(word)
                val region=ClassifiedRegion(TextRegion.CHOICE_BUTTON,emptyList(),Rect(300,300,1620,435),com.fgogotran.ocr.OcrEngineId.PADDLE_OCR)
                val instruction=RenderInstruction(region,body,sourceText="これが本当の理由だ。",targetLocale=target,showOriginalText=bilingual)
                val renderer=OverlayRenderer(context)
                val plain=renderer.render(source,listOf(instruction),1920,1080).copy(Bitmap.Config.ARGB_8888,false)
                val annotated=instruction.copy(emphasisRanges=listOf(EmphasisRange(begin,begin+word.length)))
                val marked=renderer.render(source,listOf(annotated),1920,1080)
                val before=IntArray(1920*1080);val after=IntArray(before.size)
                plain.getPixels(before,0,1920,0,0,1920,1080);marked.getPixels(after,0,1920,0,0,1920,1080)
                assertTrue(before.indices.any { before[it]!=after[it] },"No visible choice dots: $target/$bilingual")
                var bodyPixels=0
                for (i in before.indices) if (Color.alpha(before[i])>128 && Color.green(before[i])>180 && Color.blue(before[i])>180) {
                    assertEquals(before[i],after[i],"Choice body moved: $target/$bilingual at $i")
                    bodyPixels++
                }
                assertTrue(bodyPixels>20)
                plain.recycle()
            }
        } finally { source.recycle() }
    }
}
