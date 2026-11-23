package com.warung.autobag.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.roundToInt

/**
 * Satu elemen teks hasil OCR.
 * Koordinat selalu dikembalikan dalam ukuran bitmap sumber.
 */
data class RecognizedElement(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/**
 * Satu baris teks hasil OCR.
 * Koordinat selalu dikembalikan dalam ukuran bitmap sumber.
 */
data class RecognizedLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val elements: List<RecognizedElement> = emptyList()
)

/**
 * Hasil keseluruhan proses OCR.
 */
data class OcrResult(
    val fullText: String,
    val lines: List<RecognizedLine>
)

/**
 * Mesin OCR lokal menggunakan Google ML Kit.
 *
 * Untuk screenshot sangat lebar/tinggi, input OCR dikecilkan secara proporsional
 * sampai dimensi terpanjang 1920 px. Semua bounding box dikembalikan ke
 * koordinat bitmap asli sehingga posisi tap dan deteksi rak tidak bergeser.
 */
class OrderTextRecognizer {

    companion object {
        private const val MAX_OCR_DIMENSION = 1920
    }

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    fun recognize(
        bitmap: Bitmap,
        roi: Rect? = null,
        onSuccess: (OcrResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (bitmap.isRecycled) {
            onFailure(IllegalArgumentException("Gambar sudah tidak tersedia."))
            return
        }

        var ocrBitmap: Bitmap? = null
        try {
            // Apply ROI crop if present
            val croppedBitmap = if (roi != null) {
                val safeLeft = roi.left.coerceIn(0, bitmap.width - 1)
                val safeTop = roi.top.coerceIn(0, bitmap.height - 1)
                val safeWidth = roi.width().coerceAtMost(bitmap.width - safeLeft)
                val safeHeight = roi.height().coerceAtMost(bitmap.height - safeTop)
                if (safeWidth > 0 && safeHeight > 0) {
                    Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeWidth, safeHeight)
                } else {
                    bitmap
                }
            } else {
                bitmap
            }

            val maxDimension = maxOf(croppedBitmap.width, croppedBitmap.height)
            val scale = if (maxDimension > MAX_OCR_DIMENSION) {
                MAX_OCR_DIMENSION.toFloat() / maxDimension.toFloat()
            } else {
                1f
            }

            val inputBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    croppedBitmap,
                    (croppedBitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (croppedBitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true
                ).also { ocrBitmap = it }
            } else {
                croppedBitmap
            }

            val inputImage = InputImage.fromBitmap(inputBitmap, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    try {
                        val inverseScale = if (scale < 1f) 1f / scale else 1f
                        val offsetX = if (roi != null) roi.left else 0
                        val offsetY = if (roi != null) roi.top else 0
                        val result = toOcrResult(visionText, inverseScale, offsetX, offsetY)
                        onSuccess(result)
                    } catch (exception: Exception) {
                        onFailure(exception)
                    } finally {
                        if (croppedBitmap != bitmap && !croppedBitmap.isRecycled) {
                            croppedBitmap.recycle()
                        }
                        ocrBitmap?.let {
                            if (!it.isRecycled) it.recycle()
                        }
                        ocrBitmap = null
                    }
                }
                .addOnFailureListener { exception ->
                    ocrBitmap?.let {
                        if (!it.isRecycled) it.recycle()
                    }
                    ocrBitmap = null
                    onFailure(exception)
                }
        } catch (exception: Exception) {
            ocrBitmap?.let {
                if (!it.isRecycled) it.recycle()
            }
            ocrBitmap = null
            onFailure(exception)
        }
    }

    private fun toOcrResult(
        visionText: Text,
        inverseScale: Float,
        offsetX: Int = 0,
        offsetY: Int = 0
    ): OcrResult {
        val recognizedLines = visionText.textBlocks.flatMap { block ->
            block.lines.mapNotNull lineLoop@{ line ->
                val bounds = line.boundingBox ?: return@lineLoop null
                val lineBounds = scaleRect(bounds, inverseScale, offsetX, offsetY)

                val recognizedElements = line.elements.mapNotNull elementLoop@{ element ->
                    val elementBounds = element.boundingBox
                        ?: return@elementLoop null
                    val scaled = scaleRect(elementBounds, inverseScale, offsetX, offsetY)

                    RecognizedElement(
                        text = element.text,
                        left = scaled.left,
                        top = scaled.top,
                        right = scaled.right,
                        bottom = scaled.bottom
                    )
                }

                RecognizedLine(
                    text = line.text,
                    left = lineBounds.left,
                    top = lineBounds.top,
                    right = lineBounds.right,
                    bottom = lineBounds.bottom,
                    elements = recognizedElements
                )
            }
        }

        return OcrResult(
            fullText = visionText.text,
            lines = recognizedLines
        )
    }

    private fun scaleRect(rect: Rect, factor: Float, offsetX: Int = 0, offsetY: Int = 0): Rect =
        Rect(
            (rect.left * factor).roundToInt() + offsetX,
            (rect.top * factor).roundToInt() + offsetY,
            (rect.right * factor).roundToInt() + offsetX,
            (rect.bottom * factor).roundToInt() + offsetY
        )

    fun close() {
        recognizer.close()
    }
}
