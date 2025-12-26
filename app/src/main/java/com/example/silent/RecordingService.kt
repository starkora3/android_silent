package com.example.silent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import java.util.concurrent.Executors

class RecordingService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_START = "com.example.silent.action.START_RECORDING"
        const val ACTION_STOP = "com.example.silent.action.STOP_RECORDING"
        const val ACTION_RECORDING_SAVED = "com.example.silent.action.RECORDING_SAVED"
        const val ACTION_RECORDING_STARTED = "com.example.silent.action.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.example.silent.action.RECORDING_STOPPED"
        const val ACTION_CAMERA_SWITCHED = "com.example.silent.action.CAMERA_SWITCHED"
        const val ACTION_SWITCH_CAMERA = "com.example.silent.action.SWITCH_CAMERA"
        const val ACTION_CAMERA_PERMISSION_REQUIRED = "com.example.silent.action.CAMERA_PERMISSION_REQUIRED"
        // Timer-related actions (used by Activity)
        const val ACTION_TIMER_START = "com.example.silent.action.TIMER_START"
        const val ACTION_TIMER_CANCEL = "com.example.silent.action.TIMER_CANCEL"
        const val ACTION_TIMER_TICK = "com.example.silent.action.TIMER_TICK"
        const val ACTION_TIMER_STARTED = "com.example.silent.action.TIMER_STARTED"
        const val ACTION_TIMER_CANCELLED = "com.example.silent.action.TIMER_CANCELLED"
        // Permission-request broadcasts (Activity listens to these)
        const val ACTION_NOTIFICATION_PERMISSION_REQUIRED = "com.example.silent.action.NOTIF_PERMISSION_REQUIRED"
        const val ACTION_FOREGROUND_PERMISSION_REQUIRED = "com.example.silent.action.FOREGROUND_PERMISSION_REQUIRED"
        const val ACTION_FOREGROUND_MIC_PERMISSION_REQUIRED = "com.example.silent.action.FOREGROUND_PERMISSION_MIC_REQUIRED"

        const val CHANNEL_ID = "recording_channel"
        const val NOTIF_ID = 1001
    }

    // --- state ---
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentRecording: androidx.camera.video.Recording? = null

    @Volatile
    private var isRecordingFlag = false
    private var recordingFinalizeDone = true
    private var lastOutputDisplayName: String? = null
    private var startTime: Long = 0L

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        createNotificationChannel()

        // initialize CameraProvider async
        try {
            val future = ProcessCameraProvider.getInstance(applicationContext)
            future.addListener({
                try {
                    cameraProvider = future.get()
                    lifecycleRegistry.currentState = Lifecycle.State.STARTED
                    setupVideoCapture()
                } catch (e: Exception) {
                    Log.e("RecordingService", "failed to init CameraProvider", e)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e("RecordingService", "request CameraProvider failed", e)
        }

        // Try to enter foreground early with a minimal notification to satisfy platform timing
        try {
            startForeground(NOTIF_ID, buildNotification("サービス起動中"))
        } catch (e: Exception) {
            Log.w("RecordingService", "startForeground in onCreate failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("RecordingService", "onStartCommand action=${intent?.action}")
        // Broadcast current camera facing so Activity can sync immediately
        try {
            val facing = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"
            val b = Intent(ACTION_CAMERA_SWITCHED).apply { putExtra("facing", facing) }
            sendLocalBroadcast(b)
        } catch (_: Exception) {}
        when (intent?.action) {
            ACTION_SWITCH_CAMERA -> {
                ContextCompat.getMainExecutor(this).execute { switchCamera() }
            }
            ACTION_START -> {
                ContextCompat.getMainExecutor(this).execute { startRecording() }
            }
            ACTION_STOP -> {
                ContextCompat.getMainExecutor(this).execute { stopRecording() }
            }
        }
        return START_STICKY
    }

    // --- Recording control ---
    private fun setupVideoCapture() {
        try {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(this, currentCameraSelector, videoCapture)
        } catch (e: Exception) {
            Log.w("RecordingService", "setupVideoCapture failed", e)
        }
    }

    fun switchCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendLocalBroadcast(Intent(ACTION_CAMERA_PERMISSION_REQUIRED))
            return
        }
        ContextCompat.getMainExecutor(this).execute {
            try {
                val newSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                // If recording, stop first (non-blocking); then rebind
                if (isRecordingFlag || currentRecording != null) {
                    stopRecording()
                    // Wait until finalize completes (recordingFinalizeDone == true) or timeout, then rebind.
                    // Polling on main looper to avoid background thread UI work.
                    val startWait = System.currentTimeMillis()
                    val timeoutMs = 5000L
                    val pollInterval = 200L
                    val handler = Handler(Looper.getMainLooper())
                    val checkRunnable = object : Runnable {
                        override fun run() {
                            try {
                                if (recordingFinalizeDone || System.currentTimeMillis() - startWait > timeoutMs) {
                                    currentCameraSelector = newSelector
                                    setupVideoCapture()
                                    val b = Intent(ACTION_CAMERA_SWITCHED).apply { putExtra("facing", if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front") }
                                    sendLocalBroadcast(b)
                                } else {
                                    handler.postDelayed(this, pollInterval)
                                }
                            } catch (e: Exception) {
                                Log.w("RecordingService", "switchCamera polling failed", e)
                                try { currentCameraSelector = newSelector; setupVideoCapture(); sendLocalBroadcast(Intent(ACTION_CAMERA_SWITCHED).apply { putExtra("facing", if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front") }) } catch (_: Exception) {}
                            }
                        }
                    }
                    handler.postDelayed(checkRunnable, pollInterval)
                } else {
                    currentCameraSelector = newSelector
                    setupVideoCapture()
                    val b = Intent(ACTION_CAMERA_SWITCHED).apply { putExtra("facing", if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front") }
                    sendLocalBroadcast(b)
                }
            } catch (e: Exception) {
                Log.e("RecordingService", "switchCamera failed", e)
            }
        }
    }

    private fun startRecording() {
        val vc = videoCapture
        Log.i("RecordingService", "startRecording: currentCameraSelector=${currentCameraSelector}, videoCapture_present=${vc != null}")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendLocalBroadcast(Intent(ACTION_CAMERA_PERMISSION_REQUIRED))
            stopSelf()
            return
        }
        if (vc == null) {
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

        try {
            val prepared = vc.output.prepareRecording(this, outputOptions).apply {
                if (ContextCompat.checkSelfPermission(this@RecordingService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) withAudioEnabled()
            }

            lastOutputDisplayName = name

            val recording = prepared.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is androidx.camera.video.VideoRecordEvent.Start -> {
                        try { NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification("録画中")) } catch (_: Exception) {}
                        sendLocalBroadcast(Intent(ACTION_RECORDING_STARTED))
                        synchronized(this) { isRecordingFlag = true; startTime = System.currentTimeMillis() }
                        recordingFinalizeDone = false
                        // currentRecording will be set after prepared.start() returns; avoid referencing 'recording' here
                    }
                    is androidx.camera.video.VideoRecordEvent.Finalize -> {
                        // Try to detect known error codes/messages from the finalize event more robustly
                        val detectedError = try { detectFinalizeError(event) } catch (_: Exception) { null }
                         // Stop foreground attachment
                         try { stopForeground(STOP_FOREGROUND_DETACH) } catch (_: Exception) {}
                         recordingFinalizeDone = true
                         val outUri = event.outputResults.outputUri
                        val evtStr = try { event.toString() } catch (_: Exception) { "" }

                         // Normalize URI: treat null or blank string as no URI
                         val outUriStr = try { outUri?.toString()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }

                        // If the finalize event explicitly indicates no valid data, prefer that diagnosis.
                        if (detectedError?.contains("ERROR_NO_VALID_DATA") == true || evtStr.contains("ERROR_NO_VALID_DATA")) {
                             val err = Intent(ACTION_RECORDING_SAVED).apply {
                                 putExtra("video_uri", "")
                                 putExtra("recovery_attempted", false)
                                 putExtra("error", "ERROR_NO_VALID_DATA")
                                 putExtra("finalize_event", evtStr)
                             }
                             Log.w("RecordingService", "Finalize: CameraX finalize indicates ERROR_NO_VALID_DATA; broadcasting error and skipping recovery: $evtStr")
                             sendLocalBroadcast(err)
                         } else if (outUriStr != null) {
                            val b = Intent(ACTION_RECORDING_SAVED).apply {
                                putExtra("video_uri", outUriStr)
                                putExtra("recovery_attempted", false)
                                putExtra("finalize_event", evtStr)
                            }
                            Log.i("RecordingService", "Finalize: CameraX provided outputUri: $outUriStr")
                            sendLocalBroadcast(b)
                        } else {
                            // provisional: send empty and attempt recovery in background
                            // Snapshot values to avoid races: the field startTime/currentRecording may be reset
                            val snapName = lastOutputDisplayName
                            val snapStart = if (startTime > 0L) startTime else System.currentTimeMillis()
                            sendLocalBroadcast(Intent(ACTION_RECORDING_SAVED).apply {
                                putExtra("video_uri", "")
                                putExtra("recovery_attempted", true)
                                putExtra("recovery_in_progress", true)
                                putExtra("finalize_event", evtStr)
                            })
                            bgExecutor.execute { recoverMediaStoreEntryAndBroadcast(snapName, snapStart) }
                         }
                         synchronized(this) { isRecordingFlag = false; currentRecording = null; startTime = 0L }
                         sendLocalBroadcast(Intent(ACTION_RECORDING_STOPPED))
                     }
                 }
             }

            // store reference
            synchronized(this) { currentRecording = recording; isRecordingFlag = true; startTime = System.currentTimeMillis() }
            sendLocalBroadcast(Intent(ACTION_RECORDING_STARTED))
        } catch (e: Exception) {
            Log.e("RecordingService", "startRecording failed", e)
            try { sendLocalBroadcast(Intent(ACTION_RECORDING_STOPPED)) } catch (_: Exception) {}
            Toast.makeText(this, "録画の開始に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun recoverMediaStoreEntryAndBroadcast(name: String?, startTime: Long) {
        try {
            // Retry strategy: try up to N attempts with small delay to allow MediaStore to update.
            val maxAttempts = 6
            val delayMs = 2000L
            var foundUri: Uri? = null

            for (attempt in 1..maxAttempts) {
                try {
                    Log.i("RecordingService", "recover attempt $attempt/$maxAttempts for name=$name startTime=$startTime")

                    // 1) exact match by DISPLAY_NAME
                    if (!name.isNullOrEmpty()) {
                        contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID), "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name), "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                            if (c.moveToFirst()) {
                                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                                foundUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                            }
                        }
                    }

                    // 2) LIKE search
                    if (foundUri == null && !name.isNullOrEmpty()) {
                        contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID), "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                            if (c.moveToFirst()) {
                                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                                foundUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                            }
                        }
                    }

                    // 3) time-window search (window grows slightly with attempts)
                    if (foundUri == null) {
                        val expandSec = attempt * 10L // expand window gradually
                        val windowStart = if (startTime > 0L) (startTime / 1000L - 30L - expandSec) else (System.currentTimeMillis() / 1000L - 60L - expandSec)
                        val windowEnd = (System.currentTimeMillis() / 1000L + 10L + expandSec)
                        contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED), "${MediaStore.Video.Media.DATE_ADDED} >= ? AND ${MediaStore.Video.Media.DATE_ADDED} <= ?", arrayOf(windowStart.toString(), windowEnd.toString()), "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                            if (c.moveToFirst()) {
                                var bestId: Long? = null
                                var bestSize = -1L
                                do {
                                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                                    val sizeIdx = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                                    val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                                    if (size > bestSize) { bestSize = size; bestId = id }
                                } while (c.moveToNext())
                                if (bestId != null) foundUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, bestId.toString())
                            }
                        }
                    }

                    if (foundUri != null) {
                        Log.i("RecordingService", "recover success on attempt $attempt: $foundUri")
                        val fin = Intent(ACTION_RECORDING_SAVED).apply {
                            putExtra("video_uri", foundUri.toString())
                            putExtra("recovery_attempted", true)
                            putExtra("recovery_attempts", attempt)
                        }
                        sendLocalBroadcast(fin)
                        return
                    }
                } catch (e: Exception) {
                    Log.w("RecordingService", "recover attempt $attempt failed", e)
                }

                // wait before next attempt unless it's the last
                if (attempt < maxAttempts) {
                    try { Thread.sleep(delayMs) } catch (_: InterruptedException) { /* ignore */ }
                }
            }

            // after attempts, report failure
            Log.w("RecordingService", "recover failed after $maxAttempts attempts for name=$name")
            // Final fallback: try a broad recent search for files named like our prefix (video_*) and pick the largest
            try {
                val nowSec = System.currentTimeMillis() / 1000L
                val windowStart = nowSec - 120L // last 2 minutes
                contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE), "${MediaStore.Video.Media.DATE_ADDED} >= ?", arrayOf(windowStart.toString()), "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                    var bestId: Long? = null
                    var bestSize = -1L
                    if (c.moveToFirst()) {
                        do {
                            try {
                                val nameCol = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                                val n = if (nameCol >= 0) c.getString(nameCol) ?: "" else ""
                                if (!n.startsWith("video_")) continue
                                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                                val sizeIdx = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                                val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                                if (size > bestSize) { bestSize = size; bestId = id }
                            } catch (_: Exception) { /* ignore row errors */ }
                        } while (c.moveToNext())
                    }
                    if (bestId != null) {
                        val foundUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, bestId.toString())
                        Log.i("RecordingService", "recover fallback found recent video: $foundUri size=$bestSize")
                        val fin = Intent(ACTION_RECORDING_SAVED).apply {
                            putExtra("video_uri", foundUri.toString())
                            putExtra("recovery_attempted", true)
                            putExtra("recovery_attempts", maxAttempts + 1)
                            putExtra("recovery_fallback", true)
                        }
                        sendLocalBroadcast(fin)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w("RecordingService", "fallback recent search failed", e)
            }
             val fin = Intent(ACTION_RECORDING_SAVED).apply {
                 putExtra("video_uri", "")
                 putExtra("recovery_attempted", true)
                 putExtra("recovery_attempts", maxAttempts)
                 putExtra("error", "no_output_uri_after_retries")
             }
             sendLocalBroadcast(fin)
        } catch (e: Exception) {
            Log.w("RecordingService", "recoverMediaStoreEntryAndBroadcast failed", e)
            try { sendLocalBroadcast(Intent(ACTION_RECORDING_SAVED).apply { putExtra("video_uri", ""); putExtra("recovery_attempted", true); putExtra("error", "exception_during_recovery") }) } catch (_: Exception) {}
        }
    }

    // --- Helpers exposed to Activity ---
    fun getElapsedMs(): Long = if (isRecordingFlag) System.currentTimeMillis() - startTime else 0L
    fun isRecording(): Boolean = isRecordingFlag
    fun getCurrentLensFacing(): Int = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT

    // --- Broadcast / Notification helpers ---
    private fun sendLocalBroadcast(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w("RecordingService", "sendLocalBroadcast failed", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(CHANNEL_ID, "Recording Service", NotificationManager.IMPORTANCE_LOW).apply { description = "Channel for recording service" }
                val nm = getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(channel)
            } catch (e: Exception) { Log.w("RecordingService", "createNotificationChannel failed", e) }
        }
    }

    private fun buildNotification(contentText: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Silent Recorder")
        .setContentText(contentText)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .build()

    // --- Service wiring ---
    override fun onBind(intent: Intent?): IBinder? = binder
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override fun onDestroy() {
        super.onDestroy()
        try { cameraProvider?.unbindAll() } catch (e: Exception) { Log.w("RecordingService", "onDestroy unbindAll failed", e) }
        try { stopRecording() } catch (e: Exception) { Log.w("RecordingService", "onDestroy stopRecording failed", e) }
    }

    private fun stopRecording() {
        if (!isRecordingFlag && currentRecording == null) return
        try { NotificationManagerCompat.from(this).cancel(NOTIF_ID) } catch (_: Exception) {}
        try { currentRecording?.stop() } catch (e: Exception) { Log.w("RecordingService", "stopRecording failed", e) }
    }

    private fun detectFinalizeError(evt: androidx.camera.video.VideoRecordEvent.Finalize): String? {
        try {
            // 1) direct toString
            val s = try { evt.toString() } catch (_: Exception) { "" }
            if (!s.isNullOrEmpty() && s.contains("ERROR_NO_VALID_DATA")) return "ERROR_NO_VALID_DATA"

            // 2) look for common getter methods
            val cls = evt.javaClass
            val methodNames = arrayOf("getError", "getErrorCode", "getErrorType", "error")
            for (mn in methodNames) {
                try {
                    val m = cls.methods.firstOrNull { it.name.equals(mn, ignoreCase = true) }
                    if (m != null) {
                        val v = try { m.invoke(evt) } catch (_: Exception) { null }
                        if (v != null) {
                            val vs = v.toString()
                            if (vs.contains("ERROR_NO_VALID_DATA")) return "ERROR_NO_VALID_DATA"
                        }
                    }
                } catch (_: Exception) { /* ignore */ }
            }

            // 3) inspect fields for anything mentioning ERROR_NO_VALID_DATA
            var c: Class<*>? = cls
            while (c != null) {
                try {
                    for (f in c.declaredFields) {
                        try {
                            f.isAccessible = true
                            val v = f.get(evt) ?: continue
                            val vs = v.toString()
                            if (vs.contains("ERROR_NO_VALID_DATA")) return "ERROR_NO_VALID_DATA"
                        } catch (_: Exception) { /* ignore field access errors */ }
                    }
                } catch (_: Exception) { }
                c = c.superclass
            }
        } catch (_: Exception) {
            // fallthrough
        }
        return null
    }
}
