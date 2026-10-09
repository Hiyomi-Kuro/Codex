package com.kaori.codex.bridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import org.json.JSONObject

/** Performs bounded offline template matching against an in-memory display frame. */
internal object CodexTemplateMatcher {
    /** Matches an app-private or inline template and returns its normalized display location. */
    fun match(service: CodexAccessibilityService, args: Map<String, String>): JSONObject {
        var frame: CapturedScreenFrame? = null
        var template: Bitmap? = null
        var scaledTemplate: Bitmap? = null
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return failure("Screen capture requires Android 11 or newer")
            }
            val region = CodexOcrGeometry.region(args)
            frame = CodexScreenCapture.capture(service)
            val frameId = CodexFrameRegistry.register(frame.geometry)
            template = loadTemplate(service, args)
            val scale = templateScale(args)
            val analysisTemplate = if (scale == 1f) {
                template
            } else {
                Bitmap.createScaledBitmap(
                    template,
                    (template.width * scale).toInt().coerceAtLeast(1),
                    (template.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also { scaledTemplate = it }
            }
            val threshold = threshold(args)
            val best = findBest(frame.bitmap, analysisTemplate, region)
            result(
                frame = frame,
                frameId = frameId,
                templatePath = args["templatePath"],
                templateScale = scale,
                best = best,
                threshold = threshold,
            )
        } catch (exception: Exception) {
            failure(exception.message ?: exception.javaClass.simpleName)
        } finally {
            scaledTemplate?.takeIf { it !== template }?.recycle()
            template?.recycle()
            frame?.bitmap?.recycle()
        }
    }

    /** Matches a template, taps its center, and returns both recognition and action evidence. */
    fun tap(service: CodexAccessibilityService, args: Map<String, String>): JSONObject {
        val recognition = match(service, args)
        if (!recognition.optBoolean("ok") || !recognition.optBoolean("matched")) {
            return recognition
                .put("action", "template_tap")
                .put("executed", false)
                .put("verified", false)
        }
        val tapArgs = args.toMutableMap().apply {
            put("x", recognition.optDouble("x").toString())
            put("y", recognition.optDouble("y").toString())
            put("coordinateSpace", "display")
            put("frameId", recognition.optString("frameId"))
        }
        val tap = service.tap(tapArgs)
        return JSONObject()
            .put("ok", tap.optBoolean("ok"))
            .put("action", "template_tap")
            .put("executed", tap.optBoolean("executed", tap.optBoolean("ok")))
            .put("verified", tap.optBoolean("verified"))
            .put("matched", true)
            .put("confidence", recognition.optDouble("confidence"))
            .put("recognition", recognition)
            .put("tap", tap)
    }

