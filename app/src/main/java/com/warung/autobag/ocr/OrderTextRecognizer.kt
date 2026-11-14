
package com.warung.autobag.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Data satu baris teks yang berhasil dikenali OCR.
 * Koordinat menggunakan posisi asli pada gambar.
 */
data class RecognizedLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
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
 */
class OrderTextRecognizer {

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    /**
     * Membaca teks dari Bitmap.
     */
    fun recognize(
        bitmap: Bitmap,
        onSuccess: (OcrResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {

        if (bitmap.isRecycled) {
            onFailure(
                IllegalArgumentException(
                    "Gambar sudah tidak tersedia."
                )
            )
            return
        }

        val image = InputImage.fromBitmap(
            bitmap,
            0
        )

        recognizer.process(image)

            .addOnSuccessListener { visionText ->

                val recognizedLines =
                    visionText.textBlocks.flatMap { block ->

                        block.lines.mapNotNull { line ->

                            val bounds = line.boundingBox
                                ?: return@mapNotNull null

                            RecognizedLine(
                                text = line.text,
                                left = bounds.left,
                                top = bounds.top,
                                right = bounds.right,
                                bottom = bounds.bottom
                            )
                        }
                    }

                val result = OcrResult(
                    fullText = visionText.text,
                    lines = recognizedLines
                )

                onSuccess(result)
            }

            .addOnFailureListener { exception ->

                onFailure(exception)
            }
    }

    /**
     * Menutup resource OCR.
     */
    fun close() {
        recognizer.close()
    }
}
