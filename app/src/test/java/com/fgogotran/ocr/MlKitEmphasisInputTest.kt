package com.fgogotran.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.fgogotran.text.EmphasisRange
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MlKitEmphasisInputTest {
    @Test fun `ML Kit 2x crop retains real dot evidence without changing recognition pixels`() {
        for ((name,expected) in listOf("worlds" to 12,"memory" to 22)) {
            val original=checkNotNull(javaClass.getResourceAsStream("/ocr/jp-emphasis-$name.png")).use { BitmapFactory.decodeStream(it) }
            val enlarged=Bitmap.createScaledBitmap(original,original.width*2,original.height*2,true)
            try {
                val before=IntArray(enlarged.width*enlarged.height)
                enlarged.getPixels(before,0,enlarged.width,0,0,enlarged.width,enlarged.height)
                val dots=MlKitEmphasisInput.dots(enlarged)
                assertEquals(expected,dots.size,name)
                assertTrue(dots.all { it.centerX in 0f..enlarged.width.toFloat() && it.verifiedRowTop!=null })
                val after=IntArray(before.size)
                enlarged.getPixels(after,0,enlarged.width,0,0,enlarged.width,enlarged.height)
                assertTrue(before.contentEquals(after),"ML Kit recognition bitmap was modified")
            } finally { enlarged.recycle();original.recycle() }
        }
    }
    @Test fun `shared choice punctuation recovery retains ML Kit emphasis metadata`() {
        val bitmap=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)
        try {
            val context=ChoiceOcrContext.create(bitmap,listOf(Rect(0,0,200,100)))
            val line=OcrTextLine("本当。",Rect(20,30,140,70),.95f,emphasisRanges=listOf(EmphasisRange(0,2)))
            val result=context.recover(OcrResult(listOf(line),line.text,OcrEngineId.ML_KIT))
            assertEquals(line.text,result.lines.single().text)
            assertEquals(line.emphasisRanges,result.lines.single().emphasisRanges)
        } finally { bitmap.recycle() }
    }
}
