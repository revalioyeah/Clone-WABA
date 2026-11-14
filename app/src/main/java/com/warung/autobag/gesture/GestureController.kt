package com.warung.autobag.gesture

import kotlin.math.roundToInt

/**
 * Thin controller facade for the order executor.
 *
 * This class deliberately does not start a full order loop yet.
 * It provides a single-item gesture primitive and safe coordinate mapping.
 */
object GestureController {

    data class ScreenPoint(
        val x: Float,
        val y: Float
    )

    /**
     * Converts a point measured in the captured screenshot into physical
     * display coordinates. The caller must ensure the screenshot and game
     * viewport have the same orientation and content bounds.
     */
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
            imageX < 0f || imageY < 0f ||
            imageX > imageWidth || imageY > imageHeight
        ) {
            return null
        }

        return ScreenPoint(
            x = (imageX * screenWidth / imageWidth).roundToInt().toFloat(),
            y = (imageY * screenHeight / imageHeight).roundToInt().toFloat()
        )
    }

    fun executeOneItem(
        source: ScreenPoint,
        bag: ScreenPoint,
        holdDurationMs: Long = 350L,
        dragDurationMs: Long = 650L,
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
