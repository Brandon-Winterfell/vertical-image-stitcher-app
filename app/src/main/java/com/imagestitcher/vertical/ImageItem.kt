package com.imagestitcher.vertical

import android.net.Uri

data class ImageItem(
    val id: String,
    val uri: Uri,
    val name: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long
)