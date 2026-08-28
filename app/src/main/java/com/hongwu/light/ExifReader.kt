package com.hongwu.light

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

/** 拍摄参数信息（扩展版） */
data class ShotInfo(
    val brand: String? = null,       // 相机品牌 (TAG_MAKE)
    val model: String? = null,       // 相机型号 (TAG_MODEL)
    val focalLength: String? = null, // 焦距 (TAG_FOCAL_LENGTH)
    val aperture: String? = null,    // 光圈 (TAG_F_NUMBER)
    val shutter: String? = null,     // 快门 (TAG_EXPOSURE_TIME)
    val iso: String? = null,         // ISO (TAG_ISO_SPEED_RATINGS)
    val dateTime: String? = null,    // 拍摄时间 (TAG_DATETIME_ORIGINAL)
)

/** 从图片 Uri 中读取全部可用 EXIF 信息 */
fun readExifInfo(context: Context, uri: Uri): ShotInfo {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            ShotInfo(
                brand = exif.getAttribute(ExifInterface.TAG_MAKE),
                model = exif.getAttribute(ExifInterface.TAG_MODEL),
                focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                    ?.let { formatFocalLength(it) },
                aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                    ?.let { formatAperture(it) },
                shutter = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                    ?.let { formatShutterSpeed(it) },
                iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                    ?.let { "ISO $it" },
                dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            )
        } ?: ShotInfo()
    } catch (e: Exception) {
        e.printStackTrace()
        ShotInfo()
    }
}

/** 生成水印文本列表（两行）：
 *  第1行：品牌型号
 *  第2行：拍摄参数
 *  若全部读不到，返回当前时间戳作为唯一一行
 */
fun buildWatermarkLines(context: Context, uri: Uri): List<String> {
    val info = readExifInfo(context, uri)

    // 第1行：品牌型号
    val line1 = listOfNotNull(info.brand, info.model)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" ")

    // 第2行：拍摄参数
    val line2 = listOfNotNull(info.focalLength, info.aperture, info.shutter, info.iso)
        .takeIf { it.isNotEmpty() }
        ?.joinToString("  ·  ")

    return when {
        line1 != null && line2 != null -> listOf(line1, line2)
        line2 != null -> listOf(line2)
        line1 != null -> listOf(line1, line2 ?: "")
        else -> {
            // fallback: 当前时间戳
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            listOf(ts)
        }
    }
}

// -------- 格式化工具函数 --------

/** 将曝光时间转为可读格式，不足 1 秒用 "1/10" 分数格式，≥1 秒用 "2s" */
private fun formatShutterSpeed(raw: String): String {
    // 统一换算成秒：兼容分数 "1/10" 与小数 "0.1" 两种 EXIF 存储格式
    val seconds: Double = if (raw.contains("/")) {
        val parts = raw.split("/")
        val num = parts[0].toDoubleOrNull() ?: return raw
        val den = parts[1].toDoubleOrNull() ?: return raw
        if (den == 0.0) return raw
        num / den
    } else {
        raw.toDoubleOrNull() ?: return raw
    }

    if (seconds <= 0.0) return raw

    return if (seconds < 1.0) {
        // 不足 1 秒：转为 1/N 格式，如 0.1 -> 1/10、0.004 -> 1/250
        "1/${round(1.0 / seconds).toInt()}"
    } else {
        // ≥1 秒：保留秒表示
        if (seconds == seconds.toLong().toDouble()) "${seconds.toLong()}s"
        else String.format(Locale.US, "%.1fs", seconds)
    }
}

/** 将光圈值转为可读格式，如 "f/2.8" */
private fun formatAperture(raw: String): String {
    val parts = raw.split("/")
    if (parts.size == 2) {
        val num = parts[0].toDoubleOrNull() ?: return raw
        val den = parts[1].toDoubleOrNull() ?: return raw
        val fVal = num / den
        return if (fVal == fVal.toLong().toDouble()) "f/${fVal.toLong()}"
        else String.format(Locale.US, "f/%.1f", fVal)
    }
    return raw
}

/** 将焦距转为可读格式，如 "24mm" */
private fun formatFocalLength(raw: String): String {
    val parts = raw.split("/")
    if (parts.size == 2) {
        val num = parts[0].toDoubleOrNull() ?: return raw
        val den = parts[1].toDoubleOrNull() ?: return raw
        val mm = num / den
        return if (mm == mm.toLong().toDouble()) "${mm.toLong()}mm"
        else String.format(Locale.US, "%.1fmm", mm)
    }
    return raw
}
