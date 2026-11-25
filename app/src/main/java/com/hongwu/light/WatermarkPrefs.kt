package com.hongwu.light

import android.content.Context

/** 水印显示偏好：拍摄时间开关等设置 */
object WatermarkPrefs {

    /** 日期文案配置数量：设置页提供三个可选配置 */
    const val CAPTION_PRESET_COUNT = 3

    /** SharedPreferences 文件名，与保存位置设置共用 */
    private const val PREFS_NAME = "frame_prefs"

    /** 是否展示拍摄时间的键，未写入时默认关闭 */
    private const val KEY_SHOW_SHOT_TIME = "show_shot_time"

    /** 是否启用日期文案（天数计数）的键，默认关闭 */
    private const val KEY_DAY_COUNT_ENABLED = "day_count_enabled"

    /** 日期文案配置起始时间（epochDay）的键前缀，-1 表示未设置 */
    private const val KEY_DAY_COUNT_START = "day_count_start"

    /** 日期文案配置模板的键前缀，用 {0} 表示天数 */
    private const val KEY_DAY_COUNT_TEXT = "day_count_text"

    /** 日期文案配置是否斜体的键前缀 */
    private const val KEY_CAPTION_ITALIC = "caption_italic"

    /** 日期文案配置是否加粗的键前缀 */
    private const val KEY_CAPTION_BOLD = "caption_bold"

    /** 日期文案配置字号倍数的键前缀（默认 1.0） */
    private const val KEY_CAPTION_SIZE = "caption_size_scale"

    /** 日期文案配置是否按年月日展示的键前缀（默认关闭，按纯天数） */
    private const val KEY_CAPTION_YMD = "caption_ymd"

    /** 日期文案配置别名的键前缀（默认空串，界面回退为"配置 N"） */
    private const val KEY_CAPTION_NAME = "caption_name"

    /** 当前选中的日期文案配置序号键 */
    private const val KEY_DAY_COUNT_ACTIVE = "day_count_active"

    /** 是否统一水印文字风格的键，默认关闭 */
    private const val KEY_UNIFY_ENABLED = "unify_style_enabled"

    /** 统一风格生效的相框风格集合键（存风格名，未写入时默认全部风格） */
    private const val KEY_UNIFY_STYLES = "unify_styles"

    /** 自定义字体文件路径的键，null 表示使用系统默认字体 */
    private const val KEY_FONT_PATH = "custom_font_path"

    /** 自定义字体展示名称的键 */
    private const val KEY_FONT_NAME = "custom_font_name"

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

    /** 读取指定配置的起始时间：返回 epochDay，未设置时返回 null */
    fun loadDayCountStart(context: Context, index: Int): Long? {
        val p = prefs(context)
        val key = slotKey(KEY_DAY_COUNT_START, index)  // 该配置的独立键
        // 配置 1 未写入新键时回退读取旧版单配置，保证升级后原有设置不丢失
        val raw = if (index != 0 || p.contains(key)) p.getLong(key, -1L)
        else p.getLong(KEY_DAY_COUNT_START, -1L)
        return raw.takeIf { it >= 0L }  // 负值视为未设置
    }

    /** 写入指定配置的起始时间（epochDay） */
    fun saveDayCountStart(context: Context, index: Int, epochDay: Long) {
        prefs(context).edit()
            .putLong(slotKey(KEY_DAY_COUNT_START, index), epochDay)  // 按配置分槽保存
            .apply()
    }

    /** 读取指定配置的文案模板，默认空串表示未配置 */
    fun loadDayCountText(context: Context, index: Int): String {
        val p = prefs(context)
        val stored = p.getString(slotKey(KEY_DAY_COUNT_TEXT, index), null)  // 该配置的独立键
        val legacy = if (index == 0) p.getString(KEY_DAY_COUNT_TEXT, "") else null  // 旧版单配置模板
        return stored ?: legacy ?: ""  // 新键优先，其次旧键，最后空串
    }

    /** 写入指定配置的文案模板 */
    fun saveDayCountText(context: Context, index: Int, text: String) {
        prefs(context).edit()
            .putString(slotKey(KEY_DAY_COUNT_TEXT, index), text)  // 按配置分槽保存
            .apply()
    }

    /** 读取指定配置的日期文案是否斜体，默认关闭 */
    fun loadCaptionItalic(context: Context, index: Int): Boolean {
        val p = prefs(context)
        val key = slotKey(KEY_CAPTION_ITALIC, index)  // 该配置的独立键
        // 配置 1 未写入新键时回退旧版单配置
        return if (index != 0 || p.contains(key)) p.getBoolean(key, false)
        else p.getBoolean(KEY_CAPTION_ITALIC, false)
    }

    /** 写入指定配置的日期文案是否斜体 */
    fun saveCaptionItalic(context: Context, index: Int, italic: Boolean) {
        prefs(context).edit()
            .putBoolean(slotKey(KEY_CAPTION_ITALIC, index), italic)  // 按配置分槽保存
            .apply()
    }

