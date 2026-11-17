import com.warung.autobag.minigame.NpcVision
import com.warung.autobag.minigame.CementTiming
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
fun main() {
    var count = 0
    fun expect(ok: Boolean, label: String) { check(ok) { label }; count++ }
    fun vision(path: String, dimPan: Boolean = false): NpcVision {
        val im=ImageIO.read(File(path))
        return NpcVision { x,y ->
            val c=im.getRGB(x*im.width/2340,y*im.height/1080)
            if(dimPan && x in 990..1200 && y in 770..900) {
                val r=((c shr 16 and 255)*0.85).toInt();val g=((c shr 8 and 255)*0.85).toInt();val b=((c and 255)*0.85).toInt()
                (255 shl 24) or (r shl 16) or (g shl 8) or b
            } else c
        }
    }
    for (suffix in listOf(".png","_half.jpg")) {
        val raw=vision("tests/frames/video45$suffix")
        val old=NpcVision.Action(raw.plate[0],raw.pan[0],"Goreng rengginang putih")
        expect(raw.food(raw.plate[0],true)==NpcVision.Food.RAW,"Source still raw")
        expect(raw.dragDelivered(old,NpcVision.Food.RAW),"Pan occupancy confirms successful drag even when source respawns raw")
        val cooked=vision("tests/frames/video46$suffix",true)
        expect(cooked.food(cooked.pan[0])==NpcVision.Food.COOKED,"Dim cooked food detected in pan")
        expect(cooked.urgentPanAction()?.from in cooked.pan,"Pan harvest outranks retries")
        val empty=vision("tests/frames/video40$suffix")
        expect(empty.urgentPanAction()==null,"Empty oil does not trigger harvest or trash")
        expect(!empty.dragDelivered(NpcVision.Action(empty.plate[0],empty.pan[0],"test"),NpcVision.Food.COOKED),"Failed drag not falsely accepted")
    }
    for(support in listOf(950,1050,1140,1170,1210,1300,1400)) {
        val t=CementTiming.target(support)
        expect(abs(t-1170)<=abs(support-1170),"Move toward cart center")
        expect(abs(t-support)<=60,"Maintain supporting overlap")
    }
    for(v in listOf(-3f,-1f,1f,3f)) {
        val x=(1170-v*100).toInt()
        val delay=CementTiming.crossingDelay(x,1170,v,v,20,35f)
        expect(delay!=null && abs(x+v*(20+35+delay!!)-1170)<=3,"Predict crossing in either direction")
        expect(CementTiming.crossingDelay(x,1170,v,-v,20,35f)==null,"Reject reversal")
        expect(CementTiming.crossingDelay(x,1170,v,v,250,35f)==null,"Reject stale frame")
        expect(CementTiming.adjustLead(35f,(1170+v*40).toInt(),1170,v)>35,"Learn late release")
    }
    val frames=File("tests/trial2").listFiles()!!.sortedBy { it.name }
    var visible=0;var detected=0
    for(f in frames) {
        val v=vision(f.path)
        if(!v.panelVisible(NpcVision.Game.UJANG))continue
        visible++
        if(v.hangingCement()!=null)detected++
        val support=v.stackCenter();val t=v.cementTarget()
        expect(abs(t-1170)<=abs(support-1170),"Replay target moves toward cart")
        expect(abs(t-support)<=60,"Replay keeps overlap")
    }
    expect(visible>120 && detected>80,"New Ujang recording coverage")
    println("PASS: $count checks; new Ujang replay ${frames.size} frames, $visible panels, $detected hanging sacks. No live gameplay claim.")
}
