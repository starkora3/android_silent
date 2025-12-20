package com.example.silent

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class VideoAdapter(
    private val onPlay: (VideoItem) -> Unit,
    private val onShare: (VideoItem) -> Unit,
    private val onDeleteRequest: (VideoItem) -> Unit
) : ListAdapter<VideoItem, VideoAdapter.VH>(VideoDiffCallback()) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val name: TextView = view.findViewById(R.id.videoName)
        val uriView: TextView = view.findViewById(R.id.videoUri)
        val durationView: TextView = view.findViewById(R.id.videoDuration)
        val btnPlay: ImageButton = view.findViewById(R.id.btnPlay)
        val btnShare: ImageButton = view.findViewById(R.id.btnShare)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.name.text = item.displayName
        holder.uriView.text = item.uri.toString()
        holder.durationView.text = formatDuration(item.durationMs)

        holder.btnPlay.setOnClickListener { onPlay(item) }
        holder.btnShare.setOnClickListener { onShare(item) }
        holder.btnDelete.setOnClickListener { onDeleteRequest(item) }

        // Glideでサムネイルをロード
        Glide.with(holder.thumb.context)
            .load(item.uri)
            .centerCrop()
            .placeholder(android.R.drawable.ic_menu_report_image)
            .into(holder.thumb)
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format("%d:%02d", minutes, seconds)
    }
}

class VideoDiffCallback : DiffUtil.ItemCallback<VideoItem>() {
    override fun areItemsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean = oldItem.uri == newItem.uri
    override fun areContentsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean = oldItem == newItem
}

data class VideoItem(val displayName: String, val uri: android.net.Uri, val durationMs: Long)
