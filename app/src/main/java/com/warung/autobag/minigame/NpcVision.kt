package com.warung.autobag.minigame

import kotlin.math.abs

/** Coordinates are calibrated to the supplied 2340 x 1080 recordings. */
class NpcVision(private val pixel: (Int, Int) -> Int) {
    enum class Game(val title: String, val keyword: String) {
        GISKA("Giska · Pilih Kardus", "GISKA"),
        UJANG("Mang Ujang · Susun Semen", "UJANG"),
        SUMI("Nenek Sumi · Rengginang", "SUMI")
    }
    data class Point(val x: Int, val y: Int)
    data class Action(val from: Point, val to: Point? = null, val label: String)
    data class Blob(val x: Int, val y: Int, val width: Int, val height: Int, val area: Int)
    enum class Food { EMPTY, RAW, COOKED, BURNT, UNKNOWN }
    private fun r(c: Int) = c shr 16 and 255
    private fun g(c: Int) = c shr 8 and 255
    private fun b(c: Int) = c and 255
    private fun gold(c: Int) = r(c) > 220 && g(c) in 155..230 && b(c) in 65..170
    private fun nearGold(x: Int, y: Int): Boolean = (-5..5).any { dx ->
        (-5..5).any { dy -> gold(pixel(x + dx, y + dy)) }
    }
    fun panelVisible(game: Game): Boolean =
        nearGold(1000, if (game == Game.GISKA) 310 else 330) &&
            nearGold(381, 600) && nearGold(1961, 600)

    private fun blobs(x0: Int, y0: Int, x1: Int, y1: Int, predicate: (Int) -> Boolean): List<Blob> {
        val step = 4
        val w = (x1 - x0) / step
        val h = (y1 - y0) / step
        val mask = BooleanArray(w * h) { i -> predicate(pixel(x0 + i % w * step, y0 + i / w * step)) }
        val queue = IntArray(mask.size)
        val result = mutableListOf<Blob>()
        for (i in mask.indices) {
            if (!mask[i]) continue
            var head = 0; var tail = 1; queue[0] = i; mask[i] = false
            var left = i % w; var right = left; var top = i / w; var bottom = top
            while (head < tail) {
                val p = queue[head++]; val x = p % w; val y = p / w
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
                for (n in intArrayOf(if (x > 0) p - 1 else -1, if (x + 1 < w) p + 1 else -1,
                    if (y > 0) p - w else -1, if (y + 1 < h) p + w else -1)) {
                    if (n >= 0 && mask[n]) { mask[n] = false; queue[tail++] = n }
                }
            }
            if (tail >= 8) result += Blob(x0 + (left + right) * step / 2,
                y0 + (top + bottom) * step / 2, (right - left + 1) * step,
                (bottom - top + 1) * step, tail * step * step)
        }
        return result
    }
    fun cardboardSize(): Int? {
        val shadow = blobs(570, 675, 1010, 840) { c ->
            r(c) in 90..174 && r(c) - g(c) in 6..29 && g(c) - b(c) in 11..39
        }.filter { it.height in 28..100 && abs(it.x - 796) < 45 }.maxByOrNull { it.area } ?: return null
        return when (shadow.width) { in 125..180 -> 0; in 200..265 -> 1; in 290..365 -> 2; else -> null }
    }
    fun cardboardAction(size: Int) = Action(Point(1590, intArrayOf(440, 640, 845)[size]),
        label = "Kardus ${listOf("kecil", "sedang", "besar")[size]}")
    private fun cement(c: Int) = r(c) < 110 && g(c) in 65..165 && b(c) > g(c) + 25 && g(c) > r(c) + 15
    fun hangingCement(): Blob? = blobs(420, 398, 1910, 475, ::cement)
        .filter { it.width in 90..175 && it.height in 16..65 }.maxByOrNull { it.area }
    fun stackTop(): Blob? = blobs(800, 490, 1510, 900, ::cement)
        .filter { it.width in 85..180 && it.height in 16..75 }.minByOrNull { it.y }
    fun stackCenter(): Int = stackTop()?.x ?: 1170
    // The cart remains the anchor. Do not reproduce accumulated sideways errors.
    fun cementTarget(): Int {
        val support = stackCenter()
        return CementTiming.target(support)
    }