    /** 读取指定配置的日期文案是否加粗，默认关闭 */
    fun loadCaptionBold(context: Context, index: Int): Boolean {
        val p = prefs(context)
        val key = slotKey(KEY_CAPTION_BOLD, index)  // 该配置的独立键
        // 配置 1 未写入新键时回退旧版单配置
        return if (index != 0 || p.contains(key)) p.getBoolean(key, false)
        else p.getBoolean(KEY_CAPTION_BOLD, false)
    }

    /** 写入指定配置的日期文案是否加粗 */
    fun saveCaptionBold(context: Context, index: Int, bold: Boolean) {
        prefs(context).edit()
            .putBoolean(slotKey(KEY_CAPTION_BOLD, index), bold)  // 按配置分槽保存
            .apply()
    }

    /** 读取指定配置的日期文案字号倍数，默认 1.0 */
    fun loadCaptionSize(context: Context, index: Int): Float {
        val p = prefs(context)
        val key = slotKey(KEY_CAPTION_SIZE, index)  // 该配置的独立键
        // 配置 1 未写入新键时回退旧版单配置
        return if (index != 0 || p.contains(key)) p.getFloat(key, 1f)
        else p.getFloat(KEY_CAPTION_SIZE, 1f)
    }

    /** 写入指定配置的日期文案字号倍数 */
    fun saveCaptionSize(context: Context, index: Int, scale: Float) {
        prefs(context).edit()
            .putFloat(slotKey(KEY_CAPTION_SIZE, index), scale)  // 按配置分槽保存
            .apply()
    }

    /** 读取指定配置是否按年月日展示天数差，默认关闭 */
    fun loadCaptionYmd(context: Context, index: Int): Boolean =
        prefs(context).getBoolean(slotKey(KEY_CAPTION_YMD, index), false)  // 新功能无旧键，直接分槽

    /** 写入指定配置是否按年月日展示天数差 */
    fun saveCaptionYmd(context: Context, index: Int, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(slotKey(KEY_CAPTION_YMD, index), enabled)  // 按配置分槽保存
            .apply()
    }

    /** 读取指定配置的别名，默认空串表示未命名 */
    fun loadCaptionName(context: Context, index: Int): String =
        prefs(context).getString(slotKey(KEY_CAPTION_NAME, index), "") ?: ""  // 新功能无旧键，缺失时为空

    /** 写入指定配置的别名 */
    fun saveCaptionName(context: Context, index: Int, name: String) {
        prefs(context).edit()
            .putString(slotKey(KEY_CAPTION_NAME, index), name)  // 按配置分槽保存
            .apply()
    }

    /** 读取当前选中的日期文案配置序号，越界时回退到第一个配置 */
    fun loadDayCountActive(context: Context): Int =
        prefs(context).getInt(KEY_DAY_COUNT_ACTIVE, 0)
            .coerceIn(0, CAPTION_PRESET_COUNT - 1)

    /** 写入当前选中的日期文案配置序号 */
    fun saveDayCountActive(context: Context, index: Int) {
        prefs(context).edit()
            .putInt(KEY_DAY_COUNT_ACTIVE, index)  // 仅记录选中项
            .apply()
    }

    /** 读取"统一水印文字风格"开关，默认关闭 */
    fun loadUnifyEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UNIFY_ENABLED, false)

    /** 写入"统一水印文字风格"开关 */
    fun saveUnifyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_UNIFY_ENABLED, enabled)
            .apply()
    }

    /** 读取统一风格生效的相框风格名集合，未写入时默认全部风格 */
    fun loadUnifyStyles(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_UNIFY_STYLES, FrameStyle.entries.map { it.name }.toSet())
            ?.toSet()  // 拷贝一份，避免外部修改内部集合
            ?: FrameStyle.entries.map { it.name }.toSet()

    /** 写入统一风格生效的相框风格名集合 */
    fun saveUnifyStyles(context: Context, styles: Set<String>) {
        prefs(context).edit()
            .putStringSet(KEY_UNIFY_STYLES, styles)
            .apply()
    }

    /** 读取已导入字体的文件路径，未导入时返回 null */
    fun loadFontPath(context: Context): String? =
        prefs(context).getString(KEY_FONT_PATH, null)

    /** 写入已导入字体的文件路径，传 null 表示恢复系统默认字体 */
    fun saveFontPath(context: Context, path: String?) {
        prefs(context).edit()
            .putString(KEY_FONT_PATH, path)  // 空值即清除记录
            .apply()
    }

    /** 读取已导入字体的展示名称，未导入时返回 null */
    fun loadFontName(context: Context): String? =
        prefs(context).getString(KEY_FONT_NAME, null)

    /** 写入已导入字体的展示名称，传 null 表示清除 */
    fun saveFontName(context: Context, name: String?) {
        prefs(context).edit()
            .putString(KEY_FONT_NAME, name)  // 空值即清除记录
            .apply()
    }

    /** 生成按配置序号区分的键名，如 day_count_start_1 */
    private fun slotKey(base: String, index: Int): String = "${base}_$index"

    /** SharedPreferences 实例 */
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
