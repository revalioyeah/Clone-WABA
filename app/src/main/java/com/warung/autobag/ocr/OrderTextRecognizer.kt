
package com.warung.autobag.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Satu elemen teks hasil OCR.
 * Menyimpan teks dan koordinat asli setiap elemen.
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
 * Menyimpan teks lengkap dan seluruh elemen di dalamnya.
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
 */
class OrderTextRecognizer {

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    /**
     * Membaca teks dan koordinat setiap elemen dari Bitmap.
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

                        block.lines.mapNotNull lineLoop@{ line ->

                            val bounds = line.boundingBox
                                ?: return@lineLoop null

                            // Ambil setiap elemen teks
                            // beserta koordinat aslinya.
                            val recognizedElements =
                                line.elements.mapNotNull elementLoop@{ element ->

                                    val elementBounds =
                                        element.boundingBox
                                            ?: return@elementLoop null

                                    RecognizedElement(
                                        text = element.text,
                                        left = elementBounds.left,
                                        top = elementBounds.top,
                                        right = elementBounds.right,
                                        bottom = elementBounds.bottom
                                    )
                                }

                            RecognizedLine(
                                text = line.text,
                                left = bounds.left,
                                top = bounds.top,
                                right = bounds.right,
                                bottom = bounds.bottom,
                                elements = recognizedElements
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