    val plate = listOf(Point(652, 505), Point(840, 505), Point(652, 670), Point(840, 670))
    val pan = listOf(Point(1041, 825), Point(1152, 850))
    val tray = listOf(Point(1532, 505), Point(1668, 584), Point(1668, 742),
        Point(1532, 820), Point(1395, 742), Point(1395, 584), Point(1532, 663))
    fun food(p: Point, onPlate: Boolean = false): Food {
        val inPan = p in pan
        var raw = 0; var cooked = 0; var burnt = 0; var blue = 0
        for (dx in -24..24 step 8) for (dy in -24..24 step 8) {
            val c = pixel(p.x + dx, p.y + dy)
            if (r(c) > 190 && g(c) > 180 && b(c) > 145 && r(c) - b(c) < 70) raw++
            if ((r(c) > 205 && g(c) in 155..235 && b(c) in 55..165 && r(c) - b(c) > 75) ||
                (inPan && r(c) >= 175 && g(c) in 130..230 && b(c) in 50..165 &&
                    r(c) - b(c) >= 65 && r(c) - g(c) >= 20)) cooked++
            if (r(c) in 90..160 && g(c) in 55..118 && b(c) in 25..85 && r(c) - g(c) > 27) burnt++
            if (b(c) > r(c) + 20 && g(c) > r(c)) blue++
        }
        return when {
            raw >= 28 -> Food.RAW
            cooked >= 28 -> Food.COOKED
            burnt >= 28 -> Food.BURNT
            onPlate && blue >= 30 -> Food.EMPTY
            else -> Food.UNKNOWN
        }
    }
    // Empty target requires the white ring; never infer emptiness from brown alone.
    private fun emptyRing(p: Point): Boolean {
        var hits = 0
        for (i in 0 until 12) {
            val angle = i * Math.PI / 6
            val x = p.x + (56 * kotlin.math.cos(angle)).toInt()
            val y = p.y + (56 * kotlin.math.sin(angle)).toInt()
            if ((-4..4).any { d ->
                val c = pixel(x + d, y)
                r(c) > 205 && g(c) > 185 && b(c) > 135 && r(c) - b(c) < 85
            }) hits++
        }
        return hits >= 7 && food(p) !in listOf(Food.RAW, Food.COOKED)
    }
    fun dragDelivered(action: Action, sourceBefore: Food?): Boolean {
        val target = action.to ?: return false
        val targetFood = food(target)
        if (target in pan && targetFood in listOf(Food.RAW, Food.COOKED, Food.BURNT)) return true
        if (target in tray && targetFood == Food.COOKED) return true
        return food(action.from, action.from.x < 900) != sourceBefore
    }
    fun urgentPanAction(): Action? {
        val destination = tray.firstOrNull { emptyRing(it) }
        val cookedPan = pan.firstOrNull { food(it) == Food.COOKED }
        if (cookedPan != null && destination != null) return Action(cookedPan, destination, "Angkat dari wajan")
        val burntPan = pan.firstOrNull { food(it) == Food.BURNT }
        if (burntPan != null) return Action(burntPan, Point(530, 880), "Buang gosong dari wajan")
        return null
    }
    fun rengginangAction(): Action? {
        urgentPanAction()?.let { return it }
        val destination = tray.firstOrNull { emptyRing(it) }
        val cookedPlate = plate.firstOrNull { food(it, true) == Food.COOKED }
        if (cookedPlate != null && destination != null) return Action(cookedPlate, destination, "Susun rengginang matang")
        val burntPlate = plate.firstOrNull { food(it, true) == Food.BURNT }
        if (burntPlate != null) return Action(burntPlate, Point(530, 880), "Buang rengginang gosong")
        // One batch in the pan at a time; reserve a free tray slot before frying.
        if (destination == null || pan.any { food(it) in listOf(Food.RAW, Food.COOKED, Food.BURNT) }) return null
        val rawPlate = plate.firstOrNull { food(it, true) == Food.RAW }
        val emptyPan = pan.firstOrNull { p ->
            var oil = 0
            for (dx in -20..20 step 10) for (dy in -20..20 step 10) {
                val c = pixel(p.x + dx, p.y + dy)
                if (r(c) in 95..155 && r(c) - g(c) in 10..27 && g(c) - b(c) in 30..60) oil++
            }
            food(p) == Food.UNKNOWN && oil >= 17
        }
        if (rawPlate != null && emptyPan != null) return Action(rawPlate, emptyPan, "Goreng rengginang putih")
        return null
    }
}

/** Timing in reference pixels and milliseconds, isolated for replay/unit tests. */
object CementTiming {
    fun target(support: Int): Int = support + (1170 - support).coerceIn(-60, 60)
    fun crossingDelay(x: Int, target: Int, velocity: Float, previous: Float?, age: Long, lead: Float): Long? {
        if (previous == null || age !in 0..180 || abs(velocity) !in 0.2f..5f ||
            velocity * previous <= 0f || abs(velocity - previous) > maxOf(0.6f, abs(velocity) * 0.4f)) return null
        val speed = velocity * 0.7f + previous * 0.3f
        val delay = (target - (x + speed * (age + lead))) / speed
        return if (delay in -6f..80f) delay.toLong().coerceAtLeast(0) else null
    }
    fun adjustLead(current: Float, releaseX: Int, target: Int, speed: Float): Float {
        if (abs(speed) < 0.3f) return current
        return (current + ((releaseX - target) / speed).coerceIn(-60f, 60f) * 0.5f).coerceIn(10f, 160f)
    }
}
