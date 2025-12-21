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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.QualitySelector
import androidx.camera.video.Quality
import androidx.camera.view.PreviewView
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
import android.Manifest
import android.content.pm.PackageManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.method.LinkMovementMethod
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var recordButton: Button
    private lateinit var audioStatus: TextView
    private lateinit var rootLayout: ViewGroup
    private lateinit var viewListButton: View
    private lateinit var recordingTimer: TextView
    private lateinit var logView: TextView
    private lateinit var navigateButton: View

    private var videoCapture: VideoCapture<Recorder>? = null

    private var recordingService: RecordingService? = null
    private var bound = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            val elapsed = recordingService?.getElapsedMs() ?: 0L
            recordingTimer.text = formatMs(elapsed)
            if (bound && elapsed > 0L) mainHandler.postDelayed(this, 500)
        }
    }

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
                    recordingTimer.text = formatMs(0L)
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
                recordingTimer.text = formatMs(0L)
                appendLog("Service disconnected")
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.CAMERA] == true
        appendLog("Camera permission result: $granted")
        if (granted) startCamera()
    }

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
            // clear pending flag since user denied
            pendingStartAfterNotifPerm = false
        } else {
            // granted: if there was a pending user intent to start recording, start now
            if (pendingStartAfterNotifPerm) {
                pendingStartAfterNotifPerm = false
                // small delay to ensure permission state is settled
                mainHandler.postDelayed({
                    try { startRecordingInternal() } catch (e: Exception) { android.util.Log.w("MainActivity", "startRecording after notif perm failed", e) }
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
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) }
                    startActivity(intent)
                }
                .show()
            pendingStartAfterFgsPerm = false
        } else {
            if (pendingStartAfterFgsPerm) {
                pendingStartAfterFgsPerm = false
                mainHandler.postDelayed({ try { startRecordingInternal() } catch (e: Exception) { android.util.Log.w("MainActivity", "startRecording after fgs perm failed", e) } }, 150)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // show rationale if needed
                if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("通知の許可")
                        .setMessage("録画中の通知を表示するために通知の許可が必要です。設定しますか？")
                        .setPositiveButton("許可") { _, _ -> notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        .setNegativeButton("後で", null)
                        .show()
                } else {
                    // direct request
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
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
                        recordingTimer.text = formatMs(0L)
                        appendLog("Recording stopped")
                    }
                    RecordingService.ACTION_RECORDING_SAVED -> {
                        // Activity receives the saved URI from the service and logs it for user visibility
                        val uriStr = intent?.getStringExtra("video_uri")
                        if (!uriStr.isNullOrEmpty()) {
                            appendLog("Recording saved: $uriStr")
                            try {
                                val parent = findViewById<View>(android.R.id.content)
                                Snackbar.make(parent, "録画を保存しました", Snackbar.LENGTH_SHORT)
                                    .setAction("開く") {
                                        try { openUri(Uri.parse(uriStr)) } catch (_: Exception) {}
                                    }
                                    .show()
                                // Add a clickable entry in the log so user can tap the URI
                                try { appendClickableUri(Uri.parse(uriStr)) } catch (e: Exception) { appendLog("appendClickableUri failed: ${e.message}") }
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
                val snack = Snackbar.make(parent, "フォアグラウンドサービス実行の権限が必要です。設定を開きますか？", Snackbar.LENGTH_INDEFINITE)
                    .setAction("設定") {
                        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) }
                        startActivity(i)
                    }
                try { val sv = snack.view; val lp = sv.layoutParams; if (lp is FrameLayout.LayoutParams) { lp.gravity = Gravity.CENTER; sv.layoutParams = lp } } catch (_: Exception) {}
                snack.show()
                appendLog("Foreground-service permission required (received broadcast)")
                // attempt to ask runtime permission if applicable
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    pendingStartAfterFgsPerm = true
                    try { fgsPermissionLauncher.launch(Manifest.permission.FOREGROUND_SERVICE) } catch (_: Exception) { /* some ROMs don't allow runtime request for this; settings guidance above will help */ }
                }
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

    override fun onStart() {
        super.onStart()
        // bind to service if running
        val intent = Intent(this, RecordingService::class.java)
        bindService(intent, serviceConnection, 0)

        // register receiver for recording state changes
        val stateFilter = IntentFilter().apply {
            addAction(RecordingService.ACTION_RECORDING_STARTED)
            addAction(RecordingService.ACTION_RECORDING_STOPPED)
            addAction(RecordingService.ACTION_RECORDING_SAVED) // added: receive saved URI broadcasts
        }
        try { registerReceiver(recordingStateReceiver, stateFilter, Context.RECEIVER_NOT_EXPORTED) } catch (e: Exception) { android.util.Log.w("MainActivity", "registerReceiver state failed", e) }

        // register receiver for notification-permission-required broadcasts
        val filter = IntentFilter(RecordingService.ACTION_NOTIFICATION_PERMISSION_REQUIRED)
        try {
            registerReceiver(notifPermReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "registerReceiver failed", e)
        }

        // register receiver for foreground-permission-required broadcasts
        val fgsFilter = IntentFilter(RecordingService.ACTION_FOREGROUND_PERMISSION_REQUIRED)
        try { registerReceiver(fgsPermReceiver, fgsFilter, Context.RECEIVER_NOT_EXPORTED) } catch (e: Exception) { android.util.Log.w("MainActivity", "registerReceiver fgs failed", e) }
        // register receiver for camera-permission-required broadcasts
        val camFilter = IntentFilter(RecordingService.ACTION_CAMERA_PERMISSION_REQUIRED)
        try { registerReceiver(cameraPermReceiver, camFilter, Context.RECEIVER_NOT_EXPORTED) } catch (e: Exception) { android.util.Log.w("MainActivity", "registerReceiver camera failed", e) }
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
        try { unregisterReceiver(fgsPermReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(cameraPermReceiver) } catch (_: Exception) {}
        appendLog("onStop: unbound and receivers unregistered")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        recordButton = findViewById(R.id.recordButton)
        audioStatus = findViewById(R.id.audioStatus)
        rootLayout = findViewById(R.id.rootLayout)
        viewListButton = findViewById(R.id.btnViewList)
        navigateButton = findViewById(R.id.btnNavigate)
        recordingTimer = findViewById(R.id.recordingTimer)
        logView = findViewById(R.id.logView)

        navigateButton.setOnClickListener {
            try {
                val intent = Intent(this, SecondActivity::class.java)
                startActivity(intent)
            } catch (e: Exception) {
                appendLog("Failed to navigate: ${e.message}")
            }
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
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
            appendLog("Requesting permissions: ${needed.joinToString()}")
        } else {
            startCamera()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture)
                appendLog("Camera started and bound to lifecycle")
            } catch (e: Exception) {
                e.printStackTrace()
                appendLog("Failed to start camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED) {
                // request and remember intent
                pendingStartAfterFgsPerm = true
                try { fgsPermissionLauncher.launch(Manifest.permission.FOREGROUND_SERVICE) } catch (_: Exception) { /* some ROMs disallow runtime request */ }

                val parent = findViewById<View>(android.R.id.content)
                val snack = Snackbar.make(parent, "録画の開始にはフォアグラウンドサービス権限が必要です", Snackbar.LENGTH_INDEFINITE)
                    .setAction("許可") {
                        try { fgsPermissionLauncher.launch(Manifest.permission.FOREGROUND_SERVICE) } catch (_: Exception) {}
                    }
                try { val sv = snack.view; val lp = sv.layoutParams; if (lp is FrameLayout.LayoutParams) { lp.gravity = Gravity.CENTER; sv.layoutParams = lp } } catch (_: Exception) {}
                snack.show()

                try { recordButton.isEnabled = true } catch (_: Exception) {}
                appendLog("Foreground service permission required before starting recording")
                return
            }
        }

        // If we are here, permission is present (or not required). Perform the actual start logic.
        startRecordingInternal()
    }

    // Actual logic to start the service once we know permissions are satisfied
    private fun startRecordingInternal() {
        // Start foreground recording service (robust attempt)
        val intent = Intent(this, RecordingService::class.java).apply { action = RecordingService.ACTION_START }
        try {
            try {
                try {
                    startService(intent)
                    appendLog("startService called")
                } catch (e: IllegalStateException) {
                    android.util.Log.w("MainActivity", "startService threw IllegalStateException, trying startForegroundService", e)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                        appendLog("startForegroundService used after IllegalStateException")
                    } else {
                        throw e
                    }
                }
            } catch (inner: Exception) {
                android.util.Log.w("MainActivity", "service start failed, attempting fallback", inner)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                    appendLog("Fallback service start attempted")
                } catch (inner2: Exception) {
                    throw RuntimeException("All service start attempts failed", inner2)
                }
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
                val s = Snackbar.make(parent, "録画サービスを開始できませんでした。設定で権限を確認してください", Snackbar.LENGTH_INDEFINITE)
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
            val label = "Recording saved: $uriStr\n"
            val ss = SpannableString(label)
            val start = label.indexOf(uriStr)
            if (start >= 0) {
                val end = start + uriStr.length
                ss.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        try { openUri(uri) } catch (e: Exception) { appendLog("openUri failed: ${e.message}") }
                    }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
}
