package com.hongwu.light

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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
 * @param showShotTime 是否在拍摄参数位置展示拍摄时间（开启后不再展示快门、光圈等参数）
 * @param dayCaption 日期文案（如"小朋友出生88天"），非空时优先级最高，直接占用参数位置
 */
fun buildWatermarkLines(
    info: ShotInfo,
    style: FrameStyle,
    custom: String = "",
    showShotTime: Boolean = false,
    dayCaption: String? = null
): List<String> = when (style) {
    FrameStyle.GRADIENT -> buildGradientLines(info, custom, showShotTime, dayCaption)  // 渐变风格水印
    FrameStyle.WHITE_MOUNT -> buildMountLines(info, custom, showShotTime, dayCaption)  // 展框风格水印
    FrameStyle.DARK_PLATE -> buildDarkPlateLines(info, custom, showShotTime, dayCaption)  // 暗色底板风格水印
}

/** 品牌标语表：仅收录已确认无误的品牌，未命中则不显示标语行 */
private val BRAND_TAGLINES = mapOf(
    "HASSELBLAD" to "CREATE TO INSPIRE"
)

/** 渐变风格水印：第1行品牌型号，第2行拍摄参数；自定义水印整体替换 */
private fun buildGradientLines(info: ShotInfo, custom: String, showShotTime: Boolean, dayCaption: String?): List<String> {
    // 用户输入优先：非空时只显示用户输入的单行
    val customLine = custom.trim().takeIf { it.isNotEmpty() }
    if (customLine != null) return listOf(customLine)

    // 第1行：品牌型号
    val line1 = listOfNotNull(info.brand, info.model)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" ")

    // 第2行：日期文案 / 拍摄时间 / 拍摄参数，按优先级取第一项
    val line2 = buildParamsLine(info, showShotTime, "  ·  ", dayCaption)

    return when {
        line1 != null && line2 != null -> listOf(line1, line2)
        line2 != null -> listOf(line2)
        line1 != null -> listOf(line1, line2 ?: "")
        else -> listOf(currentTimestampLine())  // fallback: 当前时间戳
    }
}

/** 展框风格水印：品牌行 + 标语行（可缺）+ 参数行 */
private fun buildMountLines(info: ShotInfo, custom: String, showShotTime: Boolean, dayCaption: String?): List<String> {
    // 参数行：日期文案优先，其次拍摄时间，最后焦距 光圈 快门 ISO
    val params = buildParamsLine(info, showShotTime, " ", dayCaption)

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
private fun buildDarkPlateLines(info: ShotInfo, custom: String, showShotTime: Boolean, dayCaption: String?): List<String> {
    // 参数段：日期文案优先，其次拍摄时间，最后焦距 光圈 快门 ISO
    val params = buildParamsLine(info, showShotTime, " ", dayCaption).orEmpty()

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

/** EXIF 拍摄时间的标准格式 */
private const val EXIF_TIME_PATTERN = "yyyy:MM:dd HH:mm:ss"

/**
 * 拍摄参数段，按优先级取第一项：
 * 日期文案 > 拍摄时间（开关开启时）> 焦距 / 光圈 / 快门 / ISO
 */
private fun buildParamsLine(info: ShotInfo, showShotTime: Boolean, separator: String, dayCaption: String?): String? {
    // 日期文案优先级最高：能算出天数时直接占用参数位置
    dayCaption?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    // 开启时间展示时优先取 EXIF 原始拍摄时间
    if (showShotTime) {
        formatShotTime(info.dateTime)?.let { return it }
    }
    // 默认参数段：按顺序拼接非空项
    return listOfNotNull(info.focalLength, info.aperture, info.shutter, info.iso)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator)
}

/** 拍摄时间格式化：EXIF 原始格式 "yyyy:MM:dd HH:mm:ss" 转为 "yyyy-MM-dd HH:mm"，解析失败时原样返回 */
private fun formatShotTime(raw: String?): String? {
    // 空值视为无拍摄时间
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // 解析 EXIF 固定格式；单独构造实例避免 SimpleDateFormat 非线程安全问题
    val date = runCatching {
        SimpleDateFormat(EXIF_TIME_PATTERN, Locale.US).parse(text)
    }.getOrNull() ?: return text  // 格式异常时直接显示原文
    // 输出去掉秒的紧凑格式
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(date)
}

/**
 * 生成日期文案：天数 = 照片拍摄日期 − 起始日期（如出生日期），按天计算
 * 模板中的 {0} 会被替换为天数；模板未包含 {0} 时天数追加在末尾
 * @param startEpochDay 起始日期（epochDay）
 * @param template 文案模板，如 "小朋友出生{0}天"
 * @return 生成好的文案；照片无拍摄时间或模板为空时返回 null（水印回退到拍摄时间/参数）
 */
fun buildDayCountCaption(info: ShotInfo, startEpochDay: Long, template: String): String? {
    // 模板为空视为未配置
    val text = template.trim().takeIf { it.isNotEmpty() } ?: return null
    // 照片必须有可解析的拍摄日期才能计算天数
    val shootDate = parseShotDate(info.dateTime) ?: return null
    // 起始日期非法时同样放弃生成
    val start = runCatching { LocalDate.ofEpochDay(startEpochDay) }.getOrNull() ?: return null
    // 两个日期之间的天数差（拍摄日 − 起始日）
    val days = ChronoUnit.DAYS.between(start, shootDate)
    // 有占位符则替换，没有则追加天数
    return if (text.contains("{0}")) text.replace("{0}", days.toString()) else "$text $days"
}

/** 解析 EXIF 拍摄时间中的日期部分，兼容带/不带时分秒两种写法，解析失败返回 null */
private fun parseShotDate(raw: String?): LocalDate? {
    // 空值视为无拍摄时间
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // 优先按完整时间格式解析
    try {
        return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(EXIF_TIME_PATTERN, Locale.US)).toLocalDate()
    } catch (e: Exception) {
        // 退化到仅日期格式
        return try {
            LocalDate.parse(text, DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.US))
        } catch (e2: Exception) {
            null
        }
    }
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
