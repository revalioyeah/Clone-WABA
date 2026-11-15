package com.warung.autobag.minigame

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.warung.autobag.capture.ScreenCaptureService
import com.warung.autobag.gesture.GestureAccessibilityService
import com.warung.autobag.gesture.GestureController
import com.warung.autobag.ocr.OrderTextRecognizer
import java.io.File
import kotlin.math.abs

/** One selected NPC, one in-flight gesture, no background/other-app input. */
class NpcAutomation(
    private val directory: File,
    private val game: NpcVision.Game,
    private val status: (String) -> Unit,
    private val stopped: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = OrderTextRecognizer()
    private var active = false
    private var lastFrame = 0L
    private var gestureBusy = false
    private var nextAction = 0L
    private var lastOcr = 0L
    private var ocrBusy = false
    private var titleUntil = 0L
    private var seenPanel = false
    private var absentSince = 0L
    private var stableSize: Int? = null
    private var stableCount = 0
    private var previousX: Int? = null
    private var previousTime = 0L
    private var waitingForDrop = false
    private var dropTime = 0L
    private var lastDrag: NpcVision.Action? = null
    private var lastSourceFood: NpcVision.Food? = null
    private var dragRetries = 0
    private val scan = Runnable { scanFrame() }

    fun start() {
        active = true
        lastFrame = File(directory, ScreenCaptureService.CAPTURE_FILE_NAME).lastModified()
        ScreenCaptureService.setAutomationActive(true)
        ScreenCaptureService.setFastMinigameCapture(game == NpcVision.Game.UJANG)
        status("Pindah ke minigame ${game.title}. Menunggu panel permainan.")
        handler.postDelayed(scan, 1500)
    }
    fun stop(message: String) {
        if (!active) return
        active = false
        handler.removeCallbacks(scan)
        ScreenCaptureService.setFastMinigameCapture(false)
        ScreenCaptureService.setAutomationActive(false)
        recognizer.close()
        status(message)
        stopped()
    }
    private fun scanFrame() {
        if (!active) return
        try {
            if (!ScreenCaptureService.isRunning || GestureAccessibilityService.instance == null) {
                stop("Capture atau Accessibility terputus."); return
            }
            if (GestureAccessibilityService.instance?.isTargetGameForeground() != true) {
                titleUntil = 0L; previousX = null; stableCount = 0
                return
            }
            val file = File(directory, ScreenCaptureService.CAPTURE_FILE_NAME)
            val modified = file.lastModified()
            val age = System.currentTimeMillis() - modified
            if (modified <= lastFrame || age !in 0..500) return
            lastFrame = modified
            val frame = BitmapFactory.decodeFile(file.absolutePath) ?: return
            try {
                if (frame.height < 240 || frame.width.toFloat() / frame.height !in 1.9f..2.35f) {
                    status("Rasio layar belum didukung. Gunakan landscape seperti rekaman."); return
                }
                val now = SystemClock.uptimeMillis()
                val v = NpcVision { x, y -> frame.getPixel(
                    (x * frame.width / 2340).coerceIn(0, frame.width - 1),
                    (y * frame.height / 1080).coerceIn(0, frame.height - 1)) }
                readScreen(frame, now)
                if (!v.panelVisible(game)) {
                    previousX = null; stableCount = 0
                    if (seenPanel) {
                        if (absentSince == 0L) absentSince = now
                        if (now - absentSince > 1800) stop("Panel permainan berakhir atau tertutup. Switch dimatikan.")
                    }
                    return
                }
                absentSince = 0L
                if (now > titleUntil) return
                seenPanel = true
                if (gestureBusy || now < nextAction) return
                when (game) {
                    NpcVision.Game.GISKA -> {
                        val size = v.cardboardSize()
                        if (size == null) { stableCount = 0; status("Menunggu ukuran bayangan yang jelas."); return }
                        stableCount = if (size == stableSize) stableCount + 1 else 1
                        stableSize = size
                        if (stableCount >= 2) {
                            send(v.cardboardAction(size), frame, now)
                            stableCount = 0
                        }
                    }
                    NpcVision.Game.UJANG -> handleCement(v, frame, now, age)
                    NpcVision.Game.SUMI -> {
                        val old = lastDrag
                        if (old != null) {
                            val same = v.food(old.from, old.from.x < 900) == lastSourceFood
                            val placed = old.to in v.tray && old.to?.let { v.food(it) } == NpcVision.Food.COOKED
                            if (same && !placed) {
                                if (++dragRetries > 2) { stop("Drag belum berhasil setelah 3 percobaan. Periksa posisi layar."); return }
                                send(old, frame, now); return
                            }
                            lastDrag = null; dragRetries = 0
                        }
                        val action = v.rengginangAction()
                        if (action != null) {
                            lastSourceFood = v.food(action.from, action.from.x < 900)
                            lastDrag = action
                            send(action, frame, now)
                        } else status("Menunggu matang atau slot tampah kosong.")
                    }
                }
            } finally { frame.recycle() }
        } catch (e: Exception) {
            stop("Otomatisasi dihentikan: ${e.message ?: "frame tidak dapat dibaca"}")
        } finally {
            if (active) handler.postDelayed(scan, 25)
        }
    }
    private fun handleCement(v: NpcVision, frame: Bitmap, now: Long, age: Long) {
        val moving = v.hangingCement()
        if (waitingForDrop) {
            if (moving == null) { waitingForDrop = false; nextAction = now + 300; previousX = null }
            else if (now - dropTime > 2000) { waitingForDrop = false; previousX = null }
            return
        }
        if (moving == null) { previousX = null; return }
        val prior = previousX
        val dt = now - previousTime
        previousX = moving.x; previousTime = now
        if (prior == null || dt !in 15..250) return
        val velocity = (moving.x - prior).toFloat() / dt
        if (abs(velocity) > 5f) return // animation/reset, not horizontal travel
        val target = v.stackCenter()
        // Frame write age + short dispatch estimate; do not chase an already missed crossing.
        val predicted = moving.x + velocity * (age.coerceAtMost(120) + 35)
        if (abs(predicted - target) <= 38 && abs(moving.x - target) <= 110) {
            if (send(NpcVision.Action(NpcVision.Point(1170, 600), label = "Jatuhkan semen di pusat tumpukan"), frame, now)) {
                waitingForDrop = true; dropTime = now
            }
        } else status("Menunggu semen sejajar dengan tumpukan.")
    }
    private fun send(action: NpcVision.Action, frame: Bitmap, now: Long): Boolean {
        fun point(p: NpcVision.Point) = GestureController.ScreenPoint(
            p.x * frame.width / 2340f, p.y * frame.height / 1080f)
        gestureBusy = true
        nextAction = now + if (game == NpcVision.Game.GISKA) 420 else 120
        val complete: (Boolean, String) -> Unit = { success, message ->
            gestureBusy = false
            if (active) {
                nextAction = maxOf(nextAction, SystemClock.uptimeMillis() + if (action.to != null) 180 else 60)
                if (!success) status("Gesture ditolak: $message")
            }
        }
        val accepted = if (action.to == null) GestureController.executeTapFromImage(
            point(action.from), frame.width, frame.height, durationMs = 35, onComplete = complete)
        else GestureController.executeOneItemFromImage(
            point(action.from), point(action.to), frame.width, frame.height,
            holdDurationMs = 65, dragDurationMs = 180, onComplete = complete)
        if (!accepted) { gestureBusy = false; nextAction = now + 300 }
        else status(action.label)
        return accepted
    }
    private fun readScreen(frame: Bitmap, now: Long) {
        if (ocrBusy || now - lastOcr < 850) return
        lastOcr = now; ocrBusy = true
        val copy = Bitmap.createScaledBitmap(frame, 1170, 540, true)
        // A separately owned bitmap is essential: the scan frame is recycled immediately.
        val input = if (copy === frame) frame.copy(Bitmap.Config.ARGB_8888, false) else copy
        fun release() { if (!input.isRecycled) input.recycle(); ocrBusy = false }
        try {
            recognizer.recognize(input, onSuccess = { result ->
                release()
                if (active && SystemClock.uptimeMillis() - now < 1500) {
                    val text = result.fullText.uppercase()
                    val finished = result.lines.any { line ->
                        line.top > 140 && line.bottom < 470 &&
                            (line.text.uppercase().contains("SELESAI") ||
                                line.text.uppercase().contains("HASIL KERJA"))
                    }
                    if (finished && seenPanel) stop("Minigame selesai. Switch dimatikan.")
                    else if (text.contains(game.keyword)) titleUntil = now + 2200
                    else titleUntil = 0L
                }
            }, onFailure = { release(); titleUntil = 0L })
        } catch (_: Exception) { release(); titleUntil = 0L }
    }
}
