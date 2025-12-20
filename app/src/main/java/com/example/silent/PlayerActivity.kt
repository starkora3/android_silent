package com.example.silent

import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

class PlayerActivity : AppCompatActivity() {

    private var videoView: VideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        videoView = findViewById(R.id.videoView)
        val uriString = intent.getStringExtra("video_uri")
        uriString?.let {
            val uri = Uri.parse(it)
            videoView?.setVideoURI(uri)
            val mediaController = MediaController(this)
            mediaController.setAnchorView(videoView)
            videoView?.setMediaController(mediaController)
            videoView?.start()
        }
    }

    override fun onStop() {
        super.onStop()
        videoView?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        videoView?.stopPlayback()
        videoView = null
    }
}
