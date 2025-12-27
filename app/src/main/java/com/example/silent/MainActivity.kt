package com.example.silent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.QualitySelector
import androidx.camera.video.Quality
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.snackbar.Snackbar
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.Manifest
import android.content.pm.PackageManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.method.LinkMovementMethod
import android.widget.Toast
import android.widget.EditText
import android.text.InputType
import android.widget.LinearLayout
import android.view.ViewGroup.LayoutParams
import android.graphics.BitmapFactory

class MainActivity : AppCompatActivity() {

    private lateinit var recordButton: Button
    private lateinit var audioStatus: TextView
    private lateinit var rootLayout: ViewGroup
    private lateinit var viewListButton: View
    private lateinit var recordingTimer: TextView
    private lateinit var recordingState: TextView
    private lateinit var logView: TextView
    private lateinit var toggleCameraButton: Button
    private lateinit var togglePreviewButton: Button
    private lateinit var previewImageView: ImageView

    // Timer UI elements
    private var timerStartInput: EditText? = null
    private var timerDurationInput: EditText? = null
    private var timerRecordButton: Button? = null
    // 保存しておいたボタンの元のラベル（元に戻すため）
    private var timerButtonOriginalText: CharSequence? = null

    // SharedPreferences keys for timer settings
    private val PREFS_NAME = "silent_prefs"
    private val KEY_TIMER_START_SEC = "timer_start_sec"
    private val KEY_TIMER_DURATION_SEC = "timer_duration_sec"

    // Android 15+ required permission for starting a foreground service with microphone type
    private val MIC_FGS_PERMISSION = "android.permission.FOREGROUND_SERVICE_MICROPHONE"

