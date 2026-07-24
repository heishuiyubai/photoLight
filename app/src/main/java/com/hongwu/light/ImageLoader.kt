package com.hongwu.light

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** 从 Uri 加载 Bitmap，采样降采样以节省内存 */
suspend fun loadBitmapFromUri(
    context: Context,
    uri: Uri
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }

        val targetSize = 2048
        var sampleSize = 1
        if (opts.outHeight > targetSize || opts.outWidth > targetSize) {
            sampleSize = max(
                opts.outHeight / targetSize,
                opts.outWidth / targetSize
            )
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
