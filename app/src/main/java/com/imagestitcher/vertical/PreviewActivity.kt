package com.imagestitcher.vertical

import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Locale

class PreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_FILE_SIZE = "extra_file_size"
    }

    private var currentFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        val toolbar = findViewById<Toolbar>(R.id.previewToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (filePath.isNullOrEmpty()) {
            Toast.makeText(this, "未找到图片文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(this, "图片文件不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentFile = file

        val width = intent.getIntExtra(EXTRA_WIDTH, 0)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
        val fileSize = intent.getLongExtra(EXTRA_FILE_SIZE, file.length())

        val tvInfo = findViewById<TextView>(R.id.tvStitchedInfo)
        val readableSize = formatFileSize(fileSize)
        tvInfo.text = "分辨率: $width × $height px  |  大小: $readableSize"

        val ivPreview = findViewById<ImageView>(R.id.ivPreviewStitched)

        // 异步解码并在界面显示
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    ivPreview.setImageBitmap(bitmap)
                } else {
                    Toast.makeText(this@PreviewActivity, "图片预览加载失败", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveImageToGallery(file)
        }

        findViewById<Button>(R.id.btnShare).setOnClickListener {
            shareImage(file)
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f MB", size.toDouble() / (1024 * 1024))
            size >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", size.toDouble() / 1024)
            else -> "$size B"
        }
    }

    private fun saveImageToGallery(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mimeType = if (file.name.endsWith(".png", true)) "image/png" else "image/jpeg"
                val displayName = "Stitched_${System.currentTimeMillis()}.${if (file.name.endsWith(".png", true)) "png" else "jpg"}"

                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VerticalStitcher")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                val uri = contentResolver.insert(collection, contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { outStream ->
                        FileInputStream(file).use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, contentValues, null, null)
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PreviewActivity, R.string.save_success, Toast.LENGTH_LONG).show()
                    }
                } else {
                    throw Exception("无法创建媒体库条目")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PreviewActivity, getString(R.string.save_failed) + ": " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareImage(file: File) {
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )

            val mimeType = if (file.name.endsWith(".png", true)) "image/png" else "image/jpeg"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_image)))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: " + e.message, Toast.LENGTH_SHORT).show()
        }
    }
}