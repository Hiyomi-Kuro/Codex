package com.kaori.codex.bridge

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** Captures one in-memory accessibility frame and returns OCR metadata without the frame. */
object CodexOcr {
    /** Performs bounded OCR for a normalized display region. */
    fun read(service: CodexAccessibilityService, args: Map<String, String>): JSONObject {
        var captured: CapturedScreenFrame? = null
        var analysis: OcrAnalysisFrame? = null
        var recognizer: TextRecognizer? = null
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return failure("Screen capture requires Android 11 or newer")
            }
            val region = CodexOcrGeometry.region(args)
            captured = CodexScreenCapture.capture(service)
            val frameId = CodexFrameRegistry.register(captured.geometry)
            analysis = prepare(captured.bitmap, region, args)
            recognizer = recognizer(args["language"])
            val text = recognize(recognizer, analysis.bitmap)
            result(text, analysis, region, args, captured.geometry, frameId)
        } catch (exception: Exception) {
            failure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            recognizer?.close()
            analysis?.bitmap?.let { bitmap ->
                if (bitmap !== captured?.bitmap) {
                    bitmap.recycle()
                }
            }
            captured?.bitmap?.recycle()
        }
    }

    private fun prepare(
        source: Bitmap,
        region: NormalizedRegion,
        args: Map<String, String>,
    ): OcrAnalysisFrame {
        val sourceRect = CodexOcrGeometry.pixelRect(region, source.width, source.height)
        val cropped = Bitmap.createBitmap(
            source,
            sourceRect.left,
            sourceRect.top,
            sourceRect.width(),
            sourceRect.height(),
        )
        val requestedMax = args["maxDimension"]?.toIntOrNull() ?: DEFAULT_MAX_DIMENSION
        require(requestedMax in MIN_MAX_DIMENSION..MAX_MAX_DIMENSION) {
            "maxDimension must be between $MIN_MAX_DIMENSION and $MAX_MAX_DIMENSION"
        }
        val largest = maxOf(cropped.width, cropped.height)
        if (largest <= requestedMax) {
            return OcrAnalysisFrame(cropped, sourceRect, 1f)
        }
        val scale = requestedMax.toFloat() / largest
        val scaled = Bitmap.createScaledBitmap(
            cropped,
            (cropped.width * scale).roundToInt().coerceAtLeast(1),
            (cropped.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        cropped.recycle()
        return OcrAnalysisFrame(scaled, sourceRect, scale)
    }

    private fun recognizer(language: String?): TextRecognizer {
        return when (language?.lowercase()) {
            null, "", "zh", "zh-cn", "chinese" ->
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "en", "en-us", "english", "latin" ->
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            "ja", "ja-jp", "japanese" ->
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            else -> throw IllegalArgumentException("Unsupported OCR language; use zh, en, or ja")
        }
    }

    private fun recognize(recognizer: TextRecognizer, bitmap: Bitmap): Text {
        val completed = CountDownLatch(1)
        var result: Text? = null
        var failure: Exception? = null
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener {
                result = it
                completed.countDown()
            }
            .addOnFailureListener {
                failure = it
                completed.countDown()
            }
        if (!completed.await(OCR_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw TimeoutException("OCR timed out")
        }
        failure?.let { throw it }
        return result ?: throw IllegalStateException("OCR returned no result")
    }

    private fun result(
        text: Text,
        analysis: OcrAnalysisFrame,
        region: NormalizedRegion,
        args: Map<String, String>,
        geometry: DisplayGeometry,
        frameId: String,
    ): JSONObject {
        val regions = JSONArray()
        val recognized = StringBuilder()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val bounds = line.boundingBox ?: continue
                val lineText = line.text.trim()
                if (lineText.isEmpty()) {
                    continue
                }
                if (recognized.isNotEmpty()) {
                    recognized.append('\n')
                }
                recognized.append(lineText)
                regions.put(JSONObject()
                    .put("text", lineText)
                    .put("confidence", line.confidence.toDouble())
                    .put("bounds", CodexOcrGeometry.normalizedBounds(
                        bounds,
                        analysis.sourceRect,
                        analysis.scale,
                        geometry.width,
                        geometry.height,
                    )))
            }
        }
        val allText = recognized.toString()
        val expected = (args["expectedText"] ?: args["expectText"])
            ?.takeIf { it.isNotBlank() }
        val absent = (args["expectedNotText"] ?: args["expectNotText"])
            ?.takeIf { it.isNotBlank() }
        val expectedFound = expected?.let { allText.contains(it, ignoreCase = true) }
        val absentFound = absent?.let { allText.contains(it, ignoreCase = true) }
        val verified = if (expected == null && absent == null) {
            allText.isNotEmpty()
        } else {
            (expectedFound != false) && (absentFound != true)
        }
        return JSONObject()
            .put("ok", true)
            .put("action", "ocr")
            .put("executed", true)
            .put("verified", verified)
            .put("text", allText)
            .put("regions", regions)
            .put("expectedText", expected ?: JSONObject.NULL)
            .put("expectedTextFound", expectedFound ?: JSONObject.NULL)
            .put("expectedNotText", absent ?: JSONObject.NULL)
            .put("expectedNotTextFound", absentFound ?: JSONObject.NULL)
            .put("textDetected", allText.isNotEmpty())
            .put("coordinateSpace", "display")
            .put("frameId", frameId)
            .put("frameWidth", geometry.width)
            .put("frameHeight", geometry.height)
            .put("frameRotation", geometry.rotation)
            .put("frameWindowLeft", geometry.windowLeft)
            .put("frameWindowTop", geometry.windowTop)
            .put("frameWindowRight", geometry.windowRight)
            .put("frameWindowBottom", geometry.windowBottom)
            .put("framePackage", geometry.packageName ?: JSONObject.NULL)
            .put("frame", JSONObject()
                .put("id", frameId)
                .put("width", geometry.width)
                .put("height", geometry.height)
                .put("rotation", geometry.rotation)
                .put("coordinateSpace", "display")
                .put("windowInsets", JSONObject()
                    .put("left", geometry.windowLeft)
                    .put("top", geometry.windowTop)
                    .put("right", geometry.width - geometry.windowRight)
                    .put("bottom", geometry.height - geometry.windowBottom))
                .put("package", geometry.packageName ?: JSONObject.NULL))
            .put("requestedRegion", JSONObject()
                .put("left", region.left)
                .put("top", region.top)
                .put("right", region.right)
                .put("bottom", region.bottom))
            .put("analysis", JSONObject()
                .put("width", analysis.bitmap.width)
                .put("height", analysis.bitmap.height)
                .put("scale", analysis.scale)
                .put("cropped", true))
            .put("framePersisted", false)
            .put("frameReleased", true)
            .put("visualEvidenceAvailable", true)
    }

    private fun failure(message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("action", "ocr")
        .put("executed", false)
        .put("verified", false)
        .put("error", message)
        .put("framePersisted", false)
        .put("frameReleased", true)
        .put("visualEvidenceAvailable", false)

    private data class OcrAnalysisFrame(
        val bitmap: Bitmap,
        val sourceRect: Rect,
        val scale: Float,
    )

    private const val DEFAULT_MAX_DIMENSION = 1600
    private const val MIN_MAX_DIMENSION = 320
    private const val MAX_MAX_DIMENSION = 2048
    private const val OCR_TIMEOUT_MS = 5000L
}

