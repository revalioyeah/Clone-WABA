
package com.warung.autobag.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.system.Os
import android.util.Log
import android.view.WindowManager
import com.warung.autobag.MainActivity
import com.warung.autobag.gesture.GestureAccessibilityService
import java.io.File
import java.io.FileOutputStream

class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        const val ACTION_STOP =
            "com.warung.autobag.capture.STOP"

        const val CAPTURE_FILE_NAME = "latest_capture.jpg"

        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 2001
        private const val REQUEST_STOP = 2002
        private const val REQUEST_OPEN_APP = 2003
        private const val IDLE_CAPTURE_INTERVAL_MS = 1000L

        @Volatile
        private var currentNotificationStatus =
            "Capture aktif • Otomatisasi belum dimulai"

        /**
         * Update the persistent foreground-service notification so the user
         * can inspect status and reopen MainActivity without using Recents.
         */
        fun updateStatus(context: Context, status: String) {
            if (!isRunning) return
            val safeStatus = status.take(140)
            if (safeStatus == currentNotificationStatus) return
            currentNotificationStatus = safeStatus

            val appContext = context.applicationContext
            ensureNotificationChannel(appContext)
            try {
                val manager = appContext.getSystemService(
                    NotificationManager::class.java
                )
                manager.notify(
                    NOTIFICATION_ID,
                    buildNotification(appContext, safeStatus)
                )
            } catch (exception: SecurityException) {
                Log.w(TAG, "Izin notifikasi belum diberikan", exception)
            } catch (exception: Exception) {
                Log.w(TAG, "Gagal memperbarui notifikasi status", exception)
            }
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Warung Auto Bag Assistant",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Status Screen Capture dan otomatisasi pesanan"
                }
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
        }

        private fun buildNotification(
            context: Context,
            status: String
        ): Notification {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openPendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_OPEN_APP,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val stopIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                context,
                REQUEST_STOP,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("Warung Auto Bag Assistant")
                .setContentText(status)
                .setStyle(Notification.BigTextStyle().bigText(status))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(openPendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(
                    Notification.Action.Builder(
                        android.R.drawable.ic_menu_view,
                        "Buka aplikasi",
                        openPendingIntent
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        android.R.drawable.ic_media_pause,
                        "Hentikan capture",
                        stopPendingIntent
                    ).build()
                )
                .build()
        }
        private const val AUTOMATION_CAPTURE_INTERVAL_MS = 120L

        @Volatile
        private var automationActive: Boolean = false

        @Volatile private var fastMinigameCapture: Boolean = false
        fun setFastMinigameCapture(active: Boolean) { fastMinigameCapture = active }

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Use a faster frame cadence only while an order is being executed. */
        fun setAutomationActive(active: Boolean) {
            automationActive = active
        }
    }

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var lastSavedAt = 0L
    private var lastForegroundCheckAt = 0L
    private var cachedGameForeground = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        if (projection != null) {
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        ) ?: Activity.RESULT_CANCELED

        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(
                EXTRA_RESULT_DATA,
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            currentNotificationStatus = "Capture aktif • Otomatisasi belum dimulai"
            ensureNotificationChannel(this)

            val notification = buildNotification(
                this,
                currentNotificationStatus
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }

            startCapture(resultCode, resultData)

        } catch (e: Exception) {
            Log.e(TAG, "Gagal memulai screen capture", e)
            stopCapture()
        }

        return START_NOT_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent
    ) {

        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        val mediaProjection = manager.getMediaProjection(
            resultCode,
            resultData
        )

        projection = mediaProjection

        workerThread = HandlerThread(
            "WarungScreenCapture"
        ).apply {
            start()
        }

        val handler = Handler(
            workerThread!!.looper
        )

        workerHandler = handler

        val callback = object : MediaProjection.Callback() {

            override fun onStop() {
                Log.i(TAG, "Screen capture dihentikan sistem")

                releaseResources(false)

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        projectionCallback = callback

        mediaProjection.registerCallback(
            callback,
            handler
        )

        val windowManager = getSystemService(
            WindowManager::class.java
        )

        val screenSize = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) {
            windowManager.maximumWindowMetrics.bounds
        } else {
            null
        }

        val metrics = resources.displayMetrics

        val width = screenSize?.width() ?: metrics.widthPixels
        val height = screenSize?.height() ?: metrics.heightPixels
        val density = resources.configuration.densityDpi

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader!!.setOnImageAvailableListener(
            { reader ->
                saveLatestFrame(reader)
            },
            handler
        )

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "WarungAutoBagScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler
        )

        if (virtualDisplay == null) {
            throw IllegalStateException(
                "VirtualDisplay gagal dibuat"
            )
        }

        isRunning = true

        Log.i(
            TAG,
            "Screen capture aktif: ${width}x${height}"
        )
    }

    private fun saveLatestFrame(reader: ImageReader) {
        val now = SystemClock.elapsedRealtime()
        val image = reader.acquireLatestImage() ?: return
        val acquiredAtWall = System.currentTimeMillis()

        // Capture hanya menyimpan frame game. Pemeriksaan foreground dibatasi
        // maksimal dua kali per detik agar tidak membebani callback tiap frame.
        if (now - lastForegroundCheckAt >= 500L) {
            lastForegroundCheckAt = now
            val accessibility = GestureAccessibilityService.instance
            cachedGameForeground = accessibility == null || accessibility.isTargetGameForeground()
        }

        val captureIntervalMs = if (automationActive) {
            if (fastMinigameCapture) 40L else AUTOMATION_CAPTURE_INTERVAL_MS
        } else {
            IDLE_CAPTURE_INTERVAL_MS
        }

        if (now - lastSavedAt < captureIntervalMs || !cachedGameForeground) {
            image.close()
            return
        }

        val outputFile = File(filesDir, CAPTURE_FILE_NAME)
        val temporaryFile = File(filesDir, "$CAPTURE_FILE_NAME.tmp")

        var paddedBitmap: Bitmap? = null
        var croppedBitmap: Bitmap? = null

        try {
            val width = image.width
            val height = image.height

            val plane = image.planes[0]
            val buffer = plane.buffer

            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride

            val padded = Bitmap.createBitmap(
                paddedWidth,
                height,
                Bitmap.Config.ARGB_8888
            )
            paddedBitmap = padded
            padded.copyPixelsFromBuffer(buffer)

            val cropped = Bitmap.createBitmap(
                padded,
                0,
                0,
                width,
                height
            )
            croppedBitmap = cropped

            FileOutputStream(temporaryFile).use { output ->
                val compressed = cropped.compress(
                    Bitmap.CompressFormat.JPEG,
                    80,
                    output
                )
                if (!compressed) {
                    throw IllegalStateException("JPEG capture gagal dikompresi.")
                }
                output.flush()
            }

            // Only the timing-sensitive NPC mode uses acquisition time, including JPEG latency.
            if (fastMinigameCapture) temporaryFile.setLastModified(acquiredAtWall)
            // Atomic rename prevents OCR from opening a partially written JPEG.
            Os.rename(temporaryFile.absolutePath, outputFile.absolutePath)
            lastSavedAt = now

            Log.d(TAG, "Gambar tersimpan: ${outputFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Gagal menyimpan gambar", e)

        } finally {
            if (croppedBitmap != null && croppedBitmap !== paddedBitmap && !croppedBitmap.isRecycled) {
                croppedBitmap.recycle()
            }
            if (paddedBitmap != null && !paddedBitmap.isRecycled) {
                paddedBitmap.recycle()
            }
            temporaryFile.delete()
            image.close()
        }
    }

    private fun stopCapture() {

        releaseResources(true)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseResources(stopProjection: Boolean) {

        isRunning = false
        automationActive = false
        fastMinigameCapture = false
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(
            null,
            null
        )

        imageReader?.close()
        imageReader = null

        val currentProjection = projection
        val currentCallback = projectionCallback

        projection = null
        projectionCallback = null

        if (stopProjection && currentProjection != null) {

            try {
                if (currentCallback != null) {
                    currentProjection.unregisterCallback(
                        currentCallback
                    )
                }

                currentProjection.stop()

            } catch (e: Exception) {
                Log.w(TAG, "Proses penghentian capture", e)
            }
        }

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
    }

    override fun onDestroy() {
        releaseResources(true)
        super.onDestroy()
    }
}
