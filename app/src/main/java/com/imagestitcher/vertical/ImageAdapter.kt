package com.imagestitcher.vertical

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

class ImageAdapter(
    private val scope: CoroutineScope,
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<ImageAdapter.ViewHolder>() {

    private val items = mutableListOf<ImageItem>()

    fun submitList(newItems: List<ImageItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_selected_image, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvIndex.text = (position + 1).toString()
        holder.tvName.text = item.name
        holder.tvDimensions.text = holder.itemView.context.getString(
            R.string.image_info_format,
            item.width,
            item.height
        )

        holder.btnMoveUp.isEnabled = position > 0
        holder.btnMoveUp.alpha = if (position > 0) 1.0f else 0.3f
        holder.btnMoveDown.isEnabled = position < items.size - 1
        holder.btnMoveDown.alpha = if (position < items.size - 1) 1.0f else 0.3f

        holder.btnMoveUp.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos > 0) {
                onMoveUp(currentPos)
            }
        }

        holder.btnMoveDown.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos in 0 until items.size - 1) {
                onMoveDown(currentPos)
            }
        }

        holder.btnRemove.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                onRemove(currentPos)
            }
        }

        // 异步加载缩略图
        holder.ivThumbnail.setImageDrawable(null)
        val tag = item.uri.toString()
        holder.ivThumbnail.tag = tag

        scope.launch {
            val bitmap = loadThumbnail(holder.itemView.context, item)
            if (holder.ivThumbnail.tag == tag) {
                holder.ivThumbnail.setImageBitmap(bitmap)
            }
        }
    }

    private suspend fun loadThumbnail(
        context: android.content.Context,
        item: ImageItem
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(item.uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            var sampleSize = 1
            val targetSize = 150
            while (options.outWidth / (sampleSize * 2) >= targetSize &&
                options.outHeight / (sampleSize * 2) >= targetSize
            ) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            context.contentResolver.openInputStream(item.uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (e: Exception) {
            null
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvIndex: TextView = itemView.findViewById(R.id.tvIndex)
        val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        val tvName: TextView = itemView.findViewById(R.id.tvImageName)
        val tvDimensions: TextView = itemView.findViewById(R.id.tvImageDimensions)
        val btnMoveUp: ImageButton = itemView.findViewById(R.id.btnMoveUp)
        val btnMoveDown: ImageButton = itemView.findViewById(R.id.btnMoveDown)
        val btnRemove: ImageButton = itemView.findViewById(R.id.btnRemove)
    }
}