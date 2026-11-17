package com.imagestitcher.vertical

import android.app.Activity
import android.app.Dialog
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED_URIS = "extra_selected_uris"
    }

    data class GalleryPhoto(
        val id: Long,
        val uri: Uri,
        val displayName: String,
        val bucketName: String,
        val width: Int,
        val height: Int
    )

    private val allPhotos = mutableListOf<GalleryPhoto>()
    private val filteredPhotos = mutableListOf<GalleryPhoto>()
    private val selectedOrderUris = mutableListOf<Uri>()
    private var currentAlbum: String = "全部图片"

    private lateinit var rvGalleryGrid: RecyclerView
    private lateinit var tvSelectionOrderHint: TextView
    private lateinit var tvEmptyGallery: TextView
    private lateinit var pbGalleryLoading: ProgressBar
    private lateinit var btnConfirmSelection: MaterialButton
    private lateinit var btnClearSelection: Button
    private lateinit var layoutAlbumChips: LinearLayout

    private lateinit var galleryAdapter: GalleryGridAdapter
    private lateinit var thumbCache: LruCache<Long, Bitmap>

    // 备用系统文件选择器
    private val systemPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val resultIntent = Intent().apply {
                putParcelableArrayListExtra(EXTRA_SELECTED_URIS, ArrayList(uris))
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery_picker)

        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = maxMemoryKb / 8
        thumbCache = object : LruCache<Long, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: Long, value: Bitmap): Int {
                return value.byteCount / 1024
            }
        }

        initViews()
        loadDevicePhotos()
    }

    private fun initViews() {
        val toolbar = findViewById<Toolbar>(R.id.galleryToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        rvGalleryGrid = findViewById(R.id.rvGalleryGrid)
        tvSelectionOrderHint = findViewById(R.id.tvSelectionOrderHint)
        tvEmptyGallery = findViewById(R.id.tvEmptyGallery)
        pbGalleryLoading = findViewById(R.id.pbGalleryLoading)
        btnConfirmSelection = findViewById(R.id.btnConfirmSelection)
        btnClearSelection = findViewById(R.id.btnClearSelection)
        layoutAlbumChips = findViewById(R.id.layoutAlbumChips)

        galleryAdapter = GalleryGridAdapter()
        rvGalleryGrid.layoutManager = GridLayoutManager(this, 3)
        rvGalleryGrid.adapter = galleryAdapter

        findViewById<Button>(R.id.btnSystemPicker).setOnClickListener {
            systemPickerLauncher.launch("image/*")
        }

        btnClearSelection.setOnClickListener {
            selectedOrderUris.clear()
            galleryAdapter.notifyDataSetChanged()
            updateSelectionUI()
        }

        btnConfirmSelection.setOnClickListener {
            if (selectedOrderUris.isNotEmpty()) {
                val resultIntent = Intent().apply {
                    putParcelableArrayListExtra(EXTRA_SELECTED_URIS, ArrayList(selectedOrderUris))
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            }
        }

        updateSelectionUI()
    }

    private fun loadDevicePhotos() {
        pbGalleryLoading.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val loaded = mutableListOf<GalleryPhoto>()
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            try {
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                    val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        val name = if (nameCol != -1) cursor.getString(nameCol) ?: "image_$id" else "image_$id"
                        val bucket = if (bucketCol != -1) cursor.getString(bucketCol) ?: "其他" else "其他"
                        val w = if (widthCol != -1) cursor.getInt(widthCol) else 0
                        val h = if (heightCol != -1) cursor.getInt(heightCol) else 0

                        loaded.add(GalleryPhoto(id, uri, name, bucket, w, h))
                    }
                }
            } catch (_: Exception) {
            }

            withContext(Dispatchers.Main) {
                pbGalleryLoading.visibility = View.GONE
                allPhotos.clear()
                allPhotos.addAll(loaded)
                buildAlbumChips()
                applyAlbumFilter("全部图片")
            }
        }
    }

    private fun buildAlbumChips() {
        layoutAlbumChips.removeAllViews()
        val albums = mutableListOf("全部图片")
        val distinctBuckets = allPhotos.map { it.bucketName }.distinct()
        // 优先把 Screenshots 放到前面方便长图截图用户查找
        val sortedBuckets = distinctBuckets.sortedWith(
            compareByDescending<String> {
                it.contains("screenshot", ignoreCase = true) || it.contains("截屏") || it.contains("截图")
            }.thenBy { it }
        )
        albums.addAll(sortedBuckets)

        for (album in albums) {
            val count = if (album == "全部图片") allPhotos.size else allPhotos.count { it.bucketName == album }
            val btn = MaterialButton(this).apply {
                text = "$album ($count)"
                textSize = 12f
                isAllCaps = false
                cornerRadius = 40
                strokeWidth = 2
                strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#334155"))
                setPadding(28, 6, 28, 6)
                minimumHeight = 0
                minHeight = 76
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = 12
                layoutParams = lp
                setOnClickListener {
                    applyAlbumFilter(album)
                    refreshChipStyles()
                }
                tag = album
            }
            layoutAlbumChips.addView(btn)
        }
        refreshChipStyles()
    }

    private fun refreshChipStyles() {
        for (i in 0 until layoutAlbumChips.childCount) {
            val btn = layoutAlbumChips.getChildAt(i) as? MaterialButton ?: continue
            val isSelected = btn.tag == currentAlbum
            if (isSelected) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E88E5"))
                btn.setTextColor(Color.WHITE)
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0F172A"))
                btn.setTextColor(Color.parseColor("#CBD5E1"))
            }
        }
    }

    private fun applyAlbumFilter(album: String) {
        currentAlbum = album
        filteredPhotos.clear()
        if (album == "全部图片") {
            filteredPhotos.addAll(allPhotos)
        } else {
            filteredPhotos.addAll(allPhotos.filter { it.bucketName == album })
        }
        tvEmptyGallery.visibility = if (filteredPhotos.isEmpty()) View.VISIBLE else View.GONE
        galleryAdapter.notifyDataSetChanged()
        refreshChipStyles()
    }

    private fun toggleSelection(photo: GalleryPhoto) {
        val existingIdx = selectedOrderUris.indexOf(photo.uri)
        if (existingIdx != -1) {
            selectedOrderUris.removeAt(existingIdx)
            // 取消某一张时，后面的编号需要全部顺移刷新
            galleryAdapter.notifyDataSetChanged()
        } else {
            selectedOrderUris.add(photo.uri)
            val pos = filteredPhotos.indexOfFirst { it.uri == photo.uri }
            if (pos != -1) {
                galleryAdapter.notifyItemChanged(pos)
            }
        }
        updateSelectionUI()
    }

    private fun updateSelectionUI() {
        val count = selectedOrderUris.size
        tvSelectionOrderHint.text = getString(R.string.selection_order_hint, count)
        btnClearSelection.visibility = if (count > 0) View.VISIBLE else View.INVISIBLE
        btnConfirmSelection.isEnabled = count > 0
        btnConfirmSelection.alpha = if (count > 0) 1.0f else 0.5f
        btnConfirmSelection.text = if (count > 0) {
            getString(R.string.confirm_selection, count)
        } else {
            getString(R.string.confirm_selection_empty)
        }
    }

    private fun showGalleryPreviewDialog(startPos: Int) {
        if (filteredPhotos.isEmpty() || startPos !in filteredPhotos.indices) return
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_image_preview)

        val tvTitle = dialog.findViewById<TextView>(R.id.tvDialogTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvDialogSubtitle)
        val ivPreview = dialog.findViewById<ImageView>(R.id.ivDialogPreview)
        val pbLoading = dialog.findViewById<ProgressBar>(R.id.pbDialogLoading)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnDialogClose)
        val btnPrev = dialog.findViewById<Button>(R.id.btnDialogPrev)
        val btnNext = dialog.findViewById<Button>(R.id.btnDialogNext)
        val layoutOrderRow = dialog.findViewById<View>(R.id.layoutDialogOrderRow)

        // 相册选择器内隐藏排序行，改为切换或关闭
        layoutOrderRow.visibility = View.GONE

        var currentIndex = startPos

        fun renderPhoto(idx: Int) {
            val photo = filteredPhotos[idx]
            val orderNum = selectedOrderUris.indexOf(photo.uri)
            val orderTag = if (orderNum != -1) " [已选第 ${orderNum + 1} 张]" else ""
            tvTitle.text = "${photo.displayName}$orderTag"
            tvSubtitle.text = if (photo.width > 0 && photo.height > 0) {
                "${photo.width} × ${photo.height} px · ${photo.bucketName}"
            } else {
                photo.bucketName
            }

            btnPrev.isEnabled = idx > 0
            btnPrev.alpha = if (idx > 0) 1.0f else 0.4f
            btnNext.isEnabled = idx < filteredPhotos.size - 1
            btnNext.alpha = if (idx < filteredPhotos.size - 1) 1.0f else 0.4f

            ivPreview.setImageDrawable(null)
            pbLoading.visibility = View.VISIBLE
            val targetTag = photo.uri.toString()
            ivPreview.tag = targetTag

            lifecycleScope.launch(Dispatchers.IO) {
                val bmp = decodePreviewBitmap(photo.uri, 1600)
                withContext(Dispatchers.Main) {
                    if (ivPreview.tag == targetTag) {
                        pbLoading.visibility = View.GONE
                        ivPreview.setImageBitmap(bmp)
                    }
                }
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        btnPrev.setOnClickListener {
            if (currentIndex > 0) {
                currentIndex--
                renderPhoto(currentIndex)
            }
        }
        btnNext.setOnClickListener {
            if (currentIndex < filteredPhotos.size - 1) {
                currentIndex++
                renderPhoto(currentIndex)
            }
        }

        renderPhoto(currentIndex)
        dialog.show()
    }

    private fun decodePreviewBitmap(uri: Uri, maxDim: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / (sample * 2) >= maxDim || opts.outHeight / (sample * 2) >= maxDim) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        } catch (_: Exception) {
            null
        }
    }

    inner class GalleryGridAdapter : RecyclerView.Adapter<GalleryGridAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_gallery_image, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = filteredPhotos.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val photo = filteredPhotos[position]
            val orderIndex = selectedOrderUris.indexOf(photo.uri)

            if (orderIndex != -1) {
                holder.tvOrderBadge.setBackgroundResource(R.drawable.bg_badge_selected)
                holder.tvOrderBadge.text = (orderIndex + 1).toString()
                holder.viewSelectedOverlay.visibility = View.VISIBLE
            } else {
                holder.tvOrderBadge.setBackgroundResource(R.drawable.bg_badge_unselected)
                holder.tvOrderBadge.text = ""
                holder.viewSelectedOverlay.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    toggleSelection(filteredPhotos[pos])
                }
            }

            holder.btnZoomPreview.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    showGalleryPreviewDialog(pos)
                }
            }

            val cached = thumbCache.get(photo.id)
            if (cached != null && !cached.isRecycled) {
                holder.ivThumb.setImageBitmap(cached)
                holder.ivThumb.tag = photo.id
            } else {
                holder.ivThumb.setImageDrawable(null)
                holder.ivThumb.tag = photo.id
                lifecycleScope.launch(Dispatchers.IO) {
                    val bmp = loadGridThumb(photo)
                    if (bmp != null) {
                        thumbCache.put(photo.id, bmp)
                    }
                    withContext(Dispatchers.Main) {
                        if (holder.ivThumb.tag == photo.id && bmp != null) {
                            holder.ivThumb.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }

        private fun loadGridThumb(photo: GalleryPhoto): Bitmap? {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(photo.uri, Size(280, 280), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Thumbnails.getThumbnail(
                        contentResolver,
                        photo.id,
                        MediaStore.Images.Thumbnails.MINI_KIND,
                        null
                    )
                }
            } catch (_: Exception) {
                decodePreviewBitmap(photo.uri, 280)
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val ivThumb: ImageView = view.findViewById(R.id.ivGalleryThumb)
            val viewSelectedOverlay: View = view.findViewById(R.id.viewSelectedOverlay)
            val tvOrderBadge: TextView = view.findViewById(R.id.tvOrderBadge)
            val btnZoomPreview: ImageButton = view.findViewById(R.id.btnZoomPreview)
        }
    }
}
