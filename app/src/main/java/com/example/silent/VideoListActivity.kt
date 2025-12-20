package com.example.silent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class VideoListActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: VideoAdapter

    private val recordingSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.action == RecordingService.ACTION_RECORDING_SAVED) {
                val uriStr = intent.getStringExtra("video_uri")
                runOnUiThread {
                    // refresh list
                    loadVideos()
                    uriStr?.let { _ ->
                        Toast.makeText(this@VideoListActivity, "録画を保存しました", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Register receiver defensively to avoid crashing if registration fails on some devices
        try {
            // Use non-exported registration on newer platforms to avoid security/permission issues
            registerReceiver(recordingSavedReceiver, IntentFilter(RecordingService.ACTION_RECORDING_SAVED), Context.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            android.util.Log.w("VideoListActivity", "registerReceiver failed", e)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(recordingSavedReceiver) } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Wrap initialization in try/catch to avoid uncaught exceptions from resources or layout inflations
        try {
            setContentView(R.layout.activity_list)

            val root = findViewById<View>(R.id.rootList)
            recycler = findViewById(R.id.videoRecycler)
            recycler.layoutManager = LinearLayoutManager(this)
            adapter = VideoAdapter({ item ->
                // play -> PlayerActivity
                val intent = Intent(this@VideoListActivity, PlayerActivity::class.java).apply {
                    putExtra("video_uri", item.uri.toString())
                }
                startActivity(intent)
            }, { item ->
                // share
                shareVideo(item.uri)
            }, { item ->
                // delete request -> show confirmation first
                confirmDelete(item)
            })
            recycler.adapter = adapter

            // Apply WindowInsets to avoid status bar overlap
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val sysInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                val top = sysInsets.top
                recycler.setPadding(recycler.paddingLeft, top, recycler.paddingRight, recycler.paddingBottom)
                insets
            }

            loadVideos()
        } catch (e: Exception) {
            // Log and show a user-friendly message instead of crashing
            android.util.Log.e("VideoListActivity", "Initialization failed", e)
            try {
                val parent = findViewById<View>(android.R.id.content)
                Snackbar.make(parent, "動画一覧を表示できませんでした。設定を確認してください。", Snackbar.LENGTH_LONG).show()
            } catch (_: Exception) {}
            // finish the activity gracefully
            finish()
        }
    }

    private fun loadVideos() {
        val items = mutableListOf<VideoItem>()
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION)
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        var query = null as android.database.Cursor?
        try {
            query = contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, sort)
        } catch (se: SecurityException) {
            // Permission denied; show Snackbar that directs user to app settings to grant storage/media permission
            runOnUiThread {
                val parent = findViewById<View>(android.R.id.content)
                Snackbar.make(parent, "動画の読み取り権限が必要です。設定を開きますか？", Snackbar.LENGTH_INDEFINITE)
                    .setAction("設定") {
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) })
                        } catch (_: Exception) {}
                    }
                    .show()
                if (::adapter.isInitialized) adapter.submitList(emptyList()) else android.util.Log.w("VideoListActivity", "adapter not initialized when permission denied")
            }
            return
        } catch (e: Exception) {
            // Other failures; log and show a message
            android.util.Log.e("VideoListActivity", "loadVideos: query failed", e)
            runOnUiThread {
                val parent = findViewById<View>(android.R.id.content)
                Snackbar.make(parent, "動画一覧を読み込めませんでした", Snackbar.LENGTH_LONG).show()
                if (::adapter.isInitialized) adapter.submitList(emptyList()) else android.util.Log.w("VideoListActivity", "adapter not initialized when query failed")
            }
            return
        }

        query?.use { cursor ->
            try {
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx)
                    val duration = cursor.getLong(durationIdx)
                    val contentUri: Uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                    items.add(VideoItem(name, contentUri, duration))
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoListActivity", "cursor read failed", e)
            }
        }

        if (::adapter.isInitialized) {
            try { adapter.submitList(items) } catch (e: Exception) { android.util.Log.e("VideoListActivity", "submitList failed", e) }
        } else {
            android.util.Log.w("VideoListActivity", "adapter not initialized when trying to submit list")
        }
    }

    private fun shareVideo(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(Intent.createChooser(intent, null))
    }

    private fun confirmDelete(item: VideoItem) {
        AlertDialog.Builder(this)
            .setTitle("削除の確認")
            .setMessage("この動画を本当に削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                // ユーザーが削除を確定したら、取り消し可能な削除フローに移行
                requestDeleteWithUndo(item)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun requestDeleteWithUndo(item: VideoItem) {
        val currentList = if (::adapter.isInitialized) ArrayList(adapter.currentList) else arrayListOf<VideoItem>()
        // remove item from displayed list
        currentList.remove(item)
        if (::adapter.isInitialized) adapter.submitList(currentList)

        val parent = findViewById<View>(android.R.id.content)
        val snackbar = Snackbar.make(parent, "動画を削除しました", Snackbar.LENGTH_LONG)
        snackbar.setAction("取り消す") {
            // restore
            if (::adapter.isInitialized) adapter.submitList(ArrayList(adapter.currentList).apply { add(item) })
        }

        // position Snackbar at center of screen
        val snackView = snackbar.view
        val lp = snackView.layoutParams
        when (lp) {
            is FrameLayout.LayoutParams -> {
                lp.gravity = Gravity.CENTER
                snackView.layoutParams = lp
            }
            is CoordinatorLayout.LayoutParams -> {
                lp.gravity = Gravity.CENTER
                snackView.layoutParams = lp
            }
            else -> {
                // fallback: translate to center after show
            }
        }

        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                // if dismissed because of timeout or swipe (not because of action), perform actual delete
                if (event != Snackbar.Callback.DISMISS_EVENT_ACTION) {
                    // perform actual deletion
                    performActualDelete(item)
                }
            }

            override fun onShown(sb: Snackbar?) {
                super.onShown(sb)
                // fallback: if layout params did not support gravity, center by translation
                val sv = snackbar.view
                val params = sv.layoutParams
                if (params !is FrameLayout.LayoutParams && params !is CoordinatorLayout.LayoutParams) {
                    val parentView = parent
                    parentView?.let {
                        sv.translationY = (it.height - sv.height) / -2f
                    }
                }
            }
        })

        snackbar.show()
    }

    private fun performActualDelete(item: VideoItem) {
        try {
            val rows = contentResolver.delete(item.uri, null, null)
            if (rows > 0) {
                Toast.makeText(this, "削除を確定しました", Toast.LENGTH_SHORT).show()
                loadVideos()
            } else {
                Toast.makeText(this, "削除に失敗しました", Toast.LENGTH_SHORT).show()
                loadVideos()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "削除エラー: ${e.message}", Toast.LENGTH_SHORT).show()
            loadVideos()
        }
    }
}
