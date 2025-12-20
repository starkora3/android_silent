package com.example.silent

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Video
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class VideoListActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: VideoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)

        recycler = findViewById(R.id.videoRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = VideoAdapter(emptyList(), { item ->
            // play -> PlayerActivity
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("video_uri", item.uri.toString())
            }
            startActivity(intent)
        }, { item ->
            // share
            shareVideo(item.uri)
        }, { item ->
            // delete
            deleteVideo(item.uri)
        })
        recycler.adapter = adapter

        loadVideos()
    }

    private fun loadVideos() {
        val items = mutableListOf<VideoItem>()
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME)
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val query = contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, sort)
        query?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx)
                val contentUri: Uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                items.add(VideoItem(name, contentUri))
            }
        }
        adapter.update(items)
    }

    private fun shareVideo(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(Intent.createChooser(intent, null))
    }

    private fun deleteVideo(uri: Uri) {
        try {
            val rows = contentResolver.delete(uri, null, null)
            if (rows > 0) {
                Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show()
                loadVideos()
            } else {
                Toast.makeText(this, "削除に失敗しました", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "削除エラー: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
