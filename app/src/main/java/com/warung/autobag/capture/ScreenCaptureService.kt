
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
        private const val IDLE_CAPTURE_INTERVAL_MS = 1000L
        private const val AUTOMATION_CAPTURE_INTERVAL_MS = 180L

        @Volatile
        private var automationActive: Boolean = false

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
            createNotificationChannel()

            val notification = buildNotification()

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

        // Capture hanya menyimpan frame game. Pemeriksaan foreground dibatasi
        // maksimal dua kali per detik agar tidak membebani callback tiap frame.
        if (now - lastForegroundCheckAt >= 500L) {
            lastForegroundCheckAt = now
            val accessibility = GestureAccessibilityService.instance
            cachedGameForeground = accessibility == null || accessibility.isTargetGameForeground()
        }

        val captureIntervalMs = if (automationActive) {
            AUTOMATION_CAPTURE_INTERVAL_MS
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

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Warung Auto Bag Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Menampilkan status pengambilan layar"
            }

            val manager = getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {

        val stopIntent = Intent(
            this,
            ScreenCaptureService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle("Warung Auto Bag Assistant")
            .setContentText(
                "Screen Capture sedang berjalan"
            )
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "Hentikan",
                    stopPendingIntent
                ).build()
            )
            .build()
    }

    private fun stopCapture() {

        releaseResources(true)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseResources(stopProjection: Boolean) {

        isRunning = false
        automationActive = false
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
