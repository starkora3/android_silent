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
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoAdapter(
    private var items: List<VideoItem>,
    private val onPlay: (VideoItem) -> Unit,
    private val onShare: (VideoItem) -> Unit,
    private val onDelete: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val name: TextView = view.findViewById(R.id.videoName)
        val uriView: TextView = view.findViewById(R.id.videoUri)
        val btnPlay: ImageButton = view.findViewById(R.id.btnPlay)
        val btnShare: ImageButton = view.findViewById(R.id.btnShare)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.displayName
        holder.uriView.text = item.uri.toString()

        holder.btnPlay.setOnClickListener { onPlay(item) }
        holder.btnShare.setOnClickListener { onShare(item) }
        holder.btnDelete.setOnClickListener { onDelete(item) }

        // サムネイル非同期取得
        loadThumbnail(holder.itemView.context, item.uri, holder.thumb)
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<VideoItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    private fun loadThumbnail(context: Context, uri: android.net.Uri, imageView: ImageView) {
        // MediaStore の ID を抽出してサムネイル取得
        CoroutineScope(Dispatchers.Main).launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val id = ContentUris.parseId(uri)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val source = context.contentResolver.loadThumbnail(uri, Size(200,200), null)
                        source
                    } else {
                        MediaStore.Video.Thumbnails.getThumbnail(
                            context.contentResolver,
                            id,
                            MediaStore.Video.Thumbnails.MINI_KIND,
                            null
                        )
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap != null) imageView.setImageBitmap(bitmap) else imageView.setImageResource(android.R.drawable.ic_menu_report_image)
        }
    }
}

data class VideoItem(val displayName: String, val uri: android.net.Uri)
