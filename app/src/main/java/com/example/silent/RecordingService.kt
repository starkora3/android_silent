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
        // Timer-related actions (new)
        const val ACTION_TIMER_START = "com.example.silent.action.TIMER_START"
        const val ACTION_TIMER_CANCEL = "com.example.silent.action.TIMER_CANCEL"
        const val ACTION_TIMER_TICK = "com.example.silent.action.TIMER_TICK"
        const val ACTION_TIMER_STARTED = "com.example.silent.action.TIMER_STARTED"
        const val ACTION_TIMER_CANCELLED = "com.example.silent.action.TIMER_CANCELLED"
    }

    // Timer state
    private var timerHandler: Handler? = null
    private var timerRunnable: Runnable? = null
    private var timerRemainingStartSec: Int = 0
    private var timerRemainingRecSec: Int = 0
    private var timerPhase: Int = 0 // 0=idle,1=waiting,2=recording
    private var timerTriggeredRecording: Boolean = false
    private var timerRequestId: String? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: androidx.camera.video.Recording? = null

    private val binder = LocalBinder()
    private var startTime: Long = 0L

    // Explicit recording flag to avoid races between callbacks and explicit stop
    @Volatile
    private var isRecordingFlag = false

    // Track whether we've successfully called startForeground so we don't get ANR when
    // startForegroundService() was used to start the service.
    private var isForegroundStarted = false

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

        // Immediately attempt to enter foreground so that startForegroundService callers
        // do not trigger ForegroundServiceDidNotStartInTimeException on newer Android.
        // This is done early in onCreate (before onStartCommand heavy work) to satisfy
        // the platform's requirement that a service started via startForegroundService
        // must call startForeground promptly.
        try {
            if (!isForegroundStarted) {
                startForeground(NOTIF_ID, buildNotification("サービス初期化中"))
                isForegroundStarted = true
                Log.i("RecordingService", "Entered foreground in onCreate to avoid timing ANR")
            }
        } catch (se: SecurityException) {
            Log.e("RecordingService", "startForeground in onCreate failed due to missing notification/foreground permission", se)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    sendLocalBroadcast(Intent(ACTION_NOTIFICATION_PERMISSION_REQUIRED))
                } else {
                    sendLocalBroadcast(Intent(ACTION_FOREGROUND_PERMISSION_REQUIRED))
                }
            } catch (e: Exception) {
                Log.e("RecordingService", "failed to send permission-required broadcast from onCreate", e)
            }
            // Do not stop the service here; we'll allow callers to handle permission flow.
        } catch (e: Exception) {
            Log.e("RecordingService", "startForeground in onCreate failed", e)
        }

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
            sendLocalBroadcast(b)
        } catch (e: Exception) {
            Log.w("RecordingService", "failed to send onStartCalled broadcast", e)
        }
         when (intent?.action) {
             ACTION_TIMER_START -> {
                 // If startForegroundService was used by the caller, we MUST call startForeground promptly to avoid ANR.
                 try {
                     if (!isForegroundStarted) {
                         startForeground(NOTIF_ID, buildNotification("タイマー録画待機中"))
                         isForegroundStarted = true
                     }
                 } catch (se: SecurityException) {
                     Log.e("RecordingService", "startForeground failed due to missing notification or foreground permission (timer)", se)
                     try {
                         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                             sendLocalBroadcast(Intent(ACTION_NOTIFICATION_PERMISSION_REQUIRED))
                         } else {
                             sendLocalBroadcast(Intent(ACTION_FOREGROUND_PERMISSION_REQUIRED))
                         }
                     } catch (e: Exception) {
                         Log.e("RecordingService", "failed to send permission-required broadcast (timer)", e)
                     }
                     stopSelf()
                     return START_NOT_STICKY
                 } catch (re: Exception) {
                     Log.e("RecordingService", "startForeground failed (timer) with exception", re)
                     try { sendLocalBroadcast(Intent(ACTION_NOTIFICATION_PERMISSION_REQUIRED)) } catch (e: Exception) { Log.e("RecordingService", "failed to send notif-permission-required broadcast (timer)", e) }
                     stopSelf()
                     return START_NOT_STICKY
                 }

                 // start timer in service
                 val startSec = intent.getIntExtra("start_sec", 0)
                 val durSec = intent.getIntExtra("duration_sec", 0)
                 Log.i("RecordingService", "Received ACTION_TIMER_START start=$startSec dur=$durSec")
                 startTimer(startSec, durSec)
             }
             ACTION_TIMER_CANCEL -> {
                 Log.i("RecordingService", "Received ACTION_TIMER_CANCEL")
                 cancelTimer()
             }
             ACTION_START -> {
                // Removed incorrect runtime permission check for FOREGROUND_SERVICE
                // and removed the pre-check that aborted start when POST_NOTIFICATIONS
                // was not yet granted. Instead we always attempt to enter foreground
                // and handle SecurityException if notification permission is missing.

                // Try to enter foreground quickly with a minimal notification to satisfy system timing
                try {
                    if (!isForegroundStarted) {
                        startForeground(NOTIF_ID, buildNotification("録画を開始しています"))
                        isForegroundStarted = true
                    }
                } catch (se: SecurityException) {
                    Log.e("RecordingService", "startForeground failed due to missing notification or foreground permission", se)
                    // Determine likely missing permission and notify Activity accordingly.
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            // On Android 13+, missing POST_NOTIFICATIONS will cause startForeground to fail; ask Activity to request notification permission
                            sendLocalBroadcast(Intent(ACTION_NOTIFICATION_PERMISSION_REQUIRED))
                        } else {
                            // Otherwise, inform Activity that FOREGROUND_SERVICE permission may be required (some ROMs enforce additional checks)
                            sendLocalBroadcast(Intent(ACTION_FOREGROUND_PERMISSION_REQUIRED))
                        }
                    } catch (e: Exception) {
                        Log.e("RecordingService", "failed to send permission-required broadcast", e)
                    }
                     stopSelf()
                     return START_NOT_STICKY
                } catch (re: Exception) {
                    Log.e("RecordingService", "startForeground failed with exception", re)
                    try { sendLocalBroadcast(Intent(ACTION_NOTIFICATION_PERMISSION_REQUIRED)) } catch (e: Exception) { Log.e("RecordingService", "failed to send notif-permission-required broadcast", e) }
                    // include exception message in a broadcast for better debug on problematic Android builds
                    try {
                        val b = Intent(ACTION_NOTIFICATION_PERMISSION_REQUIRED)
                        b.putExtra("error", re.message)
                        sendLocalBroadcast(b)
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
                // clear flag when stopping foreground
                try { isForegroundStarted = false } catch (_: Exception) {}
                stopSelf()
            }
         }
         return START_STICKY
     }

    private fun startTimer(startSec: Int, durSec: Int) {
        // cancel any existing timer
        cancelTimer()
        if (durSec <= 0) {
            Log.w("RecordingService", "startTimer: invalid duration $durSec")
            return
        }
        timerHandler = Handler(Looper.getMainLooper())
        timerRemainingStartSec = startSec
        timerRemainingRecSec = durSec
        timerPhase = if (startSec > 0) 1 else 2
        timerTriggeredRecording = false

        // send initial tick for immediate UI feedback
        sendTimerTick()

        timerRunnable = object : Runnable {
            override fun run() {
                try {
                    if (timerPhase == 1) {
                        if (timerRemainingStartSec <= 0) {
                            // transition to recording
                            Log.i("RecordingService", "Timer: start reached 0, beginning recording")
                            timerPhase = 2
                            // start recording on main executor
                            ContextCompat.getMainExecutor(this@RecordingService).execute {
                                try {
                                    startRecording()
                                    timerTriggeredRecording = true
                                    // notify that timer-triggered recording started
                                    try { sendLocalBroadcast(Intent(ACTION_TIMER_STARTED)) } catch (e: Exception) { Log.w("RecordingService", "sendBroadcast ACTION_TIMER_STARTED failed", e) }
                                } catch (e: Exception) {
                                    Log.e("RecordingService", "startRecording from timer failed", e)
                                }
                            }
                            // start recording countdown after a short delay to allow recording to start
                            timerHandler?.postDelayed(this, 1000)
                            return
                        } else {
                            // countdown before start
                            sendTimerTick()
                            timerRemainingStartSec -= 1
                            timerHandler?.postDelayed(this, 1000)
                            return
                        }
                    } else if (timerPhase == 2) {
                        if (timerRemainingRecSec <= 0) {
                            Log.i("RecordingService", "Timer: recording duration finished, stopping")
                            // stop recording and notify
                            try { ContextCompat.getMainExecutor(this@RecordingService).execute { stopRecording() } } catch (e: Exception) { Log.e("RecordingService", "stopRecording error", e) }
                            timerPhase = 0
                            timerTriggeredRecording = false
                            try { sendLocalBroadcast(Intent(ACTION_TIMER_CANCELLED).apply { putExtra("reason", "finished") }) } catch (e: Exception) { Log.w("RecordingService", "sendBroadcast ACTION_TIMER_CANCELLED failed", e) }
                            return
                        } else {
                            sendTimerTick()
                            timerRemainingRecSec -= 1
                            timerHandler?.postDelayed(this, 1000)
                            return
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RecordingService", "timerRunnable error", e)
                }
            }
        }

        // start the runnable immediately (first tick already sent)
        timerHandler?.postDelayed(timerRunnable!!, 1000)
    }

    private fun sendTimerTick() {
        try {
            val intent = Intent(ACTION_TIMER_TICK)
            if (timerPhase == 1) {
                intent.putExtra("phase", "waiting")
                intent.putExtra("remaining_sec", timerRemainingStartSec)
            } else if (timerPhase == 2) {
                intent.putExtra("phase", "recording")
                intent.putExtra("remaining_sec", timerRemainingRecSec)
            } else {
                intent.putExtra("phase", "idle")
                intent.putExtra("remaining_sec", 0)
            }
            sendLocalBroadcast(intent)
        } catch (e: Exception) {
            Log.w("RecordingService", "sendTimerTick failed", e)
        }
    }

    private fun cancelTimer() {
        try {
            timerRunnable?.let { timerHandler?.removeCallbacks(it) }
        } catch (e: Exception) { /* ignore */ }
        timerRunnable = null
        timerHandler = null
        val wasRecordingTriggered = timerTriggeredRecording
        timerTriggeredRecording = false
        timerPhase = 0
        try { sendLocalBroadcast(Intent(ACTION_TIMER_CANCELLED).apply { putExtra("reason", "user_cancel") }) } catch (e: Exception) { Log.w("RecordingService", "sendBroadcast ACTION_TIMER_CANCELLED failed", e) }
        // If the recording was started by this timer, stop it as user requested
        if (wasRecordingTriggered && isRecordingFlag) {
            try { ContextCompat.getMainExecutor(this).execute { stopRecording() } } catch (e: Exception) { Log.e("RecordingService", "stopRecording on cancel error", e) }
        }
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
            try { sendLocalBroadcast(Intent(ACTION_CAMERA_PERMISSION_REQUIRED)) } catch (_: Exception) {}
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
                        try { sendLocalBroadcast(Intent(ACTION_RECORDING_STARTED)) } catch (e: Exception) { Log.w("RecordingService", "sendBroadcast started failed", e) }
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
                        // clear flag when foreground stopped
                        try { isForegroundStarted = false } catch (_: Exception) {}
                        val savedUri = event.outputResults.outputUri
                        if (savedUri != null) {
                            val b = Intent(ACTION_RECORDING_SAVED).apply { putExtra("video_uri", savedUri.toString()) }
                            try { sendLocalBroadcast(b) } catch (e: Exception) { Log.e("RecordingService", "sendBroadcast failed", e) }
                        }
                        synchronized(this@RecordingService) {
                            isRecordingFlag = false
                            currentRecording = null
                            startTime = 0L
                        }
                        try { sendLocalBroadcast(Intent(ACTION_RECORDING_STOPPED)) } catch (e: Exception) { Log.w("RecordingService", "sendBroadcast stopped failed", e) }
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
            try { sendLocalBroadcast(Intent(ACTION_RECORDING_STARTED)) } catch (e: Exception) { Log.w("RecordingService", "sendBroadcast started failed", e) }

        } catch (e: Exception) {
            Log.e("RecordingService", "Exception during prepare/start recording", e)
            // for better diagnostics on Android 15, include the stacktrace in a broadcast the Activity can surface
            try {
                val b = Intent(ACTION_NOTIFICATION_PERMISSION_REQUIRED)
                b.putExtra("error", e.message)
                sendLocalBroadcast(b)
            } catch (_: Exception) {}
            Toast.makeText(this, "録画の開始に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            // clear flag when foreground stopped
            try { isForegroundStarted = false } catch (_: Exception) {}
            stopSelf()
        }
    }

    private fun stopRecording() {
        try {
            // Ensure stopRecording runs on main thread because CameraX Recording.stop() must be called on main
            if (Looper.getMainLooper().thread != Thread.currentThread()) {
                ContextCompat.getMainExecutor(this).execute {
                    stopRecording()
                }
                return
            }

            // Perform stop with defensive checks and exception handling
            var hadRecording = false
            synchronized(this) {
                hadRecording = (currentRecording != null)
            }

            if (!hadRecording) {
                Log.w("RecordingService", "stopRecording called but no active recording")
                synchronized(this) {
                    isRecordingFlag = false
                    startTime = 0L
                    currentRecording = null
                }
                try { sendLocalBroadcast(Intent(ACTION_RECORDING_STOPPED)) } catch (e: Exception) { Log.w("RecordingService", "sendBroadcast stopped failed", e) }
                return
            }

            try {
                // call stop and catch any runtime exceptions from CameraX
                currentRecording?.stop()
            } catch (e: Exception) {
                Log.e("RecordingService", "error stopping recording", e)
            } finally {
                synchronized(this) {
                    isRecordingFlag = false
                    currentRecording = null
                    startTime = 0L
                }
                try { sendLocalBroadcast(Intent(ACTION_RECORDING_STOPPED)) } catch (e: Exception) { Log.w("RecordingService", "sendBroadcast stopped failed", e) }
            }
        } catch (t: Throwable) {
            // Catch everything to avoid crashing the service
            Log.e("RecordingService", "fatal error in stopRecording", t)
            try {
                synchronized(this) {
                    isRecordingFlag = false
                    currentRecording = null
                    startTime = 0L
                }
                try { sendLocalBroadcast(Intent(ACTION_RECORDING_STOPPED)) } catch (e: Exception) { Log.w("RecordingService", "sendBroadcast stopped failed", e) }
            } catch (ignored: Exception) { /* ignore secondary errors */ }
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

    // Helper that forces broadcast to this app package to improve delivery reliability
    private fun sendLocalBroadcast(intent: Intent) {
        try {
            intent.setPackage(packageName)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w("RecordingService", "sendLocalBroadcast failed", e)
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
