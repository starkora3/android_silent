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

class RecordingService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_START = "com.example.silent.action.START_RECORDING"
        const val ACTION_STOP = "com.example.silent.action.STOP_RECORDING"
        const val ACTION_RECORDING_SAVED = "com.example.silent.action.RECORDING_SAVED"
        const val ACTION_NOTIFICATION_PERMISSION_REQUIRED = "com.example.silent.action.NOTIF_PERMISSION_REQUIRED"
        // New: notify Activity that FOREGROUND_SERVICE permission appears to be missing
        const val ACTION_FOREGROUND_PERMISSION_REQUIRED = "com.example.silent.action.FOREGROUND_PERMISSION_REQUIRED"
        // Broadcasts to notify UI of recording state changes
        const val ACTION_RECORDING_STARTED = "com.example.silent.action.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.example.silent.action.RECORDING_STOPPED"
        const val CHANNEL_ID = "recording_channel"
        const val NOTIF_ID = 1001
        // New: notify Activity that CAMERA permission is required
        const val ACTION_CAMERA_PERMISSION_REQUIRED = "com.example.silent.action.CAMERA_PERMISSION_REQUIRED"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: androidx.camera.video.Recording? = null

    private val binder = LocalBinder()
    private var startTime: Long = 0L

    // Explicit recording flag to avoid races between callbacks and explicit stop
    @Volatile
    private var isRecordingFlag = false

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val bgExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        // initialize lifecycle
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        createNotificationChannel()

        // Initialize camera provider asynchronously but RUN binding on the main thread
        try {
            // Use the CameraX provided async mechanism and run binding on the main executor.
            val future = ProcessCameraProvider.getInstance(applicationContext)
            future.addListener({
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    Log.i("RecordingService", "CameraProvider initialized (main): $cameraProvider")
                    // move lifecycle to STARTED so bindToLifecycle works
                    lifecycleRegistry.currentState = Lifecycle.State.STARTED
                    // bind video use case on main thread
                    setupVideoCapture()
                } catch (e: Exception) {
                    Log.e("RecordingService", "Failed to init CameraProvider on main executor", e)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e("RecordingService", "Failed to request CameraProvider", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Use WARN to increase chance the log is visible on devices with info-level filtering
        Log.w("RecordingService", "onStartCommand received intent action=${intent?.action}")
        // Also notify Activity for immediate UI visibility that onStartCommand was entered
        try {
            val b = Intent("com.example.silent.action.SERVICE_ONSTARTCALLED").apply { putExtra("action", intent?.action) }
            sendBroadcast(b)
        } catch (e: Exception) {
            Log.w("RecordingService", "failed to send onStartCalled broadcast", e)
        }
         when (intent?.action) {
             ACTION_START -> {
                // Removed incorrect runtime permission check for FOREGROUND_SERVICE
                // and removed the pre-check that aborted start when POST_NOTIFICATIONS
                // was not yet granted. Instead we always attempt to enter foreground
                // and handle SecurityException if notification permission is missing.

                // Try to enter foreground quickly with a minimal notification to satisfy system timing
                try {
                    startForeground(NOTIF_ID, buildNotification("録画を開始しています"))
                } catch (se: SecurityException) {
                    Log.e("RecordingService", "startForeground failed due to missing notification or foreground permission", se)
                    // Determine likely missing permission and notify Activity accordingly.
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            // On Android 13+, missing POST_NOTIFICATIONS will cause startForeground to fail; ask Activity to request notification permission
                            sendBroadcast(Intent(ACTION_NOTIFICATION_PERMISSION_REQUIRED))
                        } else {
                            // Otherwise, inform Activity that FOREGROUND_SERVICE permission may be required (some ROMs enforce additional checks)
                            sendBroadcast(Intent(ACTION_FOREGROUND_PERMISSION_REQUIRED))
                        }
                    } catch (e: Exception) {
                        Log.e("RecordingService", "failed to send permission-required broadcast", e)
                    }
                     stopSelf()
                     return START_NOT_STICKY
                } catch (re: Exception) {
                    Log.e("RecordingService", "startForeground failed with exception", re)
                    try { sendBroadcast(Intent(ACTION_NOTIFICATION_PERMISSION_REQUIRED)) } catch (e: Exception) { Log.e("RecordingService", "failed to send notif-permission-required broadcast", e) }
                    // include exception message in a broadcast for better debug on problematic Android builds
                    try {
                        val b = Intent(ACTION_NOTIFICATION_PERMISSION_REQUIRED)
                        b.putExtra("error", re.message)
                        sendBroadcast(b)
                    } catch (e: Exception) { Log.e("RecordingService", "failed to send notif-permission-required broadcast with error detail", e) }
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Start actual recording on the main thread (CameraX/Lifecycle must be used from main)
                ContextCompat.getMainExecutor(this).execute {
                    try {
                        startRecording()
                    } catch (e: Exception) {
                        Log.e("RecordingService", "startRecording failed", e)
                        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> {
                // Ensure stopRecording executes on main thread because CameraX interactions must run there
                try {
                    ContextCompat.getMainExecutor(this).execute {
                        try { stopRecording() } catch (e: Exception) { Log.e("RecordingService", "stopRecording error", e) }
                    }
                } catch (e: Exception) {
                    Log.e("RecordingService", "failed to dispatch stopRecording to main", e)
                }
                try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                stopSelf()
            }
         }
         return START_STICKY
     }

    private fun setupVideoCapture() {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)
        try {
            if (cameraProvider == null) {
                Log.w("RecordingService", "setupVideoCapture: cameraProvider is null, cannot bind")
                return
            }
            // Ensure binding happens on the main thread; caller should already be main but guard here
            ContextCompat.getMainExecutor(this).execute {
                try {
                    cameraProvider?.unbindAll()
                    // bind using this service's lifecycle
                    Log.i("RecordingService", "Binding camera to lifecycle: $lifecycle")
                    cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, videoCapture)
                    Log.i("RecordingService", "Camera bound")
                } catch (e: Exception) {
                    Log.e("RecordingService", "setupVideoCapture bind failed on main executor", e)
                }
            }
        } catch (e: Exception) {
            Log.e("RecordingService", "setupVideoCapture bind failed", e)
        }
    }

    private fun startRecording() {
        val vc = videoCapture
        // permission checks: ensure CAMERA permission is granted before attempting to use CameraX
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e("RecordingService", "startRecording: CAMERA permission not granted")
            // Notify Activity that camera permission is required so it can request permissions
            try { sendBroadcast(Intent(ACTION_CAMERA_PERMISSION_REQUIRED)) } catch (_: Exception) {}
            stopSelf()
            return
        }

        if (vc == null) {
            Log.e("RecordingService", "startRecording: videoCapture is null (cameraProvider prepared: ${cameraProvider != null})")
            Toast.makeText(this, "カメラが準備できていません", Toast.LENGTH_SHORT).show()
            return
        }

        val name = "video_${System.currentTimeMillis()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyVideoApp")
            }
        }
        val outputOptions = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        // Prepare and start recording. Use returned Recording object to detect active state immediately.
        try {
            val prepared = vc.output
                .prepareRecording(this, outputOptions)
                .apply {
                    if (ContextCompat.checkSelfPermission(this@RecordingService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        withAudioEnabled()
                    }
                }

            // start returns Recording immediately; set state before callback to avoid races
            val recording = prepared.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is androidx.camera.video.VideoRecordEvent.Start -> {
                        // Start event: update notification
                        try {
                            val nm = NotificationManagerCompat.from(this@RecordingService)
                            nm.notify(NOTIF_ID, buildNotification("録画中"))
                        } catch (e: Exception) {
                            Log.w("RecordingService", "notify failed", e)
                        }
                        // Ensure UI knows recording started (if not already)
                        try { sendBroadcast(Intent(ACTION_RECORDING_STARTED)) } catch (e: Exception) { Log.w("RecordingService", "sendBroadcast started failed", e) }
                        synchronized(this@RecordingService) {
                            if (!isRecordingFlag) {
                                isRecordingFlag = true
                                startTime = System.currentTimeMillis()
                            }
                        }
                    }
                    is androidx.camera.video.VideoRecordEvent.Finalize -> {
                        // stop foreground when finished
                        try { stopForeground(STOP_FOREGROUND_DETACH) } catch (_: Exception) {}
                        val savedUri = event.outputResults.outputUri
                        if (savedUri != null) {
                            val b = Intent(ACTION_RECORDING_SAVED).apply { putExtra("video_uri", savedUri.toString()) }
                            try { sendBroadcast(b) } catch (e: Exception) { Log.e("RecordingService", "sendBroadcast failed", e) }
                        }
                        synchronized(this@RecordingService) {
                            isRecordingFlag = false
                            currentRecording = null
                            startTime = 0L
                        }
                        try { sendBroadcast(Intent(ACTION_RECORDING_STOPPED)) } catch (e: Exception) { Log.w("RecordingService", "sendBroadcast stopped failed", e) }
                    }
                }
            }

            // mark active recording and notify UI immediately to avoid races where system considers service not foreground
            synchronized(this) {
                currentRecording = recording
                isRecordingFlag = true
                startTime = System.currentTimeMillis()
            }
            // notify UI that recording started (best-effort)
            try { sendBroadcast(Intent(ACTION_RECORDING_STARTED)) } catch (e: Exception) { Log.w("RecordingService", "sendBroadcast started failed", e) }

        } catch (e: Exception) {
            Log.e("RecordingService", "Exception during prepare/start recording", e)
            // for better diagnostics on Android 15, include the stacktrace in a broadcast the Activity can surface
            try {
                val b = Intent(ACTION_NOTIFICATION_PERMISSION_REQUIRED)
                b.putExtra("error", e.message)
                sendBroadcast(b)
            } catch (_: Exception) {}
            Toast.makeText(this, "録画の開始に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf()
        }
    }

    private fun stopRecording() {
        try {
            synchronized(this) {
                isRecordingFlag = false
            }
            currentRecording?.stop()
        } catch (e: Exception) {
            Log.e("RecordingService", "error stopping recording", e)
        } finally {
            currentRecording = null
            startTime = 0L
            try { sendBroadcast(Intent(ACTION_RECORDING_STOPPED)) } catch (e: Exception) { Log.w("RecordingService", "sendBroadcast stopped failed", e) }
        }
    }

    fun getElapsedMs(): Long = if (isRecordingFlag && startTime > 0L) System.currentTimeMillis() - startTime else 0L

    // New helper: allow clients to query whether service is currently recording
    fun isRecording(): Boolean = isRecordingFlag

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .apply {
                // add stop action
                val stopIntent = Intent(this@RecordingService, RecordingService::class.java).apply { action = ACTION_STOP }
                val pStop = PendingIntent.getService(this@RecordingService, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                addAction(android.R.drawable.ic_media_pause, "停止", pStop)
            }
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "録画", NotificationManager.IMPORTANCE_LOW).apply {
                description = "録画サービスの通知"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(chan)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onDestroy() {
        super.onDestroy()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w("RecordingService", "onDestroy: unbindAll failed", e)
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        bgExecutor.shutdownNow()
    }
}
