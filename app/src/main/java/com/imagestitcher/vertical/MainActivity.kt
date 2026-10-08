package com.imagestitcher.vertical

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

    private lateinit var rgWidthMode: RadioGroup
    private lateinit var seekGap: SeekBar
    private lateinit var tvGapLabel: TextView
    private lateinit var rgBgColor: RadioGroup
    private lateinit var rgFormat: RadioGroup

    private val selectImagesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            addImages(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        updateUIState()
    }

    private fun initViews() {
        layoutEmpty = findViewById(R.id.layoutEmpty)
        rvImages = findViewById(R.id.rvSelectedImages)
        tvCountHint = findViewById(R.id.tvCountHint)
        btnClearAll = findViewById(R.id.btnClearAll)
        btnStitch = findViewById(R.id.btnStitch)
        layoutProgress = findViewById(R.id.layoutProgress)
        tvProgressMessage = findViewById(R.id.tvProgressMessage)

        rgWidthMode = findViewById(R.id.rgWidthMode)
        seekGap = findViewById(R.id.seekGap)
        tvGapLabel = findViewById(R.id.tvGapLabel)
        rgBgColor = findViewById(R.id.rgBgColor)
        rgFormat = findViewById(R.id.rgFormat)

        adapter = ImageAdapter(
            scope = lifecycleScope,
            onMoveUp = { pos -> moveItem(pos, pos - 1) },
            onMoveDown = { pos -> moveItem(pos, pos + 1) },
            onRemove = { pos -> removeItem(pos) }
        )

        rvImages.layoutManager = LinearLayoutManager(this)
        rvImages.adapter = adapter
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btnSelectImages).setOnClickListener {
            selectImagesLauncher.launch("image/*")
        }

        layoutEmpty.setOnClickListener {
            selectImagesLauncher.launch("image/*")
        }

        btnClearAll.setOnClickListener {
            selectedImages.clear()
            adapter.submitList(emptyList())
            updateUIState()
        }

        seekGap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvGapLabel.text = "图片纵向间距: $progress px"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnStitch.setOnClickListener {
            startStitching()
        }
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