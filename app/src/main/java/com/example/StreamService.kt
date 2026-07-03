package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Foreground service that owns the camera and the HTTP stream server.
 * Streaming keeps running when the app is closed or the screen dims;
 * Android shows an ongoing notification while it is active.
 */
class StreamService : LifecycleService() {

    companion object {
        @Volatile
        var instance: StreamService? = null

        const val CHANNEL_ID = "fonocam_stream"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.example.action.STOP"
    }

    var server: WebcamHttpServer? = null
        private set

    // live camera state - the UI polls these
    @Volatile var flashOn = false
    @Volatile var zoomRatio = 1.0f
    @Volatile var quality = 70
    @Volatile var backCamera = true
    @Volatile var isRecording = false
    @Volatile var lastRecordingName: String? = null

    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var targetResolution = android.util.Size(1280, 720)
    private var recQualityPref = "1080p"

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (server != null) return START_STICKY // already running

        val prefs = getSharedPreferences("fonocam_prefs", Context.MODE_PRIVATE)
        val port = prefs.getInt("port", 8080)
        recQualityPref = prefs.getString("rec_quality", "1080p") ?: "1080p"
        targetResolution = when (prefs.getString("resolution", "720p")) {
            "1080p" -> android.util.Size(1920, 1080)
            "480p" -> android.util.Size(854, 480)
            else -> android.util.Size(1280, 720)
        }

        startForegroundNotification()

        val srv = WebcamHttpServer(port = port) { action, value ->
            Handler(mainLooper).post {
                when (action) {
                    "toggle-flash" -> toggleFlash()
                    "switch-camera" -> switchCamera()
                    "zoom" -> value?.toFloatOrNull()?.let { setZoom(it) }
                    "quality" -> value?.toIntOrNull()?.let { applyQuality(it) }
                    "toggle-record" -> if (isRecording) stopRecording() else startRecording()
                }
            }
        }
        srv.isSecurityEnabled = false
        srv.deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        srv.frameQuality = quality
        srv.start()
        server = srv

        bindCamera()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Fonocam streaming", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, StreamService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val openPending = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Fonocam is streaming")
            .setContentText("Your phone is working as a webcam")
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, "Stop", stopPending)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                p.unbindAll()

                val selector =
                    if (backCamera) CameraSelector.DEFAULT_BACK_CAMERA
                    else CameraSelector.DEFAULT_FRONT_CAMERA

                // 16:9 lock so the stream is never square
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            targetResolution,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                var lastTime = 0L
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    try {
                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 33) {
                            val jpeg = proxy.toJpegBytes(quality, "None", applyRotation = false)
                            if (jpeg != null) {
                                server?.latestFrame = jpeg
                                server?.streamRotation = proxy.imageInfo.rotationDegrees
                                lastTime = now
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        proxy.close()
                    }
                }

                // recording quality follows the user's setting; falls back
                // to the best the device supports
                val recTarget = when (recQualityPref) {
                    "4K" -> Quality.UHD
                    "720p" -> Quality.HD
                    else -> Quality.FHD
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            recTarget, FallbackStrategy.higherQualityOrLowerThan(recTarget)
                        )
                    )
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                camera = try {
                    p.bindToLifecycle(this, selector, analysis, videoCapture!!)
                } catch (e: Exception) {
                    // some devices can't run analysis + video capture together
                    videoCapture = null
                    p.bindToLifecycle(this, selector, analysis)
                }
                camera?.cameraControl?.enableTorch(flashOn)
                camera?.cameraControl?.setZoomRatio(zoomRatio)
                server?.currentCamera = if (backCamera) "back" else "front"
            } catch (e: Exception) {
                e.printStackTrace()
                android.widget.Toast.makeText(
                    this, "Camera could not start. Close other camera apps and try again.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- controls (used by the app UI and by the PC over HTTP) ----
    fun toggleFlash() {
        flashOn = !flashOn
        camera?.cameraControl?.enableTorch(flashOn)
        server?.flashState = flashOn
    }

    fun switchCamera() {
        backCamera = !backCamera
        flashOn = false
        server?.flashState = false
        stopRecording()
        bindCamera()
    }

    fun setZoom(z: Float) {
        zoomRatio = z.coerceIn(1f, 8f)
        camera?.cameraControl?.setZoomRatio(zoomRatio)
        server?.zoomLevel = zoomRatio
    }

    fun applyQuality(q: Int) {
        quality = q
        server?.frameQuality = q
    }

    // ---- on-phone backup recording (saved to Movies/Fonocam) ----
    fun startRecording(): Boolean {
        val vc = videoCapture ?: return false
        if (activeRecording != null) return true
        return try {
            val name = SimpleDateFormat("'Fonocam_'yyyyMMdd_HHmmss'.mp4'", Locale.US)
                .format(System.currentTimeMillis())
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Fonocam")
                }
            }
            val options = MediaStoreOutputOptions.Builder(
                contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(values).build()

            var pending = vc.output.prepareRecording(this, options)
            // record sound too when the mic permission is granted
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                pending = pending.withAudioEnabled()
            }
            activeRecording = pending
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        lastRecordingName = name
                        activeRecording = null
                        isRecording = false
                        server?.isRecordingState = false
                    }
                }
            isRecording = true
            server?.isRecordingState = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        isRecording = false
        server?.isRecordingState = false
    }

    override fun onDestroy() {
        stopRecording()
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        server?.stop()
        server = null
        analysisExecutor.shutdown()
        instance = null
        super.onDestroy()
    }
}
