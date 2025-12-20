package com.example.silent

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.QualitySelector
import androidx.camera.video.Quality
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.snackbar.Snackbar
import android.view.View

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var recordButton: Button
    private lateinit var audioStatus: TextView
    private lateinit var rootLayout: ViewGroup
    private lateinit var viewListButton: View

    private var currentRecording: Recording? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.CAMERA] == true
        if (granted) startCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        recordButton = findViewById(R.id.recordButton)
        audioStatus = findViewById(R.id.audioStatus)
        rootLayout = findViewById(R.id.rootLayout)
        viewListButton = findViewById(R.id.btnViewList)

        // WindowInsets を監視してナビバー領域分をボタンの下マージンに追加
        val baseMargin = resources.getDimensionPixelSize(R.dimen.record_button_bottom_margin)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val sysInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val bottom = sysInsets.bottom
            recordButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = baseMargin + bottom
            }
            insets
        }

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
            if (currentRecording == null) startRecording() else stopRecording()
        }

        requestPermissionsIfNeeded()
        updateAudioStatus()
    }

    private fun updateAudioStatus() {
        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        audioStatus.text = if (hasAudio) getString(R.string.mic_allowed) else getString(R.string.mic_denied)
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.CAMERA
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        val vc = videoCapture ?: return

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

        currentRecording = vc.output
            .prepareRecording(this, outputOptions)
            .apply {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        recordButton.text = getString(R.string.record_stop)
                        // Snackbar を表示
                        Snackbar.make(rootLayout, getString(R.string.record_started), Snackbar.LENGTH_SHORT).show()
                    }
                    is VideoRecordEvent.Finalize -> {
                        recordButton.text = getString(R.string.record_start)
                        if (!event.hasError()) {
                            // 成功。URIは event.outputResults.outputUri
                        } else {
                            // エラー処理
                        }
                        currentRecording = null
                        updateAudioStatus()
                    }
                }
            }
    }

    private fun stopRecording() {
        currentRecording?.stop()
        currentRecording = null
        updateAudioStatus()
    }
}
