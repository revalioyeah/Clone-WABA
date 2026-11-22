import com.warung.autobag.detection.ItemCatalog
import com.warung.autobag.detection.SmartItemDetector
import com.warung.autobag.ocr.OcrResult
import com.warung.autobag.ocr.OrderParser
import com.warung.autobag.ocr.ParsedOrderItem
import com.warung.autobag.ocr.RecognizedElement
import com.warung.autobag.ocr.RecognizedLine

fun main() {
    var checks = 0
    fun expect(condition: Boolean, label: String) {
        check(condition) { label }
        checks++
    }

    val header = RecognizedLine(
        text = "PESANAN",
        left = 900,
        top = 70,
        right = 1040,
        bottom = 105
    )
    val orderLine = RecognizedLine(
        text = "Kopi sashet 0I1",
        left = 900,
        top = 130,
        right = 1190,
        bottom = 164,
        elements = listOf(
            RecognizedElement("Kopi", 900, 130, 960, 164),
            RecognizedElement("sashet", 965, 130, 1060, 164),
            RecognizedElement("0I1", 1130, 130, 1190, 164)
        )
    )
    val parsed = OrderParser.parse(
        OcrResult(
            fullText = "PESANAN\nKopi sashet 0I1",
            lines = listOf(header, orderLine)
        )
    )
    expect(parsed.headerFound, "Order header is recognized")
    expect(parsed.items.size == 1, "Fuzzy item-name fallback returns one item")
    expect(parsed.items.first().name == "Kopi sachet", "OCR typo maps to canonical catalog name")
    expect(parsed.items.first().currentQuantity == 0, "OCR I is normalized to quantity 1 separator/current")
    expect(parsed.items.first().requiredQuantity == 1, "OCR quantity denominator is parsed")


    val orphanQuantity = RecognizedLine(
        text = "0/1",
        left = 1130,
        top = 180,
        right = 1190,
        bottom = 210,
        elements = listOf(RecognizedElement("0/1", 1130, 180, 1190, 210))
    )
    val partial = OrderParser.parse(
        OcrResult(
            "PESANAN\nKopi sashet 0I1\n0/1",
            listOf(header, orderLine, orphanQuantity)
        )
    )
    expect(partial.unmatchedQuantityCount == 1, "Unpaired quantity row is reported")

    val shelfLabel = RecognizedLine(
        text = "Kopi sashet",
        left = 420,
        top = 410,
        right = 540,
        bottom = 438,
        elements = listOf(
            RecognizedElement("Kopi", 420, 410, 470, 438),
            RecognizedElement("sashet", 475, 410, 540, 438)
        )
    )
    val detection = SmartItemDetector.detect(
        ocrResult = OcrResult(
            fullText = "Kopi sashet",
            lines = listOf(shelfLabel)
        ),
        orders = listOf(
            ParsedOrderItem(
                name = "Kopi sachet",
                currentQuantity = 0,
                requiredQuantity = 1,
                rawText = "Kopi sashet 0I1"
            )
        ),
        imageWidth = 2304,
        imageHeight = 1080,
        catalogEntries = ItemCatalog.entries
    )
    expect(detection.detections.size == 1, "Shelf detector accepts controlled OCR typo")
    val target = detection.detections.first()
    expect(target.targetX in 1f..2302f, "Detected X stays within screenshot")
    expect(target.targetY in 1f..1078f, "Detected Y stays within screenshot")
    expect(target.targetX / 2304f in 0.02f..0.43f, "Detected X stays in left shelf")
    expect(target.targetY / 1080f in 0.12f..0.78f, "Detected Y stays in left shelf")


    val edgeLabel = RecognizedLine(
        text = "Kopi sachet",
        left = 420,
        top = 145,
        right = 540,
        bottom = 170,
        elements = listOf(
            RecognizedElement("Kopi", 420, 145, 470, 170),
            RecognizedElement("sachet", 475, 145, 540, 170)
        )
    )
    val edgeDetection = SmartItemDetector.detect(
        ocrResult = OcrResult("Kopi sachet", listOf(edgeLabel)),
        orders = listOf(
            ParsedOrderItem("Kopi sachet", 0, 1, "Kopi sachet 0/1")
        ),
        imageWidth = 2304,
        imageHeight = 1080,
        catalogEntries = ItemCatalog.entries
    )
    expect(edgeDetection.detections.isEmpty(), "Out-of-shelf target estimate is rejected")

    println("OrderRecognitionRegression: $checks checks passed")
}
