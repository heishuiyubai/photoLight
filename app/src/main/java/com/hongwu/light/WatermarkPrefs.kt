package com.hongwu.light

import android.content.Context

/** 水印显示偏好：拍摄时间开关等设置 */
object WatermarkPrefs {

    /** SharedPreferences 文件名，与保存位置设置共用 */
    private const val PREFS_NAME = "frame_prefs"

    /** 是否展示拍摄时间的键，未写入时默认关闭 */
    private const val KEY_SHOW_SHOT_TIME = "show_shot_time"

    /** 是否启用日期文案（天数计数）的键，默认关闭 */
    private const val KEY_DAY_COUNT_ENABLED = "day_count_enabled"

    /** 日期文案起始时间（epochDay）的键，-1 表示未设置 */
    private const val KEY_DAY_COUNT_START = "day_count_start"

    /** 日期文案模板的键，用 {0} 表示天数 */
    private const val KEY_DAY_COUNT_TEXT = "day_count_text"

    /** 日期文案是否斜体的键 */
    private const val KEY_CAPTION_ITALIC = "caption_italic"

    /** 日期文案是否加粗的键 */
    private const val KEY_CAPTION_BOLD = "caption_bold"

    /** 日期文案字号倍数的键（默认 1.0） */
    private const val KEY_CAPTION_SIZE = "caption_size_scale"

    /** 读取"展示拍摄时间"开关，默认关闭以保持原有参数水印 */
    fun loadShowShotTime(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_SHOT_TIME, false)

    /** 写入"展示拍摄时间"开关 */
    fun saveShowShotTime(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SHOW_SHOT_TIME, enabled)  // 布尔开关
            .apply()  // 异步落盘
    }

    /** 读取日期文案开关，默认关闭 */
    fun loadDayCountEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DAY_COUNT_ENABLED, false)

    /** 写入日期文案开关 */
    fun saveDayCountEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_DAY_COUNT_ENABLED, enabled)
            .apply()
    }

    /** 读取日期文案起始时间：返回 epochDay，未设置时返回 null */
    fun loadDayCountStart(context: Context): Long? =
        prefs(context).getLong(KEY_DAY_COUNT_START, -1L).takeIf { it >= 0L }

    /** 写入日期文案起始时间（epochDay） */
    fun saveDayCountStart(context: Context, epochDay: Long) {
        prefs(context).edit()
            .putLong(KEY_DAY_COUNT_START, epochDay)
            .apply()
    }

    /** 读取日期文案模板，默认空串表示未配置 */
    fun loadDayCountText(context: Context): String =
        prefs(context).getString(KEY_DAY_COUNT_TEXT, "").orEmpty()

    /** 写入日期文案模板 */
    fun saveDayCountText(context: Context, text: String) {
        prefs(context).edit()
            .putString(KEY_DAY_COUNT_TEXT, text)
            .apply()
    }

    /** 读取日期文案是否斜体，默认关闭 */
    fun loadCaptionItalic(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CAPTION_ITALIC, false)

    /** 写入日期文案是否斜体 */
    fun saveCaptionItalic(context: Context, italic: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_CAPTION_ITALIC, italic)
            .apply()
    }

    /** 读取日期文案是否加粗，默认关闭 */
    fun loadCaptionBold(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CAPTION_BOLD, false)

    /** 写入日期文案是否加粗 */
    fun saveCaptionBold(context: Context, bold: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_CAPTION_BOLD, bold)
            .apply()
    }

    /** 读取日期文案字号倍数，默认 1.0 */
    fun loadCaptionSize(context: Context): Float =
        prefs(context).getFloat(KEY_CAPTION_SIZE, 1f)

    /** 写入日期文案字号倍数 */
    fun saveCaptionSize(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_CAPTION_SIZE, scale)
            .apply()
    }

    /** SharedPreferences 实例 */
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
