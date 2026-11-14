package com.warung.autobag.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Satu elemen/kata yang dikenali OCR beserta koordinat aslinya.
 *
 * Koordinat menggunakan sistem koordinat Bitmap sumber.
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
 *
 * Properti elements memiliki nilai default agar pemanggilan lama
 * yang hanya mengirim lima parameter tetap kompatibel.
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
 *
 * Struktur OcrResult dipertahankan agar pemanggilan lama
 * OcrResult(fullText, lines) tetap kompatibel.
 */
data class OcrResult(
    val fullText: String,
    val lines: List<RecognizedLine>
)

/**
 * Mesin OCR lokal menggunakan Google ML Kit.
 *
 * Selain teks per baris, recognizer sekarang menyimpan koordinat
 * setiap Text.Element agar parser dapat membedakan teks dari
 * kolom kiri dan kanan meskipun ML Kit menggabungkannya dalam
 * satu baris OCR.
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

        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->

                val recognizedLines =
                    visionText.textBlocks.flatMap { block ->

                        block.lines.mapNotNull { line ->

                            val lineBounds = line.boundingBox
                                ?: return@mapNotNull null

                            val recognizedElements =
                                line.elements.mapNotNull { element ->

                                    val elementBounds =
                                        element.boundingBox
                                            ?: return@mapNotNull null

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
                                left = lineBounds.left,
                                top = lineBounds.top,
                                right = lineBounds.right,
                                bottom = lineBounds.bottom,
                                elements = recognizedElements
                            )
                        }
                    }

                onSuccess(
                    OcrResult(
                        fullText = visionText.text,
                        lines = recognizedLines
                    )
                )
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
