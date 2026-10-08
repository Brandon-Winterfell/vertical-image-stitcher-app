package com.imagestitcher.vertical

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object ImageStitcher {

    enum class WidthMode {
        MAX_WIDTH,
        MIN_WIDTH,
        FIRST_IMAGE,
        ORIGINAL_CENTER
    }

    enum class OutputFormat {
        JPEG,
        PNG,
        WEBP
    }

    data class StitchOptions(
        val widthMode: WidthMode = WidthMode.MAX_WIDTH,
        val gapPx: Int = 0,
        val backgroundColor: Int = Color.WHITE,
        val format: OutputFormat = OutputFormat.JPEG,
        val quality: Int = 92
    )

    data class StitchResult(
        val outputFile: File,
        val width: Int,
        val height: Int,
        val fileSizeBytes: Long
    )

    suspend fun stitchVertically(
        context: Context,
        items: List<ImageItem>,
        options: StitchOptions,
        onProgress: (step: Int, total: Int) -> Unit = { _, _ -> }
    ): StitchResult = withContext(Dispatchers.Default) {
        if (items.isEmpty()) {
            throw IllegalArgumentException("请至少选择一张图片")
        }

        // 1. 获取所有图片的尺寸
        val dimensions = items.map { item ->
            getImageBounds(context, item)
        }

        // 2. 根据宽度模式确定画布宽度
        val targetWidth: Int = when (options.widthMode) {
            WidthMode.MAX_WIDTH -> dimensions.maxOf { it.first }
            WidthMode.MIN_WIDTH -> dimensions.minOf { it.first }
            WidthMode.FIRST_IMAGE -> dimensions.first().first
            WidthMode.ORIGINAL_CENTER -> dimensions.maxOf { it.first }
        }

        // 3. 计算每张图片放置的目标尺寸及纵向偏移
        val destRects = mutableListOf<Rect>()
        var currentY = 0

        for (i in dimensions.indices) {
            val (srcW, srcH) = dimensions[i]
            val destW: Int
            val destH: Int
            val destX: Int

            when (options.widthMode) {
                WidthMode.ORIGINAL_CENTER -> {
                    destW = srcW
                    destH = srcH
                    destX = (targetWidth - destW) / 2
                }
                else -> {
                    destW = targetWidth
                    destH = if (srcW > 0) ((srcH.toDouble() * targetWidth) / srcW).toInt() else srcH
                    destX = 0
                }
            }

            destRects.add(Rect(destX, currentY, destX + destW, currentY + destH))
            currentY += destH
            if (i < dimensions.size - 1) {
                currentY += options.gapPx
            }
        }

        val totalHeight = currentY

        // 安全检查与自适应降采样（防止超大画布 OOM 或超出 Android Canvas 最大尺寸）
        var scaleFactor = 1.0f
        val maxCanvasDim = 16384
        val maxTotalPixels = 36_000_000L // 约 3600万像素

        val totalPixels = targetWidth.toLong() * totalHeight.toLong()
        if (targetWidth > maxCanvasDim || totalHeight > maxCanvasDim || totalPixels > maxTotalPixels) {
            val dimScale = min(maxCanvasDim.toFloat() / max(targetWidth, totalHeight), 1.0f)
            val pixelScale = kotlin.math.sqrt(maxTotalPixels.toDouble() / totalPixels.toDouble()).toFloat()
            scaleFactor = min(dimScale, pixelScale)
        }

        val finalWidth = max((targetWidth * scaleFactor).toInt(), 1)
        val finalHeight = max((totalHeight * scaleFactor).toInt(), 1)

        // 4. 分配大位图画布
        val stitchedBitmap = Bitmap.createBitmap(
            finalWidth,
            finalHeight,
            if (options.format == OutputFormat.PNG) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        )
        val canvas = Canvas(stitchedBitmap)
        canvas.drawColor(options.backgroundColor)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // 5. 逐张加载图片并绘制到 Canvas，随后立即回收以节省内存
        for (i in items.indices) {
            onProgress(i + 1, items.size)
            val item = items[i]
            val unscaledDest = destRects[i]

            // 目标矩形应用缩放
            val scaledDest = Rect(
                (unscaledDest.left * scaleFactor).toInt(),
                (unscaledDest.top * scaleFactor).toInt(),
                (unscaledDest.right * scaleFactor).toInt(),
                (unscaledDest.bottom * scaleFactor).toInt()
            )

            // 智能解码单张图片
            val itemBitmap = decodeSampledBitmap(
                context,
                item,
                scaledDest.width(),
                scaledDest.height()
            )

            if (itemBitmap != null) {
                val srcRect = Rect(0, 0, itemBitmap.width, itemBitmap.height)
                canvas.drawBitmap(itemBitmap, srcRect, scaledDest, paint)
                if (itemBitmap != stitchedBitmap) {
                    itemBitmap.recycle()
                }
            }
        }

        // 6. 保存到临时缓存文件供后续预览与分享
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val extension = when (options.format) {
            OutputFormat.JPEG -> "jpg"
            OutputFormat.PNG -> "png"
            OutputFormat.WEBP -> "webp"
        }
        val compressFormat = when (options.format) {
            OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
            OutputFormat.PNG -> Bitmap.CompressFormat.PNG
            OutputFormat.WEBP -> Bitmap.CompressFormat.WEBP
        }

        val outputFile = File(context.cacheDir, "STITCH_${timeStamp}.$extension")
        withContext(Dispatchers.IO) {
            FileOutputStream(outputFile).use { outStream ->
                stitchedBitmap.compress(compressFormat, options.quality, outStream)
                outStream.flush()
            }
        }

        val fileSizeBytes = outputFile.length()
        stitchedBitmap.recycle()

        StitchResult(
            outputFile = outputFile,
            width = finalWidth,
            height = finalHeight,
            fileSizeBytes = fileSizeBytes
        )
    }

    private fun getImageBounds(context: Context, item: ImageItem): Pair<Int, Int> {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(item.uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            if (options.outWidth > 0 && options.outHeight > 0) {
                Pair(options.outWidth, options.outHeight)
            } else {
                Pair(item.width, item.height)
            }
        } catch (e: Exception) {
            Pair(max(item.width, 100), max(item.height, 100))
        }
    }

    private fun decodeSampledBitmap(
        context: Context,
        item: ImageItem,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(item.uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            var inSampleSize = 1
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(item.uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (e: Exception) {
            null
        }
    }
}