package com.warung.autobag.gesture

import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Memetakan koordinat gambar capture ke koordinat layar perangkat,
 * lalu meneruskan satu gesture hold-and-drag ke Accessibility Service.
 */
object GestureController {

    data class ScreenPoint(
        val x: Float,
        val y: Float
    )

    fun mapImagePointToScreen(
        imageX: Float,
        imageY: Float,
        imageWidth: Int,
        imageHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ): ScreenPoint? {
        if (
            imageWidth <= 0 || imageHeight <= 0 ||
            screenWidth <= 0 || screenHeight <= 0 ||
            !imageX.isFinite() || !imageY.isFinite() ||
            imageX < 0f || imageY < 0f ||
            imageX >= imageWidth || imageY >= imageHeight
        ) {
            return null
        }

        return ScreenPoint(
            x = (imageX * screenWidth / imageWidth).roundToInt().toFloat(),
            y = (imageY * screenHeight / imageHeight).roundToInt().toFloat()
        )
    }


    /**
     * Sends one short tap at a product point detected in the captured image.
     * The game handles moving the tapped product into the bag.
     */
    fun executeTapFromImage(
        sourceImagePoint: ScreenPoint,
        imageWidth: Int,
        imageHeight: Int,
        durationMs: Long = TargetGameConfig.TAP_DURATION_MS,
        onComplete: (Boolean, String) -> Unit
    ): Boolean {
        val service = GestureAccessibilityService.instance
        if (service == null) {
            onComplete(false, "Accessibility Service belum aktif.")
            return false
        }

        val metrics = DisplayMetrics()
        val windowManager = service.getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val source = mapImagePointToScreen(
            sourceImagePoint.x,
            sourceImagePoint.y,
            imageWidth,
            imageHeight,
            metrics.widthPixels,
            metrics.heightPixels
        )

        if (source == null) {
            onComplete(false, "Pemetaan koordinat produk ke layar gagal.")
            return false
        }

        return service.tap(
            x = source.x,
            y = source.y,
            durationMs = durationMs,
            onComplete = onComplete
        )
    }

    /**
     * Maps a product point once, then dispatches a short sequential tap batch.
     */
    fun executeTapBatchFromImage(
        sourceImagePoint: ScreenPoint,
        imageWidth: Int,
        imageHeight: Int,
        tapCount: Int,
        durationMs: Long = TargetGameConfig.TAP_DURATION_MS,
        intervalMs: Long = TargetGameConfig.TAP_INTERVAL_MS,
        onComplete: (Boolean, String, Int) -> Unit
    ): Boolean {
        val service = GestureAccessibilityService.instance
        if (service == null) {
            onComplete(false, "Accessibility Service belum aktif.", 0)
            return false
        }

        val metrics = DisplayMetrics()
        val windowManager = service.getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val source = mapImagePointToScreen(
            sourceImagePoint.x,
            sourceImagePoint.y,
            imageWidth,
            imageHeight,
            metrics.widthPixels,
            metrics.heightPixels
        )

        if (source == null) {
            onComplete(false, "Pemetaan koordinat produk ke layar gagal.", 0)
            return false
        }

        return service.tapRepeated(
            x = source.x,
            y = source.y,
            tapCount = tapCount,
            durationMs = durationMs,
            intervalMs = intervalMs,
            onComplete = onComplete
        )
    }

    /**
     * Mengirim satu drag berdasarkan koordinat pada screenshot OCR.
     * Ukuran layar dibaca dari Accessibility Service saat gesture dikirim,
     * bukan dari Activity yang mungkin sedang berada pada orientasi berbeda.
     */
    fun executeOneItemFromImage(
        sourceImagePoint: ScreenPoint,
        bagImagePoint: ScreenPoint,
        imageWidth: Int,
        imageHeight: Int,
        holdDurationMs: Long = TargetGameConfig.HOLD_DURATION_MS,
        dragDurationMs: Long = TargetGameConfig.DRAG_DURATION_MS,
        onComplete: (Boolean, String) -> Unit
    ): Boolean {
        val service = GestureAccessibilityService.instance
        if (service == null) {
            onComplete(false, "Accessibility Service belum aktif.")
            return false
        }

        val metrics = DisplayMetrics()
        val windowManager = service.getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val source = mapImagePointToScreen(
            sourceImagePoint.x,
            sourceImagePoint.y,
            imageWidth,
            imageHeight,
            metrics.widthPixels,
            metrics.heightPixels
        )
        val destination = mapImagePointToScreen(
            bagImagePoint.x,
            bagImagePoint.y,
            imageWidth,
            imageHeight,
            metrics.widthPixels,
            metrics.heightPixels
        )

        if (source == null || destination == null) {
            onComplete(false, "Pemetaan koordinat gambar ke layar gagal.")
            return false
        }

        return executeOneItem(
            source = source,
            bag = destination,
            holdDurationMs = holdDurationMs,
            dragDurationMs = dragDurationMs,
            onComplete = onComplete
        )
    }

    fun executeOneItem(
        source: ScreenPoint,
        bag: ScreenPoint,
        holdDurationMs: Long = TargetGameConfig.HOLD_DURATION_MS,
        dragDurationMs: Long = TargetGameConfig.DRAG_DURATION_MS,
        onComplete: (Boolean, String) -> Unit
    ): Boolean {
        val service = GestureAccessibilityService.instance
        if (service == null) {
            onComplete(false, "AccessibilityService belum aktif.")
            return false
        }

        return service.holdAndDrag(
            startX = source.x,
            startY = source.y,
            endX = bag.x,
            endY = bag.y,
            holdDurationMs = holdDurationMs,
            dragDurationMs = dragDurationMs,
            onComplete = onComplete
        )
    }
}
