package com.example.silent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Lifecycle
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.QualitySelector
import androidx.camera.video.Quality
import androidx.camera.video.MediaStoreOutputOptions
import android.content.ContentValues
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import android.util.Log
import android.content.pm.PackageManager
import android.Manifest
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import androidx.camera.core.Camera
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.concurrent.ExecutorService
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import android.content.pm.ServiceInfo
import androidx.camera.video.Recording

class RecordingService : LifecycleService() {

    private val binder = LocalBinder()
    private var isRecording = false
    private var recordingStartTime: Long = 0L
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var activeRecording: Recording? = null
    private var cameraReady = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private lateinit var cameraExecutor: ExecutorService

    // Timer-related state
    private var timerJob: Job? = null
    private var timerDurationSec = 0
    private var timerStartSec = 0

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: ""
        // Send a diagnostic broadcast so the Activity can log that onStartCommand was entered
        try { sendBroadcast(Intent("com.example.silent.action.SERVICE_ONSTARTCALLED").putExtra("action", action).setPackage(packageName)) } catch (_: Exception) {}

        when (action) {
            ACTION_START -> {
                val lensFacing = intent?.getIntExtra(EXTRA_CAMERA_LENS_FACING, CameraSelector.LENS_FACING_BACK) ?: CameraSelector.LENS_FACING_BACK
                startRecording(lensFacing)
            }
            ACTION_STOP -> {
                stopRecording()
            }
            ACTION_TIMER_START -> {
                val startSec = intent?.getIntExtra("start_sec", 0) ?: 0
                val durSec = intent?.getIntExtra("duration_sec", 0) ?: 0
                val lensFacing = intent?.getIntExtra(EXTRA_CAMERA_LENS_FACING, CameraSelector.LENS_FACING_BACK) ?: CameraSelector.LENS_FACING_BACK
                startTimer(startSec, durSec, lensFacing)
            }
            ACTION_TIMER_CANCEL -> {
                cancelTimer()
            }
            ACTION_TOGGLE_CAMERA -> {
                val lensFacing = intent?.getIntExtra(EXTRA_CAMERA_LENS_FACING, -1) ?: -1
                if (lensFacing != -1) {
                    toggleCamera(lensFacing)
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification(text: String) {
        // On Android 13+, ensure we have permission to post notifications before trying
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // Send a broadcast to the Activity to request permission
                sendBroadcast(Intent(ACTION_NOTIFICATION_PERMISSION_REQUIRED).setPackage(packageName))
                // Stop the service to avoid ANR if permission is not granted
                stopSelf()
                return
            }
        }
        // On Android 12+, ensure we have foreground service permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED) {
                    sendBroadcast(Intent(ACTION_FOREGROUND_PERMISSION_REQUIRED).setPackage(packageName))
                    stopSelf()
                    return
                }
            } catch (e: Exception) {
                // some ROMs might throw SecurityException here
                sendBroadcast(Intent(ACTION_FOREGROUND_PERMISSION_REQUIRED).setPackage(packageName))
                stopSelf()
                return
            }
        }
        // On Android 15+, ensure we have microphone foreground service permission
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                if (ContextCompat.checkSelfPermission(this, "android.permission.FOREGROUND_SERVICE_MICROPHONE") != PackageManager.PERMISSION_GRANTED) {
                    sendBroadcast(Intent(ACTION_FOREGROUND_MIC_PERMISSION_REQUIRED).setPackage(packageName))
                    stopSelf()
                    return
                }
            } catch (e: Exception) {
                sendBroadcast(Intent(ACTION_FOREGROUND_MIC_PERMISSION_REQUIRED).setPackage(packageName))
                stopSelf()
                return
            }
        }


        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            // Log and try to recover if possible
            Log.e(TAG, "startForeground failed", e)
            // Fallback for older devices or ROMs with stricter background start rules
            try {
                startForeground(1, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground fallback failed", e2)
                // If all attempts fail, notify the user and stop the service
                sendBroadcast(Intent(ACTION_FOREGROUND_PERMISSION_REQUIRED).setPackage(packageName))
                stopSelf()
            }
        }
    }

    private fun setupCamera(lensFacing: Int) {
        cameraReady = false
        currentCameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis?.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                if (!isRecording) {
                    imageProxy.close()
                    return@Analyzer
                }
                // 1秒に1回程度の頻度で画像を処理
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastPreviewUpdateTime > 1000) {
                    lastPreviewUpdateTime = currentTime
                    processImage(imageProxy)
                }
                imageProxy.close()
            })

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, currentCameraSelector, videoCapture, imageAnalysis)
                cameraReady = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private var lastPreviewUpdateTime = 0L

    private fun processImage(imageProxy: ImageProxy) {
        val bitmap = toBitmapFromYuv(imageProxy)
        if (bitmap != null) {
            // 画像を回転させる
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            }
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

            // ファイルに保存
            val file = File(cacheDir, "preview.jpg")
            try {
                FileOutputStream(file).use { out ->
                    rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                // MainActivityに通知
                val intent = Intent(ACTION_PREVIEW_UPDATED).apply {
                    putExtra("preview_path", file.absolutePath)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save preview image", e)
            }
        }
    }

    private fun toBitmapFromYuv(imageProxy: ImageProxy): Bitmap? {
        if (imageProxy.format != android.graphics.ImageFormat.YUV_420_888) {
            Log.e(TAG, "Unsupported image format: ${imageProxy.format}")
            return null
        }

        val yBuffer = imageProxy.planes[0].buffer.apply { rewind() }
        val uBuffer = imageProxy.planes[1].buffer.apply { rewind() }
        val vBuffer = imageProxy.planes[2].buffer.apply { rewind() }

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // U and V are swapped in NV21 format compared to I420 format
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)


        val yuvImage = YuvImage(nv21, android.graphics.ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 80, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun startRecording(lensFacing: Int) {
        // First, ensure camera is set up with the correct lens facing
        setupCamera(lensFacing)

        // Wait for camera to be ready
        lifecycleScope.launch {
            while (!cameraReady) {
                delay(100)
            }
            // Now start the actual recording
            val vc = videoCapture
            if (vc != null) {
                startRecordingInternal(vc)
            } else {
                Log.e(TAG, "VideoCapture is null after camera is ready.")
                Toast.makeText(applicationContext, "カメラの初期化に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRecordingInternal(vc: VideoCapture<Recorder>) {
        if (isRecording) return
        isRecording = true
        recordingStartTime = System.currentTimeMillis()

        startForegroundNotification("録画を開始しました")

        val name = "video_${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // This should ideally be handled in the Activity, but as a fallback:
            Toast.makeText(this, "マイクの権限がありません", Toast.LENGTH_SHORT).show()
            isRecording = false
            stopSelf()
            return
        }

        activeRecording = vc.output
            .prepareRecording(this, mediaStoreOutput)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        sendBroadcast(Intent(ACTION_RECORDING_STARTED).setPackage(packageName))
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!event.hasError()) {
                            val uri = event.outputResults.outputUri
                            val intent = Intent(ACTION_RECORDING_SAVED).apply {
                                putExtra("video_uri", uri)
                                setPackage(packageName)
                            }
                            sendBroadcast(intent)
                        } else {
                            Log.e(TAG, "Video recording failed: Error: ${event.error}, Cause: ${event.cause?.message}")
                        }
                        stopRecording()
                    }
                }
            }
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        isRecording = false
        recordingStartTime = 0L
        stopForeground(true)
        stopSelf()
        sendBroadcast(Intent(ACTION_RECORDING_STOPPED).setPackage(packageName))
        cameraProvider?.unbindAll()
    }

    private fun toggleCamera(lensFacing: Int) {
        if (isRecording) {
            // Stop the current recording
            activeRecording?.stop()
            activeRecording = null

            // Unbind all use cases
            cameraProvider?.unbindAll()

            // Set up the new camera
            setupCamera(lensFacing)

            // Start a new recording after a short delay to allow the camera to re-initialize
            lifecycleScope.launch {
                delay(1000) // Adjust delay as needed
                val vc = videoCapture
                if (vc != null) {
                    startRecordingInternal(vc)
                } else {
                    Log.e(TAG, "Failed to restart recording after camera toggle.")
                }
            }
        } else {
            // If not recording, just update the selector for the next recording
            setupCamera(lensFacing)
        }
    }

    private fun startTimer(startSec: Int, durSec: Int, lensFacing: Int) {
        cancelTimer()
        timerStartSec = startSec
        timerDurationSec = durSec
        timerJob = CoroutineScope(Dispatchers.Main).launch {
            // Countdown to start
            for (i in startSec downTo 1) {
                val intent = Intent(ACTION_TIMER_TICK).apply {
                    putExtra("remaining_sec", i)
                    putExtra("phase", "waiting")
                    setPackage(packageName)
                }
                sendBroadcast(intent)
                startForegroundNotification("録画開始まで: $i 秒")
                delay(1000)
            }

            // Start recording
            startRecording(lensFacing)
            sendBroadcast(Intent(ACTION_TIMER_STARTED).setPackage(packageName))

            // Countdown for duration
            for (i in durSec downTo 1) {
                val intent = Intent(ACTION_TIMER_TICK).apply {
                    putExtra("remaining_sec", i)
                    putExtra("phase", "recording")
                    setPackage(packageName)
                }
                sendBroadcast(intent)
                startForegroundNotification("録画中: 残り $i 秒")
                delay(1000)
            }

            // Stop recording
            stopRecording()
        }
    }

    private fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        stopForeground(true)
        sendBroadcast(Intent(ACTION_TIMER_CANCELLED).setPackage(packageName))
    }

    fun isRecording(): Boolean = isRecording
    fun getElapsedMs(): Long = if (isRecording) System.currentTimeMillis() - recordingStartTime else 0L

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        cameraExecutor.shutdown()
    }

    companion object {
        const val ACTION_START = "com.example.silent.action.START_RECORDING"
        const val ACTION_STOP = "com.example.silent.action.STOP_RECORDING"
        const val ACTION_RECORDING_STARTED = "com.example.silent.action.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.example.silent.action.RECORDING_STOPPED"
        const val ACTION_RECORDING_SAVED = "com.example.silent.action.RECORDING_SAVED"
        const val ACTION_NOTIFICATION_PERMISSION_REQUIRED = "com.example.silent.action.NOTIFICATION_PERMISSION_REQUIRED"
        const val ACTION_FOREGROUND_PERMISSION_REQUIRED = "com.example.silent.action.FOREGROUND_PERMISSION_REQUIRED"
        const val ACTION_FOREGROUND_MIC_PERMISSION_REQUIRED = "com.example.silent.action.FOREGROUND_MIC_PERMISSION_REQUIRED"
        const val ACTION_CAMERA_PERMISSION_REQUIRED = "com.example.silent.action.CAMERA_PERMISSION_REQUIRED"
        const val ACTION_TIMER_START = "com.example.silent.action.TIMER_START"
        const val ACTION_TIMER_CANCEL = "com.example.silent.action.TIMER_CANCEL"
        const val ACTION_TIMER_TICK = "com.example.silent.action.TIMER_TICK"
        const val ACTION_TIMER_STARTED = "com.example.silent.action.TIMER_STARTED"
        const val ACTION_TIMER_CANCELLED = "com.example.silent.action.TIMER_CANCELLED"
        const val ACTION_TOGGLE_CAMERA = "com.example.silent.action.TOGGLE_CAMERA"
        const val ACTION_PREVIEW_UPDATED = "com.example.silent.action.PREVIEW_UPDATED"
        const val EXTRA_CAMERA_LENS_FACING = "com.example.silent.extra.CAMERA_LENS_FACING"
        private const val CHANNEL_ID = "recording_channel"
        private const val TAG = "RecordingService"
    }
}