    private fun saveTimerPrefs(startSec: Int, durSec: Int) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_TIMER_START_SEC, startSec).putInt(KEY_TIMER_DURATION_SEC, durSec).apply()
            appendLog("Timer prefs saved: start=$startSec, dur=$durSec")
        } catch (e: Exception) {
            appendLog("Failed to save timer prefs: ${e.message}")
        }
    }

    private fun loadTimerPrefs(): Pair<Int, Int> {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val s = prefs.getInt(KEY_TIMER_START_SEC, 0)
            val d = prefs.getInt(KEY_TIMER_DURATION_SEC, 0)
            Pair(s, d)
        } catch (e: Exception) {
            appendLog("Failed to load timer prefs: ${e.message}")
            Pair(0, 0)
        }
    }

    private var recordingService: RecordingService? = null
    private var bound = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            val elapsed = recordingService?.getElapsedMs() ?: 0L
            recordingState.text = formatMs(elapsed)
            if (bound && elapsed > 0L) mainHandler.postDelayed(this, 500)
        }
    }

    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // Timer state
    private var timerHandler: Handler = Handler(Looper.getMainLooper())
    private var timerCountdownRunnable: Runnable? = null
    private var timerRemainingStartSec = 0
    private var timerRemainingRecSec = 0
    private var timerPhase = 0 // 0 = idle, 1 = waiting to start, 2 = recording countdown
    private var screenKeepOnSet = false // Track if FLAG_KEEP_SCREEN_ON is currently set

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, serviceIBinder: android.os.IBinder) {
            val local = serviceIBinder as? RecordingService.LocalBinder
            recordingService = local?.getService()
            bound = true
            // Update UI based on whether the service is currently recording
            runOnUiThread {
                val isRec = recordingService?.isRecording() == true
                recordButton.text = if (isRec) getString(R.string.record_stop) else getString(R.string.record_start)
                if (isRec) {
                    mainHandler.post(updateRunnable)
                } else {
                    mainHandler.removeCallbacks(updateRunnable)
                    recordingState.text = formatMs(0L)
                }
                appendLog("Service connected. isRecording=$isRec")
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName) {
            bound = false
            recordingService = null
            mainHandler.removeCallbacks(updateRunnable)
            runOnUiThread {
                recordButton.text = getString(R.string.record_start)
                recordingState.text = formatMs(0L)
                appendLog("Service disconnected")
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.CAMERA] == true && perms[Manifest.permission.RECORD_AUDIO] == true
        appendLog("Camera & Audio permission result: $granted")
        if (!granted) {
            Toast.makeText(this, "カメラとマイクの権限が必要です", Toast.LENGTH_LONG).show()
        }
    }

    // Shared pending timer start state when permission requests are needed
    private var pendingTimerStartSec: Int? = null
    private var pendingTimerDurSec: Int? = null
    private var pendingTimerRequested = false

    // Notification permission launcher (Android 13+)
    private var pendingStartAfterNotifPerm = false // 保留フラグ: ユーザーが録画開始を押して通知許可が必要な場合に true
    private val notifPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        appendLog("Notification permission result: $granted")
        if (!granted) {
            // show snackbar to open settings
            val parent = findViewById<View>(android.R.id.content)
            Snackbar.make(parent, "通知を許可すると録画の通知を受け取れます", Snackbar.LENGTH_INDEFINITE)
                .setAction("設定") {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) }
                    startActivity(intent)
                }
                .show()
            // clear pending flags since user denied
            pendingStartAfterNotifPerm = false
            // also clear pending timer request
            pendingTimerRequested = false
            pendingTimerStartSec = null
            pendingTimerDurSec = null
        } else {
            // granted: if there was a pending user intent to start recording, start now
            if (pendingStartAfterNotifPerm) {
                pendingStartAfterNotifPerm = false
                // small delay to ensure permission state is settled
                mainHandler.postDelayed({
                    try { startRecordingInternal() } catch (e: Exception) { android.util.Log.w("MainActivity", "startRecording after notif perm failed", e) }
                }, 150)
            }
            // If there was a pending timer start, start it now
            if (pendingTimerRequested) {
                val s = pendingTimerStartSec ?: 0
                val d = pendingTimerDurSec ?: 0
                pendingTimerRequested = false
                pendingTimerStartSec = null
                pendingTimerDurSec = null
                mainHandler.postDelayed({
                    try { startTimerRecording(s, d) } catch (e: Exception) { android.util.Log.w("MainActivity", "startTimer after notif perm failed", e) }
                }, 150)
            }
        }
    }

    // Foreground service permission launcher (some devices/ROMs enforce runtime checks). Only request on Android 12+ if needed.
    private var pendingStartAfterFgsPerm = false
    private val fgsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        appendLog("Foreground-service permission result: $granted")
        if (!granted) {
            // show guidance to open settings
            val parent = findViewById<View>(android.R.id.content)
            Snackbar.make(parent, "アプリにフォアグラウンドサービスの権限が必要です。設定を開きますか？", Snackbar.LENGTH_INDEFINITE)
                .setAction("設定") {
                    try {
                        // Prefer opening the app settings where user can grant the permission if supported.
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) })
                    } catch (_: Exception) { }
                }
                .show()
            pendingStartAfterFgsPerm = false
            // clear pending timer request
            pendingTimerRequested = false
            pendingTimerStartSec = null
            pendingTimerDurSec = null
        } else {
            if (pendingStartAfterFgsPerm) {
                pendingStartAfterFgsPerm = false
                mainHandler.postDelayed({ try { startRecordingInternal() } catch (e: Exception) { android.util.Log.w("MainActivity", "startRecording after fgs perm failed", e) } }, 150)
            }
            // If there was a pending timer request, start it now
            if (pendingTimerRequested) {
                val s = pendingTimerStartSec ?: 0
                val d = pendingTimerDurSec ?: 0
                pendingTimerRequested = false
                pendingTimerStartSec = null
                pendingTimerDurSec = null
                mainHandler.postDelayed({
                    try { startTimerRecording(s, d) } catch (e: Exception) { android.util.Log.w("MainActivity", "startTimer after fgs perm failed", e) }
                }, 150)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // Instead of auto-launching the runtime dialog on startup (which can loop on some ROMs),
                // show a snackbar asking the user to grant notifications and let them trigger the request.
                val parent = findViewById<View>(android.R.id.content)
                val snack = Snackbar.make(parent, "録画通知を表示するには通知の許可が必要です", Snackbar.LENGTH_INDEFINITE)
                    .setAction("許可") {
                        // launch the runtime permission request when user explicitly taps
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                try { val sv = snack.view; val lp = sv.layoutParams; if (lp is FrameLayout.LayoutParams) { lp.gravity = Gravity.CENTER; sv.layoutParams = lp } } catch (_: Exception) {}
                snack.show()
            }
        }
    }

    // Helper: determine whether a permission is a runtime (dangerous) permission on this device.
    // Some permissions like FOREGROUND_SERVICE are not runtime on many platforms; requesting them
    // via the runtime permission API will always fail and can cause confusing loops. We only
    // attempt a runtime request when the system classifies the permission as dangerous.
    private fun isRuntimePermission(permission: String): Boolean {
        return try {
            val pi = packageManager.getPermissionInfo(permission, 0)
            val base = android.content.pm.PermissionInfo.PROTECTION_MASK_BASE
            val prot = pi.protectionLevel and base
            prot == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
        } catch (e: Exception) {
            // If we can't resolve the permission info, assume it's NOT a runtime permission
            appendLog("isRuntimePermission: failed to get info for $permission: ${e.message}")
            false
        }
    }

    private val notifPermReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val parent = findViewById<View>(android.R.id.content) ?: return@runOnUiThread
                val snack = Snackbar.make(parent, "通知の許可が必要です。設定を開きますか？", Snackbar.LENGTH_INDEFINITE)
                    .setAction("設定") {
                        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) }
                        startActivity(i)
                    }

                // Center the Snackbar when possible
                try {
                    val sv = snack.view
                    val lp = sv.layoutParams
                    when (lp) {
                        is FrameLayout.LayoutParams -> { lp.gravity = Gravity.CENTER; sv.layoutParams = lp }
                        is CoordinatorLayout.LayoutParams -> { lp.gravity = Gravity.CENTER; sv.layoutParams = lp }
                        else -> { /* fallback */ }
                    }

                    // Try to reduce accessibility verbosity; failures are non-fatal
                    try {
                        ViewCompat.setImportantForAccessibility(sv, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO)
                        val textId = com.google.android.material.R.id.snackbar_text
                        val actionId = com.google.android.material.R.id.snackbar_action
                        val tv = sv.findViewById<View?>(textId)
                        val av = sv.findViewById<View?>(actionId)
                        tv?.let {
                            it.isFocusable = false
                            it.contentDescription = ""
                            it.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
                            ViewCompat.setImportantForAccessibility(it, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO)
                        }
                        av?.let {
                            it.isFocusable = false
                            it.contentDescription = ""
                            it.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
                            ViewCompat.setImportantForAccessibility(it, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO)
                        }
                    } catch (e: Exception) {
                        // ignore accessibility adjustment errors
                    }
                } catch (e: Exception) {
                    // ignore snack layout issues
                }

                snack.show()
                appendLog("Notification permission required (received broadcast)")
            }
        }
    }

    // Receiver for service recording state changes so Activity updates UI even when not bound
    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread {
                when (intent?.action) {
                    RecordingService.ACTION_RECORDING_STARTED -> {
                        recordButton.text = getString(R.string.record_stop)
                        mainHandler.post(updateRunnable)
                        appendLog("Recording started")
                    }
                    RecordingService.ACTION_RECORDING_STOPPED -> {
                        recordButton.text = getString(R.string.record_start)
                        mainHandler.removeCallbacks(updateRunnable)
                        recordingState.text = formatMs(0L)
                        recordingTimer.text = ""
                        appendLog("Recording stopped")
                        // Allow screen to turn off again when recording stops
                        if (screenKeepOnSet) {
                            try {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                screenKeepOnSet = false
                                appendLog("Screen can turn off now")
                            } catch (e: Exception) {
                                appendLog("Failed to clear FLAG_KEEP_SCREEN_ON: ${e.message}")
                            }
                        }
                    }
                    RecordingService.ACTION_RECORDING_SAVED -> {
                        // Activity receives the saved URI from the service and logs it for user visibility
                        // Be robust: the service may send a String extra, a Parcelable<Uri>, or different key names
                        var uri: Uri? = null
                        try {
                            // Try common parcelable key first
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                uri = intent.getParcelableExtra("video_uri", Uri::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                uri = intent.getParcelableExtra("video_uri")
                            }
                        } catch (_: Exception) {}
                        if (uri == null) {
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    uri = intent.getParcelableExtra("output_uri", Uri::class.java)
                                } else {
                                    @Suppress("DEPRECATION")
                                    uri = intent.getParcelableExtra("output_uri")
                                }
                            } catch (_: Exception) {}
                        }
                        if (uri == null) {
                            try {
                                val s1 = intent?.getStringExtra("video_uri")
                                if (!s1.isNullOrEmpty()) uri = Uri.parse(s1)
                            } catch (_: Exception) {}
                        }
                        if (uri == null) {
                            try {
                                val s2 = intent?.getStringExtra("output_uri")
                                if (!s2.isNullOrEmpty()) uri = Uri.parse(s2)
                            } catch (_: Exception) {}
                        }
                        if (uri == null) {
                            // Try generic EXTRA_STREAM
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                                } else {
                                    @Suppress("DEPRECATION")
                                    uri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                                }
                            } catch (_: Exception) {}
                        }

                        if (uri != null) {
                            appendLog("Recording saved: $uri")
                            try {
                                val parent = findViewById<View>(android.R.id.content)
                                Snackbar.make(parent, "録画を保存しました", Snackbar.LENGTH_SHORT)
                                    .setAction("開く") {
                                        try { openUri(uri) } catch (_: Exception) {}
                                    }
                                    .show()
                                // Add a clickable entry in the log so user can tap the URI
                                try { appendClickableUri(uri) } catch (e: Exception) { appendLog("appendClickableUri failed: ${e.message}") }
                            } catch (_: Exception) { }
                        } else {
                            appendLog("Recording saved (no URI provided)")
                        }
                    }
                }
            }
        }
    }

    // Receiver for foreground-service permission requests from the Service
    private val fgsPermReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val parent = findViewById<View>(android.R.id.content) ?: return@runOnUiThread
                // Show guidance to the user but DO NOT automatically call runtime permission request
                // or set pendingStartAfterFgsPerm here. Some devices/ROMs don't support runtime request
                // for FOREGROUND_SERVICE and auto-launching causes a loop.
                val snack = Snackbar.make(parent, "フォアグラウンドサービス実行の権限が必要です。設定を開きますか？", Snackbar.LENGTH_INDEFINITE)
                    .setAction("設定") {
                        try {
                            // Prefer opening the app settings where user can grant the permission if supported.
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) })
                        } catch (_: Exception) { }
                    }
                try { val sv = snack.view; val lp = sv.layoutParams; if (lp is FrameLayout.LayoutParams) { lp.gravity = Gravity.CENTER; sv.layoutParams = lp } } catch (_: Exception) {}
                snack.show()
                appendLog("Foreground-service permission required (received broadcast)")
                // NOTE: do not call fgsPermissionLauncher.launch(...) or set pendingStartAfterFgsPerm here.
                // The launcher should only be triggered when the user explicitly tries to start recording
                // (see existing code in startRecording()). This avoids repeated automatic requests.
            }
        }
    }

    // Receiver for camera permission required broadcast from service
    private val cameraPermReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // Request camera permission using existing launcher
                try {
                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                    appendLog("Camera permission requested via receiver")
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "failed to launch camera permission request", e)
                    appendLog("Failed to launch camera permission request: ${e.message}")
                }
            }
        }
    }

    // Receiver to detect when Service.onStartCommand is entered (diagnostic for devices where logcat may filter INFO)
    private val serviceOnStartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.getStringExtra("action")
            appendLog("Service.onStartCommand called (action=$action)")
        }
    }

    private val previewUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RecordingService.ACTION_PREVIEW_UPDATED) {
                val path = intent.getStringExtra("preview_path")
                if (path != null) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(path)
                        runOnUiThread {
                            previewImageView.setImageBitmap(bitmap)
                        }
                    } catch (e: Exception) {
                        appendLog("Failed to load preview image: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // bind to service if running
        val intent = Intent(this, RecordingService::class.java)
        // Ensure bind will create the connection if the service is not yet running
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // register receiver for recording state changes
        val stateFilter = IntentFilter().apply {
            addAction(RecordingService.ACTION_RECORDING_STARTED)
            addAction(RecordingService.ACTION_RECORDING_STOPPED)
            addAction(RecordingService.ACTION_RECORDING_SAVED) // added: receive saved URI broadcasts
        }
        try {
            safeRegisterReceiver(recordingStateReceiver, stateFilter)
        } catch (e: Exception) { android.util.Log.w("MainActivity", "registerReceiver state failed", e) }

        // register receiver for timer broadcasts from service
        val timerFilter = IntentFilter().apply {
            addAction(RecordingService.ACTION_TIMER_TICK)
            addAction(RecordingService.ACTION_TIMER_STARTED)
            addAction(RecordingService.ACTION_TIMER_CANCELLED)
        }
        try { safeRegisterReceiver(timerReceiver, timerFilter) } catch (e: Exception) { android.util.Log.w("MainActivity", "registerReceiver timer failed", e) }

         // register receiver for notification-permission-required broadcasts
         val filter = IntentFilter(RecordingService.ACTION_NOTIFICATION_PERMISSION_REQUIRED)
         try {
             safeRegisterReceiver(notifPermReceiver, filter)
         } catch (e: Exception) {
             android.util.Log.w("MainActivity", "registerReceiver failed", e)
         }

        // register receiver for foreground-permission-required broadcasts
        val fgsFilter = IntentFilter(RecordingService.ACTION_FOREGROUND_PERMISSION_REQUIRED)
        try { safeRegisterReceiver(fgsPermReceiver, fgsFilter) } catch (e: Exception) { android.util.Log.w("MainActivity", "registerReceiver fgs failed", e) }
        // register receiver for microphone-foreground-permission-required broadcasts (Android 15+)
        val fgsMicFilter = IntentFilter(RecordingService.ACTION_FOREGROUND_MIC_PERMISSION_REQUIRED)
        try { safeRegisterReceiver(fgsPermReceiver, fgsMicFilter) } catch (e: Exception) { android.util.Log.w("MainActivity", "registerReceiver fgsMic failed", e) }
        // register receiver for camera-permission-required broadcasts
        val camFilter = IntentFilter(RecordingService.ACTION_CAMERA_PERMISSION_REQUIRED)
        try { safeRegisterReceiver(cameraPermReceiver, camFilter) } catch (e: Exception) { android.util.Log.w("MainActivity", "registerReceiver camera failed", e) }

        // register receiver for diagnostic onStartCommand broadcast
        try { safeRegisterReceiver(serviceOnStartReceiver, IntentFilter("com.example.silent.action.SERVICE_ONSTARTCALLED")) } catch (e: Exception) { android.util.Log.w("MainActivity", "registerReceiver onStartCalled failed", e) }

        // register receiver for preview updates
        val previewFilter = IntentFilter(RecordingService.ACTION_PREVIEW_UPDATED)
        try { safeRegisterReceiver(previewUpdateReceiver, previewFilter) } catch (e: Exception) { android.util.Log.w("MainActivity", "registerReceiver preview failed", e) }

        appendLog("onStart: receivers registered and service bind attempted")
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            try { unbindService(serviceConnection) } catch (e: Exception) { android.util.Log.w("MainActivity", "unbindService failed", e) }
            bound = false
        }
        mainHandler.removeCallbacks(updateRunnable)
        try { unregisterReceiver(notifPermReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(recordingStateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(timerReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(fgsPermReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(cameraPermReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(serviceOnStartReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(previewUpdateReceiver) } catch (_: Exception) {}

        // cancel any running timer countdowns to avoid leaks
        cancelTimerCountdown()

        appendLog("onStop: unbound and receivers unregistered")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordButton = findViewById(R.id.recordButton)
        audioStatus = findViewById(R.id.audioStatus)
        rootLayout = findViewById(R.id.rootLayout)
        viewListButton = findViewById(R.id.btnViewList)
        recordingTimer = findViewById(R.id.recordingTimer)
        recordingState = findViewById(R.id.recordingState)
        logView = findViewById(R.id.logView)
        toggleCameraButton = findViewById(R.id.toggleCameraButton)
        togglePreviewButton = findViewById(R.id.togglePreviewButton)
        previewImageView = findViewById(R.id.previewImageView)

        togglePreviewButton.setOnClickListener {
            previewImageView.visibility = if (previewImageView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // --- bind timer UI from XML (XML already contains startTimeInput/durationInput/timerRecordButton) ---
        try {
            timerStartInput = findViewById(R.id.startTimeInput)
            timerDurationInput = findViewById(R.id.durationInput)
            timerRecordButton = findViewById(R.id.timerRecordButton)

            // Save original label so we can restore it when timer completes/cancels
            timerButtonOriginalText = timerRecordButton?.text

            // Restore saved timer settings
            try {
                val (savedStart, savedDur) = loadTimerPrefs()
                timerStartInput?.setText(savedStart.toString())
                timerDurationInput?.setText(savedDur.toString())
            } catch (_: Exception) {}

            timerRecordButton?.setOnClickListener {
                try {
                    appendLog("timerRecordButton clicked: phase=$timerPhase, buttonExists=${timerRecordButton != null}")
                    // If a timer is already running, treat this click as CANCEL
                    if (timerPhase != 0) {
                        cancelTimerCountdown()
                        recordingTimer.text = ""
                        // restore on UI thread
                        runOnUiThread { timerRecordButton?.text = timerButtonOriginalText }
                        appendLog("タイマー録画をキャンセルしました")
                        return@setOnClickListener
                    }

                    // Start a new timer: only persist values when user explicitly starts
                    val startSec = timerStartInput?.text?.toString()?.trim()?.toIntOrNull() ?: 0
                    val durSec = timerDurationInput?.text?.toString()?.trim()?.toIntOrNull() ?: 0
                    if (startSec < 0 || durSec <= 0) {
                        Toast.makeText(this, "正しい秒数を入力してください（開始>=0, 録画時間>0）", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    // Ensure we remembered original text
                    if (timerButtonOriginalText == null) timerButtonOriginalText = timerRecordButton?.text

                    // Immediately update button label so UI reflects the started timer (on UI thread)
                    try {
                        // Set local timer phase so subsequent clicks are treated as cancel immediately
                        timerPhase = 1

                        runOnUiThread {
                            try {
                                val before = timerRecordButton?.text
                                timerRecordButton?.text = "キャンセル"
                                timerRecordButton?.isEnabled = true
                                // force redraw in case some devices don't update immediately
                                timerRecordButton?.invalidate()
                                appendLog("タイマー録画ボタン: ラベル変更 before=[$before] after=[${timerRecordButton?.text}]")
                            } catch (e: Exception) {
                                appendLog("タイマーボタンラベル変更失敗 (runOnUiThread): ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        appendLog("タイマーボタンラベル変更失敗 outer: ${e.message}")
                    }

                    // Persist values on explicit start as required
                    saveTimerPrefs(startSec, durSec)


                    startTimerRecording(startSec, durSec)
                } catch (e: Exception) {
                    appendLog("タイマー録画の開始に失敗: ${e.message}")
                }
            }
        } catch (e: Exception) {
            appendLog("タイマーUIバインドに失敗: ${e.message}")
        }

        // Enable clickable links in the log view so saved URIs can be tapped
        try {
            logView.movementMethod = LinkMovementMethod.getInstance()
            logView.isClickable = true
            logView.linksClickable = true
        } catch (_: Exception) {}

        // WindowInsets を監視してナビバー領域分をボタンの下マージンに追加
        val baseMargin = resources.getDimensionPixelSize(R.dimen.record_button_bottom_margin)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val sysInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val bottom = sysInsets.bottom
            recordButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = baseMargin + bottom
            }
            insets
        }

        appendLog("onCreate: UI initialized")

        audioStatus.setOnClickListener {
            // 設定画面を開く
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        viewListButton.setOnClickListener {
            val intent = Intent(this, VideoListActivity::class.java)
            startActivity(intent)
        }

        toggleCameraButton.setOnClickListener {
            toggleCamera()
        }


        recordButton.setOnClickListener {
            // Decide action robustly:
            // - If the UI currently shows '停止' (record_stop), prefer stopping the service/recording
            // - Otherwise if bound and service reports recording, stop
            // - Else start a recording
            val showingStop = recordButton.text == getString(R.string.record_stop)
            val serviceIsRecording = bound && (recordingService?.isRecording() == true)

            // If this click is intended to START recording, temporarily disable the button for 500ms
            val isStart = !(showingStop || serviceIsRecording)
            if (isStart) {
                try {
                    // Always disable immediately to prevent double-tap race
                    recordButton.isEnabled = false
                    mainHandler.postDelayed({
                        try {
                            if (!isFinishing && !isDestroyed) recordButton.isEnabled = true
                        } catch (_: Exception) {
                            // ignore
                        }
                    }, 500)
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "failed to temporarily disable recordButton", e)
                    appendLog("failed to temporarily disable recordButton: ${e.message}")
                }
            }

            if (showingStop || serviceIsRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        requestPermissionsIfNeeded()
        updateAudioStatus()

        // request notification permission on startup (if needed)
        requestNotificationPermissionIfNeeded()
    }

    private fun updateAudioStatus() {
        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        audioStatus.text = if (hasAudio) getString(R.string.mic_allowed) else getString(R.string.mic_denied)
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        // NOTE: Do NOT auto-request the microphone-foreground runtime permission on startup.
        // Some ROMs/devices will loop or deny it; only request MIC_FGS when the user initiates
        // a foreground action (handled in startRecording()/startTimerRecording()).
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
            appendLog("Requesting permissions: ${needed.joinToString()}")
        }
    }

    private fun toggleCamera() {
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        appendLog("toggleCamera: new selector=$currentCameraSelector")

        // If the service is not recording, we don't need to do anything as the new
        // selector will be used on the next recording start.
        // If it IS recording, send an intent to ask it to switch cameras.
        try {
            if (recordingService?.isRecording() == true) {
                val intent = Intent(this, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_TOGGLE_CAMERA
                    val lensFacing = if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    putExtra(RecordingService.EXTRA_CAMERA_LENS_FACING, lensFacing)
                }
                startService(intent)
                appendLog("Sent ACTION_TOGGLE_CAMERA to service")
            } else {
                appendLog("Camera will be switched on next recording start.")
            }
        } catch (e: Exception) {
            appendLog("toggleCamera failed: ${e.message}")
        }
    }


    // Entry point called by UI. This will ensure notification permission exists before starting service.
    private fun startRecording() {
        // On Android 13+ ensure POST_NOTIFICATIONS permission is granted before starting a foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // user attempted to start recording but notification permission missing -> request and remember intent
                pendingStartAfterNotifPerm = true
                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)

                // show an explanatory snackbar with action to request permission
                val parent = findViewById<View>(android.R.id.content)
                val snack = Snackbar.make(parent, "録画通知を表示するには通知の許可が必要です", Snackbar.LENGTH_INDEFINITE)
                    .setAction("許可") {
                        // launch the runtime permission request
                        notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                // center the snackbar
                try {
                    val sv = snack.view
                    val lp = sv.layoutParams
                    if (lp is FrameLayout.LayoutParams) { lp.gravity = Gravity.CENTER; sv.layoutParams = lp }
                } catch (_: Exception) {}
                snack.show()

                // ensure button gets re-enabled since we are not proceeding to start service
                try { recordButton.isEnabled = true } catch (_: Exception) {}
                appendLog("Notification permission required before starting recording")
                return
            }
        }

        // Ensure FOREGROUND_SERVICE permission on Android 12+ before starting service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Only attempt to request FOREGROUND_SERVICE via runtime API if the system actually
            // classifies it as a runtime (dangerous) permission. On many devices it's a normal
            // protection level and cannot be requested at runtime; attempting to do so causes
            // repeated prompts or immediate denial.
            val shouldRequestFgsRuntime = isRuntimePermission(Manifest.permission.FOREGROUND_SERVICE)
            if (shouldRequestFgsRuntime && ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED) {
                // request and remember intent
                pendingStartAfterFgsPerm = true
                try { fgsPermissionLauncher.launch(Manifest.permission.FOREGROUND_SERVICE) } catch (_: Exception) { /* some ROMs disallow runtime request */ }

                val parent = findViewById<View>(android.R.id.content)
                val snack = Snackbar.make(parent, "録画の開始にはフォアグラウンドサービス権限が必要です", Snackbar.LENGTH_INDEFINITE)
                    .setAction("許可") { try { fgsPermissionLauncher.launch(Manifest.permission.FOREGROUND_SERVICE) } catch (_: Exception) {} }
                try { val sv = snack.view; val lp = sv.layoutParams; if (lp is FrameLayout.LayoutParams) { lp.gravity = Gravity.CENTER; sv.layoutParams = lp } } catch (_: Exception) {}
                snack.show()

                try { recordButton.isEnabled = true } catch (_: Exception) {}
                appendLog("Foreground service permission required before starting recording (runtime request)")
                return
            } else if (!shouldRequestFgsRuntime) {
                // It's not a runtime permission on this device — don't try to request it; rely on
                // the service to notify the user via UI (and our fgsPermReceiver will show guidance).
                appendLog("FOREGROUND_SERVICE is not a runtime permission on this device; skipping runtime request")
            }
            // For Android 15+, also ensure the microphone-foreground permission is granted before starting
            if (Build.VERSION.SDK_INT >= 35) {
                try {
                    if (ContextCompat.checkSelfPermission(this, MIC_FGS_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                        pendingStartAfterFgsPerm = true
                        try { fgsPermissionLauncher.launch(MIC_FGS_PERMISSION) } catch (_: Exception) { /* ignore */ }
                        val parent = findViewById<View>(android.R.id.content)
                        val snack = Snackbar.make(parent, "マイクを使うフォアグラウンドサービスの権限が必要です", Snackbar.LENGTH_INDEFINITE)
                            .setAction("許可") { try { fgsPermissionLauncher.launch(MIC_FGS_PERMISSION) } catch (_: Exception) {} }
                        try { val sv = snack.view; val lp = sv.layoutParams; if (lp is FrameLayout.LayoutParams) { lp.gravity = Gravity.CENTER; sv.layoutParams = lp } } catch (_: Exception) {}
                        snack.show()
                        try { recordButton.isEnabled = true } catch (_: Exception) {}
                        appendLog("MIC_FGS_PERMISSION required before starting recording")
                        return
                    }
                } catch (e: Exception) {
                    appendLog("Failed checking/requesting MIC_FGS_PERMISSION: ${e.message}")
                }
            }
        }

        // If we are here, permission is present (or not required). Perform the actual start logic.
        startRecordingInternal()
    }

    // Actual logic to start the service once we know permissions are satisfied
    private fun startRecordingInternal() {
        // Start foreground recording service (robust attempt)
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            val lensFacing = if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            putExtra(RecordingService.EXTRA_CAMERA_LENS_FACING, lensFacing)
        }
        try {
            // On Android O+ we must use startForegroundService when the service will call startForeground
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
                appendLog("startForegroundService called")
            } else {
                startService(intent)
                appendLog("startService called")
            }

            // Do NOT change the button text here. The service will broadcast ACTION_RECORDING_STARTED
            try {
                val parent = findViewById<View>(android.R.id.content)
                val snackbar = Snackbar.make(parent, "録画を開始しています...", Snackbar.LENGTH_SHORT)
                val snackView = snackbar.view
                val lp = snackView.layoutParams
                if (lp is FrameLayout.LayoutParams) { lp.gravity = Gravity.CENTER; snackView.layoutParams = lp }
                snackbar.show()
                appendLog("Snackbar shown: starting recording")
            } catch (_: Exception) { }
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Failed to start recording service (Throwable)", e)
            appendLog("Failed to start recording service: ${e.message}")
            try {
                val parent = findViewById<View>(android.R.id.content)
                val s = Snackbar.make(parent, "録画サービスを起動できませんでした。設定で権限を確認してください", Snackbar.LENGTH_INDEFINITE)
                    .setAction("設定") {
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) })
                        } catch (_: Exception) {}
                    }
                try { val sv = s.view; val lp2 = sv.layoutParams; if (lp2 is FrameLayout.LayoutParams) { lp2.gravity = Gravity.CENTER; sv.layoutParams = lp2 } } catch (_: Exception) {}
                s.show()
            } catch (_: Exception) {}
            try { android.widget.Toast.makeText(this, "録画サービスを起動できませんでした: ${e.message}", android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            try { recordButton.isEnabled = true } catch (_: Exception) {}
            recordButton.text = getString(R.string.record_start)
        }
    }

    private fun stopRecording() {
        val intent = Intent(this, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP }
        try {
            startService(intent)
            appendLog("stopService requested")
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "録画停止に失敗しました: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            appendLog("Failed to request stopService: ${e.message}")
        }
        // Ensure any running timer countdown is cancelled when the user stops recording
        try {
            cancelTimerCountdown()
            recordingTimer.text = ""
        } catch (e: Exception) {
            appendLog("Failed to cancel timer on stop: ${e.message}")
        }

        recordButton.text = getString(R.string.record_start)
        updateAudioStatus()
    }

    // Append a line to the on-screen log and scroll to bottom. Thread-safe.
    private fun appendLog(line: String) {
        try {
            runOnUiThread {
                try {
                    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    logView.append("[$time] $line\n")
                    // scroll parent ScrollView to bottom
                    val sv = findViewById<android.widget.ScrollView>(R.id.logScroll)
                    sv.post { sv.fullScroll(android.view.View.FOCUS_DOWN) }
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "appendLog failed", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "appendLog outer failed", e)
        }
    }

    private fun appendClickableUri(uri: Uri) {
        try {
            val uriStr = uri.toString()
            val label = "Recording saved: $uriStr [共有]\n"
            val ss = SpannableString(label)
            val start = label.indexOf(uriStr)
            if (start >= 0) {
                val end = start + uriStr.length
                ss.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        try { openUri(uri) } catch (e: Exception) { appendLog("openUri failed: ${e.message}") }
                    }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                val shareLabel = "[共有]"
                val shareStart = label.indexOf(shareLabel, end)
                if (shareStart >= 0) {
                    val shareEnd = shareStart + shareLabel.length
                    ss.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            try { shareUri(uri) } catch (e: Exception) { appendLog("shareUri failed: ${e.message}") }
                        }
                    }, shareStart, shareEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            runOnUiThread {
                logView.append(ss)
                val sv = findViewById<android.widget.ScrollView>(R.id.logScroll)
                sv.post { sv.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "appendClickableUri failed", e)
            appendLog("appendClickableUri failed: ${e.message}")
        }
    }

    private fun shareUri(uri: Uri) {
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(Intent.createChooser(share, "共有"))
        } catch (e: Exception) {
            appendLog("Failed to share URI: ${e.message}")
        }
    }

    private fun openUri(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            appendLog("Failed to open URI: ${e.message}")
            try { Toast.makeText(this, "ファイルを開けませんでした", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    private fun clearLog() {
        try { runOnUiThread { logView.text = "" } } catch (_: Exception) {}
    }

    private fun formatMs(ms: Long): String {
        val totalSeconds = (ms / 1000)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format("%02d:%02d", minutes, seconds)
    }

    // Timer recording support
    private fun startTimerRecording(startSeconds: Int, durationSeconds: Int) {
        // Delegate timer control to the RecordingService so it continues when Activity is backgrounded.
        try {
            // Check notification permission on Android 13+ before starting a foreground timer service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    // Save pending timer request so we can resume after permission is granted
                    pendingTimerRequested = true
                    pendingTimerStartSec = startSeconds
                    pendingTimerDurSec = durationSeconds
                    appendLog("POST_NOTIFICATIONS missing: requesting before starting timer")
                    // Request permission
                    notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)

                    // show explanatory snackbar
                    val parent = findViewById<View>(android.R.id.content)
                    val snack = Snackbar.make(parent, "タイマー録画を使用するには通知の許可が必要です", Snackbar.LENGTH_INDEFINITE)
                        .setAction("許可") { notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                    try { val sv = snack.view; val lp = sv.layoutParams; if (lp is FrameLayout.LayoutParams) { lp.gravity = Gravity.CENTER; sv.layoutParams = lp } } catch (_: Exception) {}
                    snack.show()
                    // ensure button re-enabled
                    try { timerRecordButton?.isEnabled = true } catch (_: Exception) {}
                    return
                }
            }

            // Ensure FOREGROUND_SERVICE permission on Android 12+ before starting service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val shouldRequestFgsRuntime = isRuntimePermission(Manifest.permission.FOREGROUND_SERVICE)
                if (shouldRequestFgsRuntime && ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED) {
                    // Save pending timer and request
                    pendingTimerRequested = true
                    pendingTimerStartSec = startSeconds
                    pendingTimerDurSec = durationSeconds
                    pendingStartAfterFgsPerm = true
                    try { fgsPermissionLauncher.launch(Manifest.permission.FOREGROUND_SERVICE) } catch (_: Exception) { /* some ROMs disallow runtime request */ }

                    val parent = findViewById<View>(android.R.id.content)
                    val snack = Snackbar.make(parent, "タイマー録画の開始にはフォアグラウンドサービス権限が必要です", Snackbar.LENGTH_INDEFINITE)
                        .setAction("許可") { try { fgsPermissionLauncher.launch(Manifest.permission.FOREGROUND_SERVICE) } catch (_: Exception) {} }
                    try { val sv = snack.view; val lp = sv.layoutParams; if (lp is FrameLayout.LayoutParams) { lp.gravity = Gravity.CENTER; sv.layoutParams = lp } } catch (_: Exception) {}
                    snack.show()

                    try { timerRecordButton?.isEnabled = true } catch (_: Exception) {}
                    appendLog("Foreground service permission required before starting timer (runtime request)")
                    return
                } else if (!shouldRequestFgsRuntime) {
                    appendLog("FOREGROUND_SERVICE is not a runtime permission on this device; skipping runtime request for timer start")
                }
                // For Android 15+, ensure microphone foreground permission is present as well
                if (Build.VERSION.SDK_INT >= 35) {
                    try {
                        if (ContextCompat.checkSelfPermission(this, MIC_FGS_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                            pendingTimerRequested = true
                            pendingTimerStartSec = startSeconds
                            pendingTimerDurSec = durationSeconds
                            pendingStartAfterFgsPerm = true
                            try { fgsPermissionLauncher.launch(MIC_FGS_PERMISSION) } catch (_: Exception) { /* ignore */ }
                            val parent = findViewById<View>(android.R.id.content)
                            val snack = Snackbar.make(parent, "タイマー録画にマイクのフォアグラウンド権限が必要です", Snackbar.LENGTH_INDEFINITE)
                                .setAction("許可") { try { fgsPermissionLauncher.launch(MIC_FGS_PERMISSION) } catch (_: Exception) {} }
                            try { val sv = snack.view; val lp = sv.layoutParams; if (lp is FrameLayout.LayoutParams) { lp.gravity = Gravity.CENTER; sv.layoutParams = lp } } catch (_: Exception) {}
                            snack.show()
                            try { timerRecordButton?.isEnabled = true } catch (_: Exception) {}
                            appendLog("MIC_FGS_PERMISSION required before starting timer")
                            return
                        }
                    } catch (e: Exception) {
                        appendLog("Failed checking/requesting MIC_FGS_PERMISSION for timer: ${e.message}")
                    }
                }
            }

            cancelTimerCountdown() // local UI cleanup
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_TIMER_START
                putExtra("start_sec", startSeconds)
                putExtra("duration_sec", durationSeconds)
                val lensFacing = if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                putExtra(RecordingService.EXTRA_CAMERA_LENS_FACING, lensFacing)
            }
            // Ensure service is started; on newer Android versions use startForegroundService when appropriate
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            appendLog("ACTION_TIMER_START sent start=$startSeconds dur=$durationSeconds")
        } catch (e: Exception) {
            appendLog("Failed to send ACTION_TIMER_START: ${e.message}")
        }
    }

    private fun cancelTimerCountdown() {
        // Cancel local UI countdown and inform service to cancel timer if running there
        try {
            timerCountdownRunnable?.let { timerHandler.removeCallbacks(it) }
        } catch (e: Exception) {
            // ignore
        }
        timerCountdownRunnable = null
        timerPhase = 0
        // restore button label when timer is cancelled
        try { timerRecordButton?.text = timerButtonOriginalText } catch (e: Exception) { /* ignore */ }
        // Allow screen to turn off again
        if (screenKeepOnSet) {
            try {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                screenKeepOnSet = false
                appendLog("Screen can turn off now (timer cancelled)")
            } catch (e: Exception) {
                // ignore
            }
        }
        try {
            val intent = Intent(this, RecordingService::class.java).apply { action = RecordingService.ACTION_TIMER_CANCEL }
            // Use startService for cancel to avoid triggering foreground start without explicit user action
            try {
                startService(intent)
            } catch (e: Exception) {
                // fallback to startForegroundService if startService fails
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) try { startForegroundService(intent) } catch (_: Exception) { appendLog("Failed to request cancel via startForegroundService: ${e.message}") }
            }
            appendLog("ACTION_TIMER_CANCEL sent")
        } catch (e: Exception) {
            appendLog("Failed to send ACTION_TIMER_CANCEL: ${e.message}")
        }
    }

    // Receiver for timer events from the service
    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread {
                when (intent?.action) {
                    RecordingService.ACTION_TIMER_TICK -> {
                        // Update countdown UI based on phase
                        val remainingSec = intent.getIntExtra("remaining_sec", 0)
                        val phase = intent.getStringExtra("phase") ?: ""
                        when (phase) {
                            "waiting" -> {
                                timerPhase = 1
                                recordingTimer.text = String.format("開始まで: %02d:%02d", remainingSec / 60, remainingSec % 60)
                                // Ensure timer button shows 'キャンセル' while waiting (service-driven start)
                                try { timerRecordButton?.text = "キャンセル" } catch (_: Exception) {}
                                // Keep screen on during timer countdown - only log once
                                if (!screenKeepOnSet) {
                                    try {
                                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                        screenKeepOnSet = true
                                        appendLog("Screen will stay on during timer")
                                    } catch (e: Exception) {
                                        appendLog("Failed to set FLAG_KEEP_SCREEN_ON: ${e.message}")
                                    }
                                }
                            }
                            "recording" -> {
                                timerPhase = 2
                                recordingTimer.text = String.format("残り: %02d:%02d", remainingSec / 60, remainingSec % 60)
                                // when recording starts, restore timer button label
                                try { timerRecordButton?.text = timerButtonOriginalText } catch (_: Exception) {}
                            }
                            else -> {
                                recordingTimer.text = String.format("残り: %02d:%02d", remainingSec / 60, remainingSec % 60)
                            }
                        }
                    }
                    RecordingService.ACTION_TIMER_STARTED -> {
                        // Timer started, update phase and UI (recording started)
                        timerPhase = 2
                        recordButton.text = getString(R.string.record_stop)
                        mainHandler.post(updateRunnable)
                        appendLog("タイマー録画: 録画開始")
                        // restore timer button label when recording actually starts
                        try { timerRecordButton?.text = timerButtonOriginalText } catch (_: Exception) {}
                    }
                    RecordingService.ACTION_TIMER_CANCELLED -> {
                        // Timer cancelled, reset UI
                        recordingTimer.text = ""
                        timerPhase = 0
                        // restore button label when timer is cancelled
                        try { timerRecordButton?.text = timerButtonOriginalText } catch (e: Exception) { /* ignore */ }
                        // Allow screen to turn off again
                        if (screenKeepOnSet) {
                            try {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                screenKeepOnSet = false
                                appendLog("Screen can turn off now")
                            } catch (e: Exception) {
                                appendLog("Failed to clear FLAG_KEEP_SCREEN_ON: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    // Helper to register receivers safely without referencing API-33-only flags on older devices
    private fun safeRegisterReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // use non-exported registration on newer platforms to avoid security/permission issues
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            // Fallback: log the error so callers can handle silently
            appendLog("safeRegisterReceiver failed: ${e.message}")
        }
    }

    // Release camera resources and clear preview surface to avoid holding camera when not needed
    private fun releaseCameraResources() {
        appendLog("Camera resources released by Activity")
    }

    override fun onDestroy() {
        // Ensure camera resources are released when Activity destroyed
        // Clear screen keep-on flag if it's still set
        try {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            screenKeepOnSet = false
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }
}
