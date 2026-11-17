import com.warung.autobag.minigame.NpcVision
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
fun main() {
    var checks = 0
    fun expect(ok: Boolean, message: String) { check(ok) { message }; checks++ }
    fun vision(path: String, override: ((Int,Int)->Int?)? = null): NpcVision {
        val im = ImageIO.read(File(path))
        return NpcVision { x,y -> override?.invoke(x,y) ?: im.getRGB((x*im.width/2340).coerceIn(0,im.width-1),(y*im.height/1080).coerceIn(0,im.height-1)) }
    }
    for (half in listOf(false,true)) {
        fun path(n: String) = if (half) "tests/frames/${n}_half.jpg" else "tests/frames/$n.png"
        val expected = listOf(0,0,2,1,0,0,1)
        for (i in expected.indices) {
            val v = vision(path("01-video1$i"))
            expect(v.panelVisible(NpcVision.Game.GISKA), "Giska panel $i half=$half")
            expect(v.cardboardSize() == expected[i], "Giska size $i half=$half")
        }
        for ((name,game) in listOf("01-video17" to NpcVision.Game.GISKA,"video47" to NpcVision.Game.SUMI,"video57" to NpcVision.Game.UJANG))
            expect(!vision(path(name)).panelVisible(game), "Result overlay must disable $game")
        val initial = vision(path("video40"))
        expect(initial.plate.map { initial.food(it,true) } == listOf(NpcVision.Food.COOKED,NpcVision.Food.COOKED,NpcVision.Food.COOKED,NpcVision.Food.BURNT), "Initial food")
        expect(initial.pan.all { initial.food(it) == NpcVision.Food.UNKNOWN }, "Empty oil must never be burnt food")
        expect(initial.rengginangAction()?.to in initial.tray, "Cooked plate to tray")
        val cooking = vision(path("video45"))
        expect(cooking.pan.all { cooking.food(it) == NpcVision.Food.RAW }, "White food cooking")
        val done = vision(path("video46"))
        expect(done.rengginangAction()?.from in done.pan, "Cooked pan has priority")
        val burnt = vision(path("video43"))
        expect(burnt.rengginangAction()?.to == NpcVision.Point(530,880), "Burnt plate to bin")
        // Recorded raw plate/empty pan, remove the one cooked item to exercise frying branch.
        val raw = vision(path("video44")) { x,y -> if (abs(x-840)<60 && abs(y-505)<60) 0xff7197b8.toInt() else null }
        expect(raw.rengginangAction()?.to in raw.pan, "Raw plate must go to empty pan")
        // Both pan slots occupied in the recording: never drop another raw item into either.
        val full = vision(path("video45")) { x,y -> if (abs(x-840)<60 && abs(y-670)<60) 0xff7197b8.toInt() else null }
        expect(full.rengginangAction() == null, "Wait for occupied pan")
        for (i in 0..6) {
            val v = vision(path("video5$i"))
            expect(v.hangingCement() != null, "Track hanging cement $i")
            expect(v.stackCenter() in 1100..1320, "Track cart/stack $i")
        }
    }
    val sequence = File("tests/frames").listFiles()!!.filter { Regex("video5_\\d+\\.jpg").matches(it.name) }.sortedBy { it.name }
    var visible = 0; var moving = 0; var crossings = 0
    var lastX: Int? = null
    for (f in sequence) {
        val v = vision(f.path)
        if (!v.panelVisible(NpcVision.Game.UJANG)) {lastX=null;continue}
        visible++
        val bag = v.hangingCement()
        if (bag == null) { lastX=null; continue }
        moving++
        val prior = lastX
        if (prior != null && abs(bag.x-prior)<400) {
            val predicted = bag.x + (bag.x-prior)/83.33f*35
            if (abs(predicted-v.stackCenter())<=38 && abs(bag.x-v.stackCenter())<=110) crossings++
        }
        lastX=bag.x
    }
    expect(visible>80 && moving>60, "Detect throughout moving sequence")
    expect(crossings>=3, "Find multiple alignment windows")
    println("PASS: $checks assertions; Ujang frames=${sequence.size}, panel=$visible, moving=$moving, alignment windows=$crossings. Replay only, not a live-game test.")
}
