package com.hongwu.light

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** 保存位置偏好：自定义目录 Uri 的存取与持久化授权管理 */
object SaveLocationPrefs {

    /** SharedPreferences 文件名 */
    private const val PREFS_NAME = "frame_prefs"

    /** 自定义保存目录的键，未写入时表示保存到系统相册 */
    private const val KEY_TREE_URI = "custom_tree_uri"

    /** 读取用户设置的自定义目录，未设置或值非法时返回 null */
    fun loadTreeUri(context: Context): Uri? {
        // 取出原始字符串
        val raw = prefs(context).getString(KEY_TREE_URI, null)
        // 空串与空值都视为未设置
        if (raw.isNullOrEmpty()) return null
        return try {
            Uri.parse(raw)  // 还原为 Uri
        } catch (e: Exception) {
            null  // 解析失败按未设置处理
        }
    }

    /** 写入自定义目录，传入 null 表示清除设置 */
    fun saveTreeUri(context: Context, uri: Uri?) {
        prefs(context).edit()
            .putString(KEY_TREE_URI, uri?.toString())  // Uri 以字符串形式保存
            .apply()  // 异步落盘
    }

    /** 申请目录的持久化读写授权，应用重启后仍可继续写入 */
    fun takePermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,  // 目录树 Uri
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION  // 读写权限位
            )
        } catch (e: SecurityException) {
            e.printStackTrace()  // 授权失败不影响保存时的错误提示
        }
    }

    /** 释放目录的持久化授权，恢复默认保存位置时调用 */
    fun releasePermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,  // 目录树 Uri
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION  // 读写权限位
            )
        } catch (e: SecurityException) {
            e.printStackTrace()  // 已失效的授权释放失败可忽略
        }
    }

    /** 检查持久化授权是否仍然有效，授权被撤销时设置页需要提示重新选择 */
    fun hasPermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    /** SharedPreferences 实例 */
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * 把 SAF 目录树 Uri 还原成可读路径文本，如 "内部存储/Pictures/我的相框"
 * 仅用于界面展示，实际写入仍使用原始 Uri
 */
fun describeTreeUri(context: Context, treeUri: Uri): String {
    // 树 Uri 的文档 ID 形如 "primary:Pictures/foo" 或 "<存储卡ID>/Android/..."
    val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        ?: treeUri.lastPathSegment.orEmpty()  // 非标准 Uri 时退回最后一段

    // 按首个冒号拆分存储卷与相对路径
    val volume: String  // 存储卷标识
    val path: String  // 卷内相对路径
    if (docId.contains(':')) {
        volume = docId.substringBefore(':')  // 卷标识
        path = docId.substringAfter(':')  // 相对路径
    } else {
        volume = docId  // 无冒号时整段作为卷标识
        path = ""  // 无相对路径
    }

    // 卷标识转可读名称：主存储与扩展存储卡分别处理
    val volumeLabel = when {
        volume == "primary" -> context.getString(R.string.dir_internal_storage)  // 内部存储
        volume.startsWith("home") -> ""  // 根目录不显示卷名
        volume.isEmpty() -> ""  // 空卷标识
        else -> context.getString(R.string.dir_sd_card)  // 其他卷按存储卡显示
    }

    // 拼接最终展示文本，去掉多余的斜杠
    return listOf(volumeLabel, path.trim('/'))
        .filter { it.isNotEmpty() }  // 丢弃空段
        .joinToString("/")  // 用斜杠连接
}
