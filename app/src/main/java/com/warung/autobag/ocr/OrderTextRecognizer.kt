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
        onSuccess: (OcrResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (bitmap.isRecycled) {
            onFailure(IllegalArgumentException("Gambar sudah tidak tersedia."))
            return
        }

        var ocrBitmap: Bitmap? = null
        try {
            val maxDimension = maxOf(bitmap.width, bitmap.height)
            val scale = if (maxDimension > MAX_OCR_DIMENSION) {
                MAX_OCR_DIMENSION.toFloat() / maxDimension.toFloat()
            } else {
                1f
            }

            val inputBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true
                ).also { ocrBitmap = it }
            } else {
                bitmap
            }

            val inputImage = InputImage.fromBitmap(inputBitmap, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    try {
                        val inverseScale = if (scale < 1f) 1f / scale else 1f
                        val result = toOcrResult(visionText, inverseScale)
                        onSuccess(result)
                    } catch (exception: Exception) {
                        onFailure(exception)
                    } finally {
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
        inverseScale: Float
    ): OcrResult {
        val recognizedLines = visionText.textBlocks.flatMap { block ->
            block.lines.mapNotNull lineLoop@{ line ->
                val bounds = line.boundingBox ?: return@lineLoop null
                val lineBounds = scaleRect(bounds, inverseScale)

                val recognizedElements = line.elements.mapNotNull elementLoop@{ element ->
                    val elementBounds = element.boundingBox
                        ?: return@elementLoop null
                    val scaled = scaleRect(elementBounds, inverseScale)

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

    private fun scaleRect(rect: Rect, factor: Float): Rect =
        Rect(
            (rect.left * factor).roundToInt(),
            (rect.top * factor).roundToInt(),
            (rect.right * factor).roundToInt(),
            (rect.bottom * factor).roundToInt()
        )

    fun close() {
        recognizer.close()
    }
}
