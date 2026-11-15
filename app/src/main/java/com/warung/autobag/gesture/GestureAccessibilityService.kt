package com.warung.autobag.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility bridge for controlled gestures.
 *
 * Security boundary:
 * - No gesture is dispatched unless the configured game package is active.
 * - No gesture is dispatched when the target package has not been configured.
 * - Callers must still validate coordinates before invoking this service.
 */
class GestureAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    private var gestureInProgress = false
    @Volatile
    private var tapBatchGeneration = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Receive only events from the configured game when configured.
        // An intentionally nonexistent package keeps event delivery narrow
        // while the package name is still unknown.
        val targetPackage = TargetGameConfig.TARGET_GAME_PACKAGE
        serviceInfo = serviceInfo.apply {
            packageNames = if (targetPackage.isNotBlank()) {
                arrayOf(targetPackage)
            } else {
                arrayOf("com.warung.autobag.__target_not_configured__")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are intentionally not used to trigger gestures automatically.
        // They are available for later foreground/interruption state management.
    }

    override fun onInterrupt() {
        tapBatchGeneration++
        gestureInProgress = false
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        tapBatchGeneration++
        gestureInProgress = false
        super.onDestroy()
    }

    /**
     * Fail-closed foreground check. If Android cannot confirm the active
     * package, the service refuses to send a gesture.
     */
    fun isTargetGameForeground(): Boolean {
        val targetPackage = TargetGameConfig.TARGET_GAME_PACKAGE
        if (targetPackage.isBlank()) return false

        val focusedWindowPackage = try {
            windows
                .firstOrNull { it.isFocused }
                ?.root
                ?.packageName
                ?.toString()
        } catch (_: Exception) {
            null
        }

        val activeRootPackage = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            null
        }

        val foregroundPackage = focusedWindowPackage ?: activeRootPackage
        return foregroundPackage == targetPackage
    }

    /**
     * Performs one hold-and-drag gesture.
     *
     * The first stroke holds at the source point. The continuation then
     * drags to the destination and ends the pointer contact.
     *
     * Coordinates are physical screen pixels, not OCR-image coordinates.
     * Coordinate mapping/calibration must be completed by the caller.
     */
    fun holdAndDrag(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        holdDurationMs: Long = 350L,
        dragDurationMs: Long = 650L,
        onComplete: (Boolean, String) -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onComplete(false, "Gesture dispatch memerlukan Android 7.0 atau lebih baru.")
            return false
        }

        if (!TargetGameConfig.isConfigured) {
            onComplete(false, "Package name game belum dikonfigurasi. Gesture ditolak.")
            return false
        }

        if (!isTargetGameForeground()) {
            onComplete(false, "Warung Life Simulator bukan aplikasi yang sedang aktif. Gesture ditolak.")
            return false
        }

        if (gestureInProgress) {
            onComplete(false, "Gesture lain masih berjalan.")
            return false
        }

        if (
            startX < 0f || startY < 0f ||
            endX < 0f || endY < 0f ||
            holdDurationMs <= 0L || dragDurationMs <= 0L
        ) {
            onComplete(false, "Koordinat atau durasi gesture tidak valid.")
            return false
        }

        gestureInProgress = true

        // A one-pixel segment keeps the pointer down for the hold phase.
        val holdPath = Path().apply {
            moveTo(startX, startY)
            lineTo(startX + 1f, startY)
        }

        val holdStroke = GestureDescription.StrokeDescription(
            holdPath,
            0L,
            holdDurationMs,
            true
        )

        val holdGesture = GestureDescription.Builder()
            .addStroke(holdStroke)
            .build()

        val accepted = dispatchGesture(
            holdGesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (!isTargetGameForeground()) {
                        // Do not continue dragging if the game lost focus.
                        finishGesture()
                        onComplete(
                            false,
                            "Game kehilangan fokus setelah fase hold. Drag dibatalkan."
                        )
                        return
                    }

                    val dragPath = Path().apply {
                        moveTo(startX + 1f, startY)
                        lineTo(endX, endY)
                    }

                    val dragStroke = holdStroke.continueStroke(
                        dragPath,
                        0L,
                        dragDurationMs,
                        false
                    )

                    val dragGesture = GestureDescription.Builder()
                        .addStroke(dragStroke)
                        .build()

                    val dragAccepted = dispatchGesture(
                        dragGesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(
                                gestureDescription: GestureDescription?
                            ) {
                                finishGesture()
                                onComplete(true, "Gesture selesai dikirim. Keberhasilan barang masuk tetap harus diverifikasi.")
                            }

                            override fun onCancelled(
                                gestureDescription: GestureDescription?
                            ) {
                                finishGesture()
                                onComplete(false, "Gesture drag dibatalkan oleh sistem.")
                            }
                        },
                        null
                    )

                    if (!dragAccepted) {
                        finishGesture()
                        onComplete(false, "Android menolak dispatch gesture drag.")
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    finishGesture()
                    onComplete(false, "Gesture hold dibatalkan oleh sistem.")
                }
            },
            null
        )

        if (!accepted) {
            finishGesture()
            onComplete(false, "Android menolak dispatch gesture hold.")
        }

        return accepted
    }

    /**
     * Tap helper for later use by the interruption handler.
     * The same target-app whitelist is enforced.
     */
    fun tap(
        x: Float,
        y: Float,
        durationMs: Long = 80L,
        onComplete: (Boolean, String) -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onComplete(false, "Gesture dispatch memerlukan Android 7.0 atau lebih baru.")
            return false
        }
        if (!TargetGameConfig.isConfigured || !isTargetGameForeground()) {
            onComplete(false, "Target game tidak aktif atau belum dikonfigurasi.")
            return false
        }
        if (gestureInProgress) {
            onComplete(false, "Gesture lain masih berjalan.")
            return false
        }
        if (x < 0f || y < 0f || durationMs <= 0L) {
            onComplete(false, "Koordinat atau durasi tap tidak valid.")
            return false
        }

        gestureInProgress = true
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs
                )
            )
            .build()

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    finishGesture()
                    onComplete(true, "Tap selesai dikirim.")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    finishGesture()
                    onComplete(false, "Tap dibatalkan oleh sistem.")
                }
            },
            null
        )

        if (!accepted) {
            finishGesture()
            onComplete(false, "Android menolak dispatch gesture tap.")
        }
        return accepted
    }

    data class TapSequenceTarget(
        val x: Float,
        val y: Float,
        val tapCount: Int
    )

    /** Prevents any not-yet-dispatched tap in the current batch. */
    fun cancelPendingTapBatch() {
        tapBatchGeneration++
    }

    /**
     * Executes taps for a small number of different products in sequence.
     * No OCR round-trip is inserted between product types; the caller must
     * verify all affected order quantities once this sequence completes.
     */
    fun tapSequence(
        targets: List<TapSequenceTarget>,
        durationMs: Long = TargetGameConfig.TAP_DURATION_MS,
        intervalMs: Long = TargetGameConfig.TAP_INTERVAL_MS,
        itemSwitchDelayMs: Long = TargetGameConfig.ITEM_SWITCH_DELAY_MS,
        onComplete: (Boolean, String, List<Int>) -> Unit
    ): Boolean {
        if (targets.isEmpty() || targets.size > TargetGameConfig.MAX_ITEM_TYPES_PER_SEQUENCE) {
            onComplete(false, "Jumlah jenis barang dalam urutan tap tidak valid.", emptyList())
            return false
        }
        if (targets.any { target ->
                !target.x.isFinite() || !target.y.isFinite() ||
                    target.x < 0f || target.y < 0f ||
                    target.tapCount !in 1..TargetGameConfig.MAX_TAPS_PER_BATCH
            }
        ) {
            onComplete(false, "Koordinat atau jumlah tap dalam urutan tidak valid.", List(targets.size) { 0 })
            return false
        }
        if (durationMs <= 0L || intervalMs < 0L || itemSwitchDelayMs < 0L) {
            onComplete(false, "Durasi atau jeda urutan tap tidak valid.", List(targets.size) { 0 })
            return false
        }
        if (!TargetGameConfig.isConfigured || !isTargetGameForeground()) {
            onComplete(false, "Target game tidak aktif atau belum dikonfigurasi.", List(targets.size) { 0 })
            return false
        }

        val handler = Handler(Looper.getMainLooper())
        val sequenceGeneration = ++tapBatchGeneration
        val completedCounts = MutableList(targets.size) { 0 }
        var targetIndex = 0
        var callbackSent = false

        fun finish(success: Boolean, detail: String) {
            if (callbackSent) return
            callbackSent = true
            onComplete(success, detail, completedCounts.toList())
        }

        fun dispatchNextTap() {
            if (callbackSent) return
            if (sequenceGeneration != tapBatchGeneration) {
                finish(false, "Urutan tap dibatalkan sebelum seluruh barang diproses.")
                return
            }
            if (!isTargetGameForeground()) {
                finish(false, "Game kehilangan fokus di tengah urutan tap.")
                return
            }
            if (targetIndex >= targets.size) {
                finish(true, "Urutan tap untuk ${targets.size} jenis barang selesai.")
                return
            }

            val target = targets[targetIndex]
            tap(
                x = target.x,
                y = target.y,
                durationMs = durationMs
            ) { success, detail ->
                if (!success) {
                    finish(false, detail)
                    return@tap
                }

                completedCounts[targetIndex]++
                if (sequenceGeneration != tapBatchGeneration) {
                    finish(false, "Urutan tap dibatalkan setelah ${completedCounts.sum()} tap.")
                    return@tap
                }

                val targetFinished = completedCounts[targetIndex] >= target.tapCount
                if (targetFinished) {
                    targetIndex++
                    if (targetIndex >= targets.size) {
                        finish(true, "Urutan tap untuk ${targets.size} jenis barang selesai.")
                    } else {
                        handler.postDelayed({ dispatchNextTap() }, itemSwitchDelayMs)
                    }
                } else {
                    handler.postDelayed({ dispatchNextTap() }, intervalMs)
                }
            }
        }

        handler.post { dispatchNextTap() }
        return true
    }

    /**
     * Sends a short sequence of taps at one cached product coordinate.
     * The queue is deliberately capped by TargetGameConfig.MAX_TAPS_PER_BATCH.
     * Each tap completes before the next one is dispatched.
     */
    fun tapRepeated(
        x: Float,
        y: Float,
        tapCount: Int,
        durationMs: Long = TargetGameConfig.TAP_DURATION_MS,
        intervalMs: Long = TargetGameConfig.TAP_INTERVAL_MS,
        onComplete: (Boolean, String, Int) -> Unit
    ): Boolean {
        if (tapCount !in 1..TargetGameConfig.MAX_TAPS_PER_BATCH) {
            onComplete(
                false,
                "Jumlah tap batch harus 1 sampai ${TargetGameConfig.MAX_TAPS_PER_BATCH}.",
                0
            )
            return false
        }
        if (intervalMs < 0L || durationMs <= 0L) {
            onComplete(false, "Durasi atau interval tap tidak valid.", 0)
            return false
        }
        if (!TargetGameConfig.isConfigured || !isTargetGameForeground()) {
            onComplete(false, "Target game tidak aktif atau belum dikonfigurasi.", 0)
            return false
        }

        val handler = Handler(Looper.getMainLooper())
        val batchGeneration = ++tapBatchGeneration
        var completedCount = 0
        var callbackSent = false

        fun finish(success: Boolean, detail: String) {
            if (callbackSent) return
            callbackSent = true
            onComplete(success, detail, completedCount)
        }

        fun dispatchNextTap() {
            if (callbackSent) return
            if (batchGeneration != tapBatchGeneration) {
                finish(false, "Batch tap dihentikan sebelum seluruh tap dikirim.")
                return
            }
            if (!isTargetGameForeground()) {
                finish(false, "Game kehilangan fokus di tengah batch tap.")
                return
            }

            // tap() invokes its callback for both asynchronous completion and
            // immediate rejection, so no second error callback is needed here.
            tap(
                x = x,
                y = y,
                durationMs = durationMs
            ) { success, detail ->
                if (!success) {
                    finish(false, detail)
                    return@tap
                }

                completedCount++
                if (completedCount >= tapCount) {
                    finish(true, "$completedCount tap selesai dikirim.")
                } else if (batchGeneration != tapBatchGeneration) {
                    finish(false, "Batch tap dihentikan setelah $completedCount tap.")
                } else {
                    handler.postDelayed(
                        { dispatchNextTap() },
                        intervalMs
                    )
                }
            }
        }

        handler.post { dispatchNextTap() }
        return true
    }

    private fun finishGesture() {
        gestureInProgress = false
    }
}
