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

/**
 * 按相框风格生成水印文本行
 * @param info 已读取的 EXIF 信息
 * @param style 相框风格（渐变两行 / 展框三行）
 * @param custom 用户输入的自定义水印
 */
fun buildWatermarkLines(info: ShotInfo, style: FrameStyle, custom: String = ""): List<String> = when (style) {
    FrameStyle.GRADIENT -> buildGradientLines(info, custom)  // 渐变风格水印
    FrameStyle.WHITE_MOUNT -> buildMountLines(info, custom)  // 展框风格水印
    FrameStyle.DARK_PLATE -> buildDarkPlateLines(info, custom)  // 暗色底板风格水印
}

/** 品牌标语表：仅收录已确认无误的品牌，未命中则不显示标语行 */
private val BRAND_TAGLINES = mapOf(
    "HASSELBLAD" to "CREATE TO INSPIRE"
)

/** 渐变风格水印：第1行品牌型号，第2行拍摄参数；自定义水印整体替换 */
private fun buildGradientLines(info: ShotInfo, custom: String): List<String> {
    // 用户输入优先：非空时只显示用户输入的单行
    val customLine = custom.trim().takeIf { it.isNotEmpty() }
    if (customLine != null) return listOf(customLine)

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
        else -> listOf(currentTimestampLine())  // fallback: 当前时间戳
    }
}

/** 展框风格水印：品牌行 + 标语行（可缺）+ 参数行 */
private fun buildMountLines(info: ShotInfo, custom: String): List<String> {
    // 参数行：焦距 光圈 快门 ISO，用单空格分隔以贴合展框排版
    val params = listOfNotNull(info.focalLength, info.aperture, info.shutter, info.iso)
        .joinToString(" ")
        .takeIf { it.isNotEmpty() }

    // 自定义水印优先作为品牌行，大写以对齐展框风格
    val customBrand = custom.trim().takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)

    // EXIF 品牌名：Make 常带公司后缀（如 "NIKON CORPORATION"），只取首个单词
    val exifBrand = info.brand?.trim()?.split(" ")?.firstOrNull()
        ?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    // 品牌行取值优先级：自定义 > EXIF 品牌 > EXIF 型号 > 当前时间戳
    val brand = customBrand
        ?: exifBrand
        ?: info.model?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        ?: currentTimestampLine()

    // 标语行只在 EXIF 品牌命中内置标语表时出现，自定义水印不臆造标语
    val tagline = if (customBrand == null) exifBrand?.let { BRAND_TAGLINES[it] } else null

    return when {
        tagline != null && params != null -> listOf(brand, tagline, params)  // 三行完整版式
        tagline != null -> listOf(brand, tagline)  // 品牌 + 标语
        params != null -> listOf(brand, params)  // 品牌 + 参数
        else -> listOf(brand)  // 仅品牌行
    }
}

/** 无任何 EXIF 信息时使用的兜底文本行 */
private fun currentTimestampLine(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

/** 暗色底板风格水印：固定返回三段 [品牌, 型号, 参数]，渲染时品牌与型号同处一行混排 */
private fun buildDarkPlateLines(info: ShotInfo, custom: String): List<String> {
    // 参数段：焦距 光圈 快门 ISO，单空格分隔
    val params = listOfNotNull(info.focalLength, info.aperture, info.shutter, info.iso)
        .joinToString(" ")

    // 自定义水印整体占据品牌段，此时不再拼接型号，避免与用户输入混排
    val customBrand = custom.trim().takeIf { it.isNotEmpty() }

    // 品牌段：EXIF Make 常为全大写，转为首字母大写以贴合该风格的排版
    val brand = toTitleCase(customBrand ?: info.brand?.trim().orEmpty())

    // 型号段：保留厂商原始写法（如 "d5300"）
    val model = if (customBrand != null) "" else info.model?.trim().orEmpty()

    // 三段全空时用时间戳兜底，保证渲染端下标始终可用
    if (brand.isEmpty() && model.isEmpty() && params.isEmpty()) {
        return listOf(currentTimestampLine(), "", "")
    }
    return listOf(brand, model, params)
}

/** 全大写字符串转首字母大写，如 "NIKON" 转为 "Nikon"；其他写法原样保留 */
private fun toTitleCase(raw: String): String {
    if (raw.isEmpty()) return raw  // 空串直接返回
    // 仅处理纯大写输入，避免破坏 "EOS R5" 这类已有大小写的品牌名
    val allUpper = raw.any { it.isLetter() } && raw == raw.uppercase(Locale.ROOT)
    if (!allUpper) return raw
    return raw.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() }  // 首字母大写
}

// -------- 格式化工具函数 --------

/** 解析 "分子/分母" 形式的分数为 Double，解析失败或分母为 0 时返回 null */
private fun parseRatio(raw: String): Double? {
    val parts = raw.split("/")
    if (parts.size != 2) return null
    val num = parts[0].toDoubleOrNull() ?: return null
    val den = parts[1].toDoubleOrNull() ?: return null
    if (den == 0.0) return null
    return num / den
}

/** 将曝光时间转为可读格式，不足 1 秒用 "1/10" 分数格式，≥1 秒用 "2s" */
private fun formatShutterSpeed(raw: String): String {
    // 统一换算成秒：兼容分数 "1/10" 与小数 "0.1" 两种 EXIF 存储格式
    val seconds: Double = if (raw.contains("/")) {
        parseRatio(raw) ?: return raw
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
    val fVal = parseRatio(raw) ?: return raw
    return if (fVal == fVal.toLong().toDouble()) "f/${fVal.toLong()}"
    else String.format(Locale.US, "f/%.1f", fVal)
}

/** 将焦距转为可读格式，如 "24mm" */
private fun formatFocalLength(raw: String): String {
    val mm = parseRatio(raw) ?: return raw
    return if (mm == mm.toLong().toDouble()) "${mm.toLong()}mm"
    else String.format(Locale.US, "%.1fmm", mm)
}
