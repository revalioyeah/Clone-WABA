package com.warung.autobag.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
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
        gestureInProgress = false
    }

    override fun onDestroy() {
        if (instance === this) instance = null
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

    private fun finishGesture() {
        gestureInProgress = false
    }
}
