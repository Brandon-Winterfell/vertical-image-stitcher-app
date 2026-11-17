package com.imagestitcher.vertical

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val selectedImages = mutableListOf<ImageItem>()
    private lateinit var adapter: ImageAdapter

    private lateinit var layoutEmpty: View
    private lateinit var rvImages: RecyclerView
    private lateinit var tvCountHint: TextView
    private lateinit var btnClearAll: Button
    private lateinit var btnStitch: Button
    private lateinit var layoutProgress: View
    private lateinit var tvProgressMessage: TextView

    // 可折叠参数设置面板
    private lateinit var layoutSettingsHeader: View
    private lateinit var layoutSettingsContent: View
    private lateinit var tvSettingsSummary: TextView
    private lateinit var tvSettingsToggleLabel: TextView
    private lateinit var ivSettingsToggle: ImageView
    private var isSettingsExpanded = false

    private lateinit var rgWidthMode: RadioGroup
    private lateinit var seekGap: SeekBar
    private lateinit var tvGapLabel: TextView
    private lateinit var rgBgColor: RadioGroup
    private lateinit var rgFormat: RadioGroup

    // 1. 有序相册选择器返回结果（严格按点选顺序）
    private val orderedGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val uris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableArrayListExtra(
                    GalleryPickerActivity.EXTRA_SELECTED_URIS,
                    Uri::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableArrayListExtra(GalleryPickerActivity.EXTRA_SELECTED_URIS)
            }
            if (!uris.isNullOrEmpty()) {
                addImages(uris)
            }
        }
    }

    // 2. 备用系统文件选择器（当拒绝相册权限时使用）
    private val fallbackSystemPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            addImages(uris)
        }
    }

    // 3. 权限申请回调
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasGalleryReadPermission()) {
            launchOrderedGalleryPicker()
        } else {
            Toast.makeText(this, R.string.permission_denied_fallback, Toast.LENGTH_LONG).show()
            fallbackSystemPickerLauncher.launch("image/*")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        updateUIState()
        updateSettingsSummary()
    }

    private fun initViews() {
        layoutEmpty = findViewById(R.id.layoutEmpty)
        rvImages = findViewById(R.id.rvSelectedImages)
        tvCountHint = findViewById(R.id.tvCountHint)
        btnClearAll = findViewById(R.id.btnClearAll)
        btnStitch = findViewById(R.id.btnStitch)
        layoutProgress = findViewById(R.id.layoutProgress)
        tvProgressMessage = findViewById(R.id.tvProgressMessage)

        layoutSettingsHeader = findViewById(R.id.layoutSettingsHeader)
        layoutSettingsContent = findViewById(R.id.layoutSettingsContent)
        tvSettingsSummary = findViewById(R.id.tvSettingsSummary)
        tvSettingsToggleLabel = findViewById(R.id.tvSettingsToggleLabel)
        ivSettingsToggle = findViewById(R.id.ivSettingsToggle)

        rgWidthMode = findViewById(R.id.rgWidthMode)
        seekGap = findViewById(R.id.seekGap)
        tvGapLabel = findViewById(R.id.tvGapLabel)
        rgBgColor = findViewById(R.id.rgBgColor)
        rgFormat = findViewById(R.id.rgFormat)

        adapter = ImageAdapter(
            scope = lifecycleScope,
            onMoveUp = { pos -> moveItem(pos, pos - 1) },
            onMoveDown = { pos -> moveItem(pos, pos + 1) },
            onRemove = { pos -> removeItem(pos) },
            onPreview = { pos -> showEnlargedPreview(pos) }
        )

        rvImages.layoutManager = LinearLayoutManager(this)
        rvImages.adapter = adapter
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btnSelectImages).setOnClickListener {
            openImagePickerWithPermissionCheck()
        }

        layoutEmpty.setOnClickListener {
            openImagePickerWithPermissionCheck()
        }

        btnClearAll.setOnClickListener {
            selectedImages.clear()
            adapter.submitList(emptyList())
            updateUIState()
        }

        layoutSettingsHeader.setOnClickListener {
            isSettingsExpanded = !isSettingsExpanded
            layoutSettingsContent.visibility = if (isSettingsExpanded) View.VISIBLE else View.GONE
            tvSettingsToggleLabel.text = getString(
                if (isSettingsExpanded) R.string.collapse_settings else R.string.expand_settings
            )
            ivSettingsToggle.setImageResource(
                if (isSettingsExpanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up
            )
        }

        rgWidthMode.setOnCheckedChangeListener { _, _ -> updateSettingsSummary() }
        rgBgColor.setOnCheckedChangeListener { _, _ -> updateSettingsSummary() }
        rgFormat.setOnCheckedChangeListener { _, _ -> updateSettingsSummary() }

        seekGap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvGapLabel.text = "图片纵向间距: $progress px"
                updateSettingsSummary()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnStitch.setOnClickListener {
            startStitching()
        }
    }

    private fun hasGalleryReadPermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                val full = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                val partial = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
                full || partial
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            }
            else -> {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    private fun openImagePickerWithPermissionCheck() {
        if (hasGalleryReadPermission()) {
            launchOrderedGalleryPicker()
        } else {
            val perms = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES
                )
                else -> arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            }
            requestPermissionLauncher.launch(perms)
        }
    }

    private fun launchOrderedGalleryPicker() {
        val intent = Intent(this, GalleryPickerActivity::class.java)
        orderedGalleryLauncher.launch(intent)
    }

    private fun updateSettingsSummary() {
        val widthLabel = when (rgWidthMode.checkedRadioButtonId) {
            R.id.rbWidthMin -> "最小宽度"
            R.id.rbWidthFirst -> "首图宽度"
            R.id.rbWidthOriginalCenter -> "原宽居中"
            else -> "最大宽度"
        }
        val bgLabel = if (rgBgColor.checkedRadioButtonId == R.id.rbBgBlack) "黑底" else "白底"
        val fmtLabel = if (rgFormat.checkedRadioButtonId == R.id.rbFormatPng) "PNG" else "JPG"
        tvSettingsSummary.text = "$widthLabel · 间距 ${seekGap.progress}px · $bgLabel · $fmtLabel"
    }

    private fun addImages(uris: List<Uri>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val newItems = mutableListOf<ImageItem>()
            for (uri in uris) {
                var fileName = "image_${System.currentTimeMillis()}"
                var fileSize = 0L

                // 获取文件名与文件大小
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                            if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                        }
                    }
                } catch (_: Exception) {}

                // 解析图片分辨率
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                } catch (_: Exception) {}

                val width = if (options.outWidth > 0) options.outWidth else 1080
                val height = if (options.outHeight > 0) options.outHeight else 1920

                newItems.add(
                    ImageItem(
                        id = UUID.randomUUID().toString(),
                        uri = uri,
                        name = fileName,
                        width = width,
                        height = height,
                        sizeBytes = fileSize
                    )
                )
            }

            withContext(Dispatchers.Main) {
                selectedImages.addAll(newItems)
                adapter.submitList(selectedImages.toList())
                updateUIState()
            }
        }
    }

    private fun moveItem(fromPos: Int, toPos: Int) {
        if (fromPos in selectedImages.indices && toPos in selectedImages.indices) {
            val item = selectedImages.removeAt(fromPos)
            selectedImages.add(toPos, item)
            adapter.submitList(selectedImages.toList())
            updateUIState()
        }
    }

    private fun removeItem(pos: Int) {
        if (pos in selectedImages.indices) {
            selectedImages.removeAt(pos)
            adapter.submitList(selectedImages.toList())
            updateUIState()
        }
    }

    private fun updateUIState() {
        val count = selectedImages.size
        if (count == 0) {
            layoutEmpty.visibility = View.VISIBLE
            rvImages.visibility = View.GONE
            btnClearAll.visibility = View.GONE
            tvCountHint.text = "暂未选择图片"
            btnStitch.isEnabled = false
            btnStitch.alpha = 0.5f
        } else {
            layoutEmpty.visibility = View.GONE
            rvImages.visibility = View.VISIBLE
            btnClearAll.visibility = View.VISIBLE
            tvCountHint.text = getString(R.string.selected_count, count)
            btnStitch.isEnabled = true
            btnStitch.alpha = 1.0f
        }
    }

    /**
     * 点击列表中的图片时放大预览大图，支持上一张/下一张切换及直接调整顺序
     */
    private fun showEnlargedPreview(startPos: Int) {
        if (selectedImages.isEmpty() || startPos !in selectedImages.indices) return

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_image_preview)

        val tvTitle = dialog.findViewById<TextView>(R.id.tvDialogTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvDialogSubtitle)
        val ivPreview = dialog.findViewById<ImageView>(R.id.ivDialogPreview)
        val pbLoading = dialog.findViewById<ProgressBar>(R.id.pbDialogLoading)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnDialogClose)
        val btnPrev = dialog.findViewById<Button>(R.id.btnDialogPrev)
        val btnNext = dialog.findViewById<Button>(R.id.btnDialogNext)
        val btnMoveUp = dialog.findViewById<Button>(R.id.btnDialogMoveUp)
        val btnMoveDown = dialog.findViewById<Button>(R.id.btnDialogMoveDown)

        var currentIndex = startPos

        fun updateOrderButtons(idx: Int) {
            tvTitle.text = "第 ${idx + 1} / ${selectedImages.size} 张 · ${selectedImages[idx].name}"
            btnPrev.isEnabled = idx > 0
            btnPrev.alpha = if (idx > 0) 1.0f else 0.4f
            btnNext.isEnabled = idx < selectedImages.size - 1
            btnNext.alpha = if (idx < selectedImages.size - 1) 1.0f else 0.4f

            btnMoveUp.isEnabled = idx > 0
            btnMoveUp.alpha = if (idx > 0) 1.0f else 0.4f
            btnMoveDown.isEnabled = idx < selectedImages.size - 1
            btnMoveDown.alpha = if (idx < selectedImages.size - 1) 1.0f else 0.4f
        }

        fun loadAndRender(idx: Int) {
            if (idx !in selectedImages.indices) return
            val item = selectedImages[idx]
            tvSubtitle.text = "${item.width} × ${item.height} px"
            updateOrderButtons(idx)

            ivPreview.setImageDrawable(null)
            pbLoading.visibility = View.VISIBLE
            val targetId = item.id
            ivPreview.tag = targetId

            lifecycleScope.launch(Dispatchers.IO) {
                val bmp = decodeHighResPreview(item.uri, 2048)
                withContext(Dispatchers.Main) {
                    if (ivPreview.tag == targetId) {
                        pbLoading.visibility = View.GONE
                        if (bmp != null) {
                            ivPreview.setImageBitmap(bmp)
                        } else {
                            Toast.makeText(this@MainActivity, "无法加载预览图", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        btnPrev.setOnClickListener {
            if (currentIndex > 0) {
                currentIndex--
                loadAndRender(currentIndex)
            }
        }

        btnNext.setOnClickListener {
            if (currentIndex < selectedImages.size - 1) {
                currentIndex++
                loadAndRender(currentIndex)
            }
        }

        btnMoveUp.setOnClickListener {
            if (currentIndex > 0) {
                moveItem(currentIndex, currentIndex - 1)
                currentIndex--
                updateOrderButtons(currentIndex)
            }
        }

        btnMoveDown.setOnClickListener {
            if (currentIndex < selectedImages.size - 1) {
                moveItem(currentIndex, currentIndex + 1)
                currentIndex++
                updateOrderButtons(currentIndex)
            }
        }

        loadAndRender(currentIndex)
        dialog.show()
    }

    private fun decodeHighResPreview(uri: Uri, maxDim: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
            var sample = 1
            while (opts.outWidth / (sample * 2) >= maxDim || opts.outHeight / (sample * 2) >= maxDim) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun startStitching() {
        if (selectedImages.isEmpty()) {
            Toast.makeText(this, R.string.no_images_selected, Toast.LENGTH_SHORT).show()
            return
        }

        // 解析设置
        val widthMode = when (rgWidthMode.checkedRadioButtonId) {
            R.id.rbWidthMin -> ImageStitcher.WidthMode.MIN_WIDTH
            R.id.rbWidthFirst -> ImageStitcher.WidthMode.FIRST_IMAGE
            R.id.rbWidthOriginalCenter -> ImageStitcher.WidthMode.ORIGINAL_CENTER
            else -> ImageStitcher.WidthMode.MAX_WIDTH
        }

        val gapPx = seekGap.progress

        val bgColor = when (rgBgColor.checkedRadioButtonId) {
            R.id.rbBgBlack -> Color.BLACK
            else -> Color.WHITE
        }

        val format = when (rgFormat.checkedRadioButtonId) {
            R.id.rbFormatPng -> ImageStitcher.OutputFormat.PNG
            else -> ImageStitcher.OutputFormat.JPEG
        }

        val options = ImageStitcher.StitchOptions(
            widthMode = widthMode,
            gapPx = gapPx,
            backgroundColor = bgColor,
            format = format,
            quality = 92
        )

        layoutProgress.visibility = View.VISIBLE
        tvProgressMessage.text = getString(R.string.stitching_progress)

        lifecycleScope.launch {
            try {
                val result = ImageStitcher.stitchVertically(
                    context = applicationContext,
                    items = selectedImages,
                    options = options,
                    onProgress = { step, total ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            tvProgressMessage.text = "正在拼接第 $step / $total 张图片…"
                        }
                    }
                )

                layoutProgress.visibility = View.GONE

                // 启动预览界面
                val intent = Intent(this@MainActivity, PreviewActivity::class.java).apply {
                    putExtra(PreviewActivity.EXTRA_FILE_PATH, result.outputFile.absolutePath)
                    putExtra(PreviewActivity.EXTRA_WIDTH, result.width)
                    putExtra(PreviewActivity.EXTRA_HEIGHT, result.height)
                    putExtra(PreviewActivity.EXTRA_FILE_SIZE, result.fileSizeBytes)
                }
                startActivity(intent)

            } catch (e: Exception) {
                layoutProgress.visibility = View.GONE
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.stitching_error, e.message ?: "未知异常"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