    private fun loadTemplate(
        service: CodexAccessibilityService,
        args: Map<String, String>,
    ): Bitmap {
        val bytes = when {
            !args["templatePath"].isNullOrBlank() ->
                CodexFileStore.readForAnalysis(service, args["templatePath"]!!)
            !args["template64"].isNullOrBlank() -> {
                require(args["template64"]!!.length <= MAX_INLINE_BASE64_CHARS) {
                    "Inline template is too large; use templatePath"
                }
                Base64.decode(args["template64"], Base64.DEFAULT)
            }
            else -> throw IllegalArgumentException("Missing templatePath or template64")
        }
        require(bytes.size <= MAX_TEMPLATE_BYTES) {
            "Template exceeds $MAX_TEMPLATE_BYTES bytes"
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("Template is not a supported bitmap")
    }

    private fun findBest(
        source: Bitmap,
        template: Bitmap,
        region: NormalizedRegion,
    ): BestMatch {
        val sourceRect = CodexOcrGeometry.pixelRect(region, source.width, source.height)
        require(template.width <= sourceRect.width() && template.height <= sourceRect.height()) {
            "Template is larger than the requested match region"
        }
        val sourceGray = grayscale(source)
        val templateGray = grayscale(template)
        val searchWidth = sourceRect.width() - template.width + 1
        val searchHeight = sourceRect.height() - template.height + 1
        val comparisons = searchWidth.toDouble() * searchHeight *
            template.width.toDouble() * template.height
        val sampleStep = ceil(
            (comparisons / MAX_COMPARISONS).coerceAtLeast(1.0).pow(0.25),
        ).toInt().coerceIn(1, MAX_SAMPLE_STEP)
        val positionStep = maxOf(1, sampleStep / 2)
        var bestScore = Double.NEGATIVE_INFINITY
        var bestLeft = sourceRect.left
        var bestTop = sourceRect.top
        var y = sourceRect.top
        while (y <= sourceRect.bottom - template.height) {
            var x = sourceRect.left
            while (x <= sourceRect.right - template.width) {
                var difference = 0L
                var samples = 0
                var templateY = 0
                while (templateY < template.height) {
                    val sourceRow = (y + templateY) * source.width
                    val templateRow = templateY * template.width
                    var templateX = 0
                    while (templateX < template.width) {
                        val sourceValue = sourceGray[sourceRow + x + templateX].toInt() and 0xff
                        val templateValue = templateGray[templateRow + templateX].toInt() and 0xff
                        difference += abs(sourceValue - templateValue)
                        samples++
                        templateX += sampleStep
                    }
                    templateY += sampleStep
                }
                val score = 1.0 - difference.toDouble() / (samples * 255.0)
                if (score > bestScore) {
                    bestScore = score
                    bestLeft = x
                    bestTop = y
                }
                x += positionStep
            }
            y += positionStep
        }
        return BestMatch(
            left = bestLeft,
            top = bestTop,
            width = template.width,
            height = template.height,
            confidence = bestScore.coerceIn(0.0, 1.0),
            sampleStep = sampleStep,
        )
    }

    private fun result(
        frame: CapturedScreenFrame,
        frameId: String,
        templatePath: String?,
        templateScale: Float,
        best: BestMatch,
        threshold: Double,
    ): JSONObject {
        val bounds = Rect(
            best.left,
            best.top,
            best.left + best.width,
            best.top + best.height,
        )
        val normalizedBounds = CodexOcrGeometry.normalizedBounds(
            bounds,
            frame.bitmap.width,
            frame.bitmap.height,
        )
        val centerX = (best.left + best.width / 2f) / frame.bitmap.width
        val centerY = (best.top + best.height / 2f) / frame.bitmap.height
        val matched = best.confidence >= threshold
        return JSONObject()
            .put("ok", true)
            .put("action", "template_match")
            .put("executed", true)
            .put("verified", matched)
            .put("matched", matched)
            .put("confidence", best.confidence)
            .put("threshold", threshold)
            .put("sampleStep", best.sampleStep)
            .put("coordinateSpace", "display")
            .put("x", centerX)
            .put("y", centerY)
            .put("bounds", normalizedBounds)
            .put("frameId", frameId)
            .put("frameWidth", frame.geometry.width)
            .put("frameHeight", frame.geometry.height)
            .put("frameRotation", frame.geometry.rotation)
            .put("framePackage", frame.geometry.packageName ?: JSONObject.NULL)
            .put("templatePath", templatePath ?: JSONObject.NULL)
            .put("templateScale", templateScale)
            .put("framePersisted", false)
            .put("frameReleased", true)
            .put("visualEvidenceAvailable", true)
    }

    private fun grayscale(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val gray = ByteArray(pixels.size)
        pixels.forEachIndexed { index, color ->
            val red = color shr 16 and 0xff
            val green = color shr 8 and 0xff
            val blue = color and 0xff
            gray[index] = ((red * 299 + green * 587 + blue * 114) / 1000).toByte()
        }
        return gray
    }

    private fun templateScale(args: Map<String, String>): Float {
        val value = args["templateScale"]?.toFloatOrNull() ?: 1f
        require(value.isFinite() && value in MIN_TEMPLATE_SCALE..MAX_TEMPLATE_SCALE) {
            "templateScale must be between $MIN_TEMPLATE_SCALE and $MAX_TEMPLATE_SCALE"
        }
        return value
    }

    private fun threshold(args: Map<String, String>): Double {
        val value = args["threshold"]?.toDoubleOrNull() ?: DEFAULT_THRESHOLD
        require(value.isFinite() && value in 0.0..1.0) {
            "threshold must be between 0.0 and 1.0"
        }
        return value
    }

    private fun failure(message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("action", "template_match")
        .put("executed", false)
        .put("verified", false)
        .put("matched", false)
        .put("error", message)
        .put("framePersisted", false)
        .put("frameReleased", true)
        .put("visualEvidenceAvailable", false)

    private data class BestMatch(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val confidence: Double,
        val sampleStep: Int,
    )

    private const val MAX_TEMPLATE_BYTES = 512 * 1024
    private const val MAX_INLINE_BASE64_CHARS = 700 * 1024
    private const val MAX_COMPARISONS = 2_000_000.0
    private const val MAX_SAMPLE_STEP = 32
    private const val MIN_TEMPLATE_SCALE = 0.25f
    private const val MAX_TEMPLATE_SCALE = 4f
    private const val DEFAULT_THRESHOLD = 0.82
}
