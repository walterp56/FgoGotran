package com.fgogotran.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.fgogotran.util.FgoLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.operation.buffer.BufferOp
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class PaddleOcrProvider(context: Context) : OcrProvider {
    private val runtime = PaddleOcrRuntime(context.applicationContext)

    override suspend fun warmUp() {
        withContext(Dispatchers.Default) {
            runtime.initialize()
        }
    }

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        return withContext(Dispatchers.Default) {
            runtime.recognize(bitmap)
        }
    }

    override fun close() {
        runtime.close()
    }
}

private class PaddleOcrRuntime(
    private val context: Context
) {
    private val tag = "OCR"
    private val lock = Any()

    private var environment: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var dictionary: List<String> = emptyList()
    @Volatile
    private var initialized = false

    fun initialize() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val startedAt = System.currentTimeMillis()
            try {
                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(4)
                }
                try {
                    environment = env
                    detSession = env.createSession(readAsset(DET_MODEL_ASSET), options)
                    recSession = env.createSession(readAsset(REC_MODEL_ASSET), options)
                } finally {
                    options.close()
                }
                dictionary = loadDictionary()
                initialized = true
                FgoLogger.info(
                    tag,
                    "PaddleOCR initialized: dict=${dictionary.size}, " +
                        "elapsed=${System.currentTimeMillis() - startedAt}ms"
                )
            } catch (e: Exception) {
                close()
                FgoLogger.warn(tag, "PaddleOCR initialization failed", e)
                throw e
            }
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { detSession?.close() }
            runCatching { recSession?.close() }
            detSession = null
            recSession = null
            environment = null
            dictionary = emptyList()
            initialized = false
        }
    }

    fun recognize(bitmap: Bitmap): OcrResult {
        initialize()
        require(!bitmap.isRecycled) { "Bitmap has been recycled" }

        val startedAt = System.currentTimeMillis()
        FgoLogger.debug(tag, "PaddleOCR starting on ${bitmap.width}x${bitmap.height}")

        val boxes = detectTextBoxes(bitmap)
            .sortedWith(compareBy({ boxMinY(it) }, { boxMinX(it) }))

        val lines = mutableListOf<OcrTextLine>()
        for (box in boxes) {
            val crop = cropTextLine(bitmap, box) ?: continue
            try {
                val (text, confidence) = recognizeCrop(crop)
                if (text.isNotBlank() && confidence >= REC_TEXT_SCORE_THRESHOLD) {
                    lines.add(
                        OcrTextLine(
                            text = text,
                            boundingBox = boxToRect(box, bitmap.width, bitmap.height),
                            confidence = confidence.coerceIn(0f, 1f)
                        )
                    )
                }
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
        }

        val fullText = lines.joinToString("\n") { it.text }
        val elapsed = System.currentTimeMillis() - startedAt
        if (lines.isEmpty()) {
            FgoLogger.warn(tag, "PaddleOCR returned 0 text lines after ${elapsed}ms")
        } else {
            FgoLogger.info(
                tag,
                "PaddleOCR complete: ${lines.size} lines, ${fullText.length} chars, ${elapsed}ms"
            )
        }
        return OcrResult(
            lines = lines,
            fullText = fullText,
            engine = OcrEngineId.PADDLE_OCR
        )
    }

    private fun readAsset(path: String): ByteArray {
        return context.assets.open(path).use { it.readBytes() }
    }

    private fun loadDictionary(): List<String> {
        val rows = context.assets.open(DICT_ASSET)
            .bufferedReader(Charsets.UTF_8)
            .readLines()
        return buildList(rows.size + 1) {
            add("")
            addAll(rows)
        }
    }

    private fun detectTextBoxes(bitmap: Bitmap): List<FloatArray> {
        val width = bitmap.width
        val height = bitmap.height
        val scale = if (max(width, height) > DET_LIMIT_SIDE_LEN) {
            DET_LIMIT_SIDE_LEN.toFloat() / max(width, height).toFloat()
        } else {
            1f
        }
        val resizedWidth = alignTo32((width * scale).roundToInt())
        val resizedHeight = alignTo32((height * scale).roundToInt())
        val resized = Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)
        val pixels = IntArray(resizedWidth * resizedHeight)
        resized.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, resizedHeight)
        if (resized !== bitmap) resized.recycle()

        val pixelCount = resizedWidth * resizedHeight
        val input = FloatArray(pixelCount * 3)
        for (index in 0 until pixelCount) {
            val pixel = pixels[index]
            val blue = (pixel and 0xff) / 255f
            val green = ((pixel shr 8) and 0xff) / 255f
            val red = ((pixel shr 16) and 0xff) / 255f
            input[index] = (blue - DET_MEAN[0]) / DET_STD[0]
            input[pixelCount + index] = (green - DET_MEAN[1]) / DET_STD[1]
            input[pixelCount * 2 + index] = (red - DET_MEAN[2]) / DET_STD[2]
        }

        val env = environment ?: error("PaddleOCR environment is not initialized")
        val session = detSession ?: error("PaddleOCR detection session is not initialized")
        val inputName = session.inputNames.first()
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, resizedHeight.toLong(), resizedWidth.toLong())
        )
        val (probabilities, predHeight, predWidth) = tensor.use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { result ->
                val output = firstTensor(result, session)
                val shape = output.info.shape
                val outputHeight = shape[shape.size - 2].toInt()
                val outputWidth = shape[shape.size - 1].toInt()
                val values = FloatArray(outputHeight * outputWidth)
                output.floatBuffer.apply {
                    rewind()
                    get(values, 0, values.size)
                }
                Triple(values, outputHeight, outputWidth)
            }
        }

        return postprocessDetection(probabilities, predWidth, predHeight, width, height)
    }

    private fun postprocessDetection(
        probabilities: FloatArray,
        predWidth: Int,
        predHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): List<FloatArray> {
        val active = BooleanArray(predWidth * predHeight) { probabilities[it] > DET_THRESHOLD }
        val visited = BooleanArray(active.size)
        val components = mutableListOf<MutableList<Coordinate>>()
        val queue = ArrayDeque<Int>()
        val neighborX = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val neighborY = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)

        for (start in active.indices) {
            if (!active[start] || visited[start]) continue
            val component = mutableListOf<Coordinate>()
            visited[start] = true
            queue.addLast(start)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val x = current % predWidth
                val y = current / predWidth
                component.add(Coordinate(x.toDouble(), y.toDouble()))
                for (offset in neighborX.indices) {
                    val nextX = x + neighborX[offset]
                    val nextY = y + neighborY[offset]
                    if (nextX !in 0 until predWidth || nextY !in 0 until predHeight) continue
                    val nextIndex = nextY * predWidth + nextX
                    if (active[nextIndex] && !visited[nextIndex]) {
                        visited[nextIndex] = true
                        queue.addLast(nextIndex)
                    }
                }
            }
            if (component.size >= DET_MIN_COMPONENT_PIXELS) {
                components.add(component)
            }
        }

        val candidates = components
            .sortedByDescending { it.size }
            .take(DET_MAX_CANDIDATES)
        val sourceScaleX = sourceWidth.toFloat() / predWidth.toFloat()
        val sourceScaleY = sourceHeight.toFloat() / predHeight.toFloat()
        val boxes = mutableListOf<FloatArray>()

        for (component in candidates) {
            val firstBox = minimumAreaBox(component) ?: continue
            if (firstBox.minSide < DET_MIN_BOX_SIDE) continue
            val score = boxMeanScore(probabilities, predWidth, predHeight, firstBox.points)
            if (score < DET_BOX_SCORE_THRESHOLD) continue
            val expandedPolygon = expandPolygon(firstBox.points, DET_UNCLIP_RATIO)
                ?: firstBox.points.toList()
            val expandedBox = minimumAreaBox(expandedPolygon) ?: firstBox
            if (expandedBox.minSide < DET_MIN_BOX_SIDE) continue
            boxes.add(mapBoxToSource(expandedBox.points, sourceScaleX, sourceScaleY, sourceWidth, sourceHeight))
        }

        FgoLogger.debug(tag, "PaddleOCR detection: components=${components.size}, boxes=${boxes.size}")
        return boxes
    }

    private fun minimumAreaBox(points: List<Coordinate>): MiniBox? {
        val hull = convexHull(points)
        if (hull.size < 3) return null

        var bestArea = Double.MAX_VALUE
        var bestBox: MiniBox? = null
        for (index in hull.indices) {
            val next = (index + 1) % hull.size
            val edgeX = hull[next].x - hull[index].x
            val edgeY = hull[next].y - hull[index].y
            val edgeLength = sqrt(edgeX * edgeX + edgeY * edgeY)
            if (edgeLength < 1e-6) continue

            val ux = edgeX / edgeLength
            val uy = edgeY / edgeLength
            val vx = -uy
            val vy = ux
            var minU = Double.MAX_VALUE
            var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE
            var maxV = -Double.MAX_VALUE

            for (point in hull) {
                val localX = point.x - hull[index].x
                val localY = point.y - hull[index].y
                val projectedU = localX * ux + localY * uy
                val projectedV = localX * vx + localY * vy
                minU = min(minU, projectedU)
                maxU = max(maxU, projectedU)
                minV = min(minV, projectedV)
                maxV = max(maxV, projectedV)
            }

            val boxWidth = maxU - minU
            val boxHeight = maxV - minV
            val area = boxWidth * boxHeight
            if (area >= bestArea) continue

            val centerU = (minU + maxU) / 2.0
            val centerV = (minV + maxV) / 2.0
            val centerX = hull[index].x + centerU * ux + centerV * vx
            val centerY = hull[index].y + centerU * uy + centerV * vy
            val halfWidth = boxWidth / 2.0
            val halfHeight = boxHeight / 2.0
            val corners = arrayOf(
                Coordinate(centerX - halfWidth * ux - halfHeight * vx, centerY - halfWidth * uy - halfHeight * vy),
                Coordinate(centerX + halfWidth * ux - halfHeight * vx, centerY + halfWidth * uy - halfHeight * vy),
                Coordinate(centerX + halfWidth * ux + halfHeight * vx, centerY + halfWidth * uy + halfHeight * vy),
                Coordinate(centerX - halfWidth * ux + halfHeight * vx, centerY - halfWidth * uy + halfHeight * vy)
            )
            bestArea = area
            bestBox = MiniBox(orderQuad(corners), min(boxWidth, boxHeight).toFloat())
        }
        return bestBox
    }

    private fun convexHull(points: List<Coordinate>): List<Coordinate> {
        if (points.size <= 3) return points.distinctBy { it.x to it.y }
        val sorted = points
            .distinctBy { it.x to it.y }
            .sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size <= 3) return sorted

        fun cross(origin: Coordinate, a: Coordinate, b: Coordinate): Double {
            return (a.x - origin.x) * (b.y - origin.y) - (a.y - origin.y) * (b.x - origin.x)
        }

        val lower = mutableListOf<Coordinate>()
        for (point in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), point) <= 0.0) {
                lower.removeAt(lower.lastIndex)
            }
            lower.add(point)
        }

        val upper = mutableListOf<Coordinate>()
        for (point in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), point) <= 0.0) {
                upper.removeAt(upper.lastIndex)
            }
            upper.add(point)
        }

        lower.removeAt(lower.lastIndex)
        upper.removeAt(upper.lastIndex)
        return lower + upper
    }

    private fun orderQuad(points: Array<Coordinate>): Array<Coordinate> {
        val topLeft = points.minBy { it.x + it.y }
        val bottomRight = points.maxBy { it.x + it.y }
        val topRight = points.minBy { it.y - it.x }
        val bottomLeft = points.maxBy { it.y - it.x }
        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun boxMeanScore(
        probabilities: FloatArray,
        width: Int,
        height: Int,
        box: Array<Coordinate>
    ): Float {
        val minX = box.minOf { it.x.toInt() }.coerceIn(0, width - 1)
        val maxX = box.maxOf { it.x.toInt() }.coerceIn(0, width - 1)
        val minY = box.minOf { it.y.toInt() }.coerceIn(0, height - 1)
        val maxY = box.maxOf { it.y.toInt() }.coerceIn(0, height - 1)
        if (minX > maxX || minY > maxY) return 0f

        var total = 0f
        var count = 0
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (pointInPolygon(x.toDouble(), y.toDouble(), box)) {
                    total += probabilities[y * width + x]
                    count += 1
                }
            }
        }
        return if (count == 0) 0f else total / count.toFloat()
    }

    private fun pointInPolygon(x: Double, y: Double, polygon: Array<Coordinate>): Boolean {
        var inside = false
        var previous = polygon.lastIndex
        for (current in polygon.indices) {
            val yi = polygon[current].y
            val yj = polygon[previous].y
            val xi = polygon[current].x
            val xj = polygon[previous].x
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun expandPolygon(points: Array<Coordinate>, ratio: Double): List<Coordinate>? {
        val area = polygonArea(points)
        val perimeter = polygonPerimeter(points)
        if (area <= 0.0 || perimeter <= 1e-6) return null
        val distance = area * ratio / perimeter
        val ring = Array(points.size + 1) { index ->
            if (index < points.size) points[index] else points.first()
        }
        return runCatching {
            val geometry = GEOMETRY_FACTORY.createPolygon(ring)
            val buffered = BufferOp.bufferOp(geometry, distance)
            val coordinates = largestGeometry(buffered)?.coordinates ?: return@runCatching null
            val withoutClosingPoint = if (
                coordinates.size > 1 &&
                coordinates.first().equals2D(coordinates.last())
            ) {
                coordinates.dropLast(1)
            } else {
                coordinates.toList()
            }
            withoutClosingPoint.takeIf { it.size >= 3 }
        }.getOrNull()
    }

    private fun largestGeometry(geometry: Geometry?): Geometry? {
        if (geometry == null || geometry.isEmpty) return null
        var best = geometry.getGeometryN(0)
        for (index in 1 until geometry.numGeometries) {
            val candidate = geometry.getGeometryN(index)
            if (candidate.area > best.area) best = candidate
        }
        return best
    }

    private fun polygonArea(points: Array<Coordinate>): Double {
        var area = 0.0
        for (index in points.indices) {
            val next = (index + 1) % points.size
            area += points[index].x * points[next].y - points[next].x * points[index].y
        }
        return abs(area) / 2.0
    }

    private fun polygonPerimeter(points: Array<Coordinate>): Double {
        var perimeter = 0.0
        for (index in points.indices) {
            val next = (index + 1) % points.size
            val dx = points[next].x - points[index].x
            val dy = points[next].y - points[index].y
            perimeter += sqrt(dx * dx + dy * dy)
        }
        return perimeter
    }

    private fun mapBoxToSource(
        points: Array<Coordinate>,
        scaleX: Float,
        scaleY: Float,
        sourceWidth: Int,
        sourceHeight: Int
    ): FloatArray {
        val ordered = orderQuad(points)
        val mapped = FloatArray(8)
        for (index in ordered.indices) {
            mapped[index * 2] = (ordered[index].x.toFloat() * scaleX)
                .coerceIn(0f, (sourceWidth - 1).toFloat())
            mapped[index * 2 + 1] = (ordered[index].y.toFloat() * scaleY)
                .coerceIn(0f, (sourceHeight - 1).toFloat())
        }
        return mapped
    }

    private fun cropTextLine(source: Bitmap, box: FloatArray): Bitmap? {
        val cropWidth = max(
            distance(box[0], box[1], box[2], box[3]),
            distance(box[6], box[7], box[4], box[5])
        ).roundToInt().coerceIn(1, source.width)
        val cropHeight = max(
            distance(box[0], box[1], box[6], box[7]),
            distance(box[2], box[3], box[4], box[5])
        ).roundToInt().coerceIn(1, source.height)

        val sourcePoints = floatArrayOf(
            box[0], box[1],
            box[2], box[3],
            box[4], box[5],
            box[6], box[7]
        )
        val destinationPoints = floatArrayOf(
            0f, 0f,
            (cropWidth - 1).toFloat(), 0f,
            (cropWidth - 1).toFloat(), (cropHeight - 1).toFloat(),
            0f, (cropHeight - 1).toFloat()
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(sourcePoints, 0, destinationPoints, 0, 4)) {
            return null
        }

        return runCatching {
            val crop = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
            Canvas(crop).drawBitmap(source, matrix, null)
            if (crop.height >= crop.width * VERTICAL_TEXT_ROTATE_RATIO) {
                val rotated = Bitmap.createBitmap(
                    crop,
                    0,
                    0,
                    crop.width,
                    crop.height,
                    Matrix().apply { setRotate(-90f) },
                    true
                )
                if (rotated !== crop) crop.recycle()
                rotated
            } else {
                crop
            }
        }.getOrNull()
    }

    private fun recognizeCrop(crop: Bitmap): Pair<String, Float> {
        val resizedWidth = max(1, ceil(REC_IMAGE_HEIGHT.toDouble() * crop.width / crop.height).toInt())
            .coerceAtMost(REC_MAX_IMAGE_WIDTH)
        val resized = Bitmap.createScaledBitmap(crop, resizedWidth, REC_IMAGE_HEIGHT, true)
        val pixels = IntArray(resizedWidth * REC_IMAGE_HEIGHT)
        resized.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, REC_IMAGE_HEIGHT)
        if (resized !== crop) resized.recycle()

        val pixelCount = resizedWidth * REC_IMAGE_HEIGHT
        val input = FloatArray(pixelCount * 3)
        for (index in 0 until pixelCount) {
            val pixel = pixels[index]
            input[index] = ((pixel and 0xff) / 255f - 0.5f) / 0.5f
            input[pixelCount + index] = (((pixel shr 8) and 0xff) / 255f - 0.5f) / 0.5f
            input[pixelCount * 2 + index] = (((pixel shr 16) and 0xff) / 255f - 0.5f) / 0.5f
        }

        val env = environment ?: error("PaddleOCR environment is not initialized")
        val session = recSession ?: error("PaddleOCR recognition session is not initialized")
        val inputName = session.inputNames.first()
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, REC_IMAGE_HEIGHT.toLong(), resizedWidth.toLong())
        )
        return tensor.use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { result ->
                val output = firstTensor(result, session)
                val shape = output.info.shape
                val sequenceLength = shape[shape.size - 2].toInt()
                val classCount = shape[shape.size - 1].toInt()
                val values = FloatArray(sequenceLength * classCount)
                output.floatBuffer.apply {
                    rewind()
                    get(values, 0, values.size)
                }
                ctcDecode(values, sequenceLength, classCount)
            }
        }
    }

    private fun ctcDecode(values: FloatArray, sequenceLength: Int, classCount: Int): Pair<String, Float> {
        val text = StringBuilder()
        var previousIndex = -1
        var confidenceSum = 0f
        var confidenceCount = 0

        for (step in 0 until sequenceLength) {
            val offset = step * classCount
            var bestIndex = 0
            var bestValue = values[offset]
            for (classIndex in 1 until classCount) {
                val value = values[offset + classIndex]
                if (value > bestValue) {
                    bestValue = value
                    bestIndex = classIndex
                }
            }
            if (bestIndex != 0 && bestIndex != previousIndex && bestIndex < dictionary.size) {
                text.append(dictionary[bestIndex])
                confidenceSum += bestValue
                confidenceCount += 1
            }
            previousIndex = bestIndex
        }

        val decoded = text.toString().trim()
        if (decoded.isBlank()) return "" to 0f
        val confidence = if (confidenceCount == 0) 0f else confidenceSum / confidenceCount
        return decoded to confidence
    }

    private fun firstTensor(result: OrtSession.Result, session: OrtSession): OnnxTensor {
        for (name in session.outputNames) {
            val value = result.get(name)
            if (value.isPresent && value.get() is OnnxTensor) {
                return value.get() as OnnxTensor
            }
        }
        error("ONNX session returned no tensor output")
    }

    private fun alignTo32(value: Int): Int {
        return max(32, ((value + 31) / 32) * 32)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private fun boxMinX(box: FloatArray): Float = minOf(box[0], box[2], box[4], box[6])
    private fun boxMinY(box: FloatArray): Float = minOf(box[1], box[3], box[5], box[7])

    private fun boxToRect(box: FloatArray, width: Int, height: Int): Rect {
        val left = boxMinX(box).roundToInt().coerceIn(0, width - 1)
        val top = boxMinY(box).roundToInt().coerceIn(0, height - 1)
        val right = maxOf(box[0], box[2], box[4], box[6]).roundToInt().coerceIn(left + 1, width)
        val bottom = maxOf(box[1], box[3], box[5], box[7]).roundToInt().coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    private data class MiniBox(
        val points: Array<Coordinate>,
        val minSide: Float
    )

    companion object {
        private const val DET_MODEL_ASSET = "ppocrv6/det_v6_small.onnx"
        private const val REC_MODEL_ASSET = "ppocrv6/rec_v6_small.onnx"
        private const val DICT_ASSET = "ppocrv6/ppocrv6_dict.txt"

        private const val DET_LIMIT_SIDE_LEN = 960
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private const val DET_THRESHOLD = 0.2f
        private const val DET_BOX_SCORE_THRESHOLD = 0.45f
        private const val DET_UNCLIP_RATIO = 1.4
        private const val DET_MIN_COMPONENT_PIXELS = 3
        private const val DET_MIN_BOX_SIDE = 3f
        private const val DET_MAX_CANDIDATES = 1000

        private const val REC_IMAGE_HEIGHT = 48
        private const val REC_MAX_IMAGE_WIDTH = 3200
        private const val REC_TEXT_SCORE_THRESHOLD = 0.5f
        private const val VERTICAL_TEXT_ROTATE_RATIO = 1.5f
        private val GEOMETRY_FACTORY = GeometryFactory()
    }
}
