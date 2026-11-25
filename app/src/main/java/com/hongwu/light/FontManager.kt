package com.hongwu.light

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** 已导入的字体：私有目录中的文件路径 + 界面展示名称 */
data class ImportedFont(val path: String, val displayName: String)

/** 待提交的字体：临时文件路径 + 最终文件名 + 展示名称，校验通过后才替换旧字体 */
data class PendingFont(val tempPath: String, val fileName: String, val displayName: String)

/**
 * 外部字体管理：把用户选择的字体复制到应用私有目录，校验后再正式启用
 * 复制到私有目录可避免文件选择器的授权失效或原文件被移走导致字体丢失
 */
object FontManager {

    /** 字体存放子目录名 */
    private const val FONT_DIR = "fonts"

    /** 正式字体文件名前缀 */
    private const val FONT_FILE_PREFIX = "custom_font"

    /** 待校验临时文件名前缀 */
    private const val PENDING_FILE_PREFIX = "import_pending"

    /** 支持的字体后缀 */
    private val FONT_EXTENSIONS = setOf("ttf", "otf")

    /**
     * 把文件选择器返回的字体复制为临时文件，等待调用方校验
     * @return 复制成功返回待提交信息；读取失败或文件为空时返回 null
     */
    fun stageFont(context: Context, uri: Uri): PendingFont? {
        // 查询系统提供的文件名，用于确定后缀与展示名称
        val fileName = queryDisplayName(context, uri)
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "font.ttf"  // 都无法获取时按 ttf 兜底
        // 后缀只接受 ttf/otf，其他写法按 ttf 命名（真实格式以能否解析为准）
        val ext = fileName.substringAfterLast('.', "").lowercase().takeIf { it in FONT_EXTENSIONS } ?: "ttf"
        // 展示名去掉后缀，界面更简洁
        val displayName = fileName.substringBeforeLast('.').ifBlank { fileName }
        // 字体目录，不存在时创建
        val dir = fontDir(context)
        // 临时文件：固定前缀 + 后缀，重复导入时覆盖
        val temp = File(dir, "$PENDING_FILE_PREFIX.$ext")
        // 打开系统输入流，失败则视为导入失败
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: return null
        // 复制输入流到临时文件
        val copied = runCatching {
            input.use { source -> temp.outputStream().use { target -> source.copyTo(target) } }
        }.isSuccess
        // 复制失败或得到空文件时清理临时文件
        if (!copied || temp.length() <= 0L) {
            temp.delete()
            return null
        }
        // 返回待校验信息，此时尚未替换已启用的旧字体
        return PendingFont(temp.absolutePath, "$FONT_FILE_PREFIX.$ext", displayName)
    }

    /**
     * 提交字体：删除旧的导入文件并把临时文件重命名为正式文件名
     * @return 成功返回最终字体信息，重命名失败返回 null
     */
    fun commitFont(context: Context, pending: PendingFont): ImportedFont? {
        // 字体目录
        val dir = fontDir(context)
        // 临时文件的文件名，用于清理时跳过它
        val tempName = File(pending.tempPath).name
        // 清理旧的正式字体与其他残留临时文件
        dir.listFiles()?.forEach { file ->
            val isOldFont = file.name.startsWith(FONT_FILE_PREFIX)  // 旧正式字体
            val isStalePending = file.name.startsWith(PENDING_FILE_PREFIX) && file.name != tempName  // 其他残留临时文件
            if (isOldFont || isStalePending) file.delete()
        }
        // 目标文件：正式前缀 + 新后缀
        val target = File(dir, pending.fileName)
        // 重命名临时文件为正式文件
        val renamed = File(pending.tempPath).renameTo(target)
        // 重命名成功返回最终信息，失败时清理临时文件
        return if (renamed) {
            ImportedFont(target.absolutePath, pending.displayName)
        } else {
            File(pending.tempPath).delete()
            null
        }
    }

    /** 放弃本次导入：删除临时文件，不影响已启用的字体 */
    fun discardFont(pending: PendingFont?) {
        // 空对象直接返回
        if (pending == null) return
        // 仅删除临时文件
        File(pending.tempPath).delete()
    }

    /**
     * 从文件路径加载字体：以导入字体为主字体，缺失字形自动回退系统字体
     * @return 加载失败时返回 null（调用方回退系统默认字体）
     */
    fun loadTypeface(path: String?): Typeface? {
        // 空路径视为未导入
        val file = path?.let { File(it) }?.takeIf { it.exists() } ?: return null
        // 读取字体文件构造 Font，再挂上系统回退链，避免缺字时显示方框
        return runCatching {
            val font = Font.Builder(file).build()  // 从字体文件构造 Font
            val family = FontFamily.Builder(font).build()  // 构造字体家族
            Typeface.CustomFallbackBuilder(family).build()  // 组合系统回退字体
        }.getOrNull()
    }

    /** 删除指定字体文件（恢复默认或清理失效配置时调用） */
    fun deleteFont(path: String?) {
        // 空路径直接返回
        val file = path?.let { File(it) } ?: return
        // 仅删除应用私有字体目录内的文件，避免误删
        if (file.parentFile?.name == FONT_DIR) file.delete()
    }

    /** 字体存放目录，不存在时创建 */
    private fun fontDir(context: Context): File =
        File(context.filesDir, FONT_DIR).apply { if (!exists()) mkdirs() }

    /** 查询系统文件选择器返回的文件展示名 */
    private fun queryDisplayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
}
