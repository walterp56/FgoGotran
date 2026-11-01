package com.fgogotran.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.fgogotran.overlay.DialoguePlaybackController
import com.fgogotran.overlay.DialoguePlaybackSettings

/** A close-up of the real dialogue renderer, with its real playback/ruby layers. */
@Composable
internal fun DialoguePlaybackPreview(
    settings: DialoguePlaybackSettings,
    locale: String,
    bilingual: Boolean,
    includeRuby: Boolean,
    replay: Int
) {
    val context = LocalContext.current
    val example = remember(locale) { DialoguePlaybackExample.forTarget(locale) }
    val scene = remember(context, example, locale, bilingual) {
        DialoguePlaybackPreviewScene.create(context, example, locale, bilingual)
    }
    DisposableEffect(scene) { onDispose { scene.close() } }
    val timeline = remember(scene, settings) { scene.timeline(settings) }
    val visibleEnd = remember(scene, timeline, includeRuby, replay) { mutableIntStateOf(0) }
    LaunchedEffect(scene, timeline, includeRuby, replay) {
        val controller = DialoguePlaybackController()
        var time = withFrameNanos { it }
        controller.start(timeline, time)
        try {
            while (true) {
                visibleEnd.intValue = controller.visibleEnd(time)
                if (!controller.isRevealing(time)) break
                time = withFrameNanos { it }
            }
        } finally { controller.cancel() }
    }
    val description = example.renderText(includeRuby) +
        if (bilingual) "\n${example.originalText}" else ""
    Surface(color = Color(0xFF1B304E), shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth()
            .aspectRatio(scene.crop.width().toFloat() / scene.crop.height())
            .semantics { contentDescription = description }) {
            clipRect {
                val canvas = drawContext.canvas.nativeCanvas
                canvas.save()
                try {
                    val scale = size.width / scene.crop.width()
                    canvas.scale(scale, scale)
                    scene.draw(canvas, includeRuby, visibleEnd.intValue)
                } finally { canvas.restore() }
            }
        }
    }
}
