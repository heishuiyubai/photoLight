package com.hongwu.light

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 水印输入防抖时长（毫秒）：停止输入该时长后才重新渲染 */
private const val WATERMARK_DEBOUNCE_MS = 500L

/** 旧位图延迟回收时长（毫秒）：等待 Compose 重组完成、切换到绘制新图后再回收 */
private const val RECYCLE_DELAY_MS = 200L

/** 风格预览条的一项：风格标识 + 对应的小尺寸成图 */
data class StyleThumb(val style: FrameStyle, val bitmap: Bitmap)

/** 相框界面 UI 状态 */
data class FrameUiState(
    val originalBitmap: Bitmap? = null,       // 原始图片
    val imageUri: Uri? = null,                // 所选图片的 Uri
    val frameBitmap: Bitmap? = null,          // 合成后的相框预览图
    val styleThumbs: List<StyleThumb> = emptyList(),  // 各风格的小尺寸预览图
    val watermarkLines: List<String> = emptyList(),  // 当前渲染使用的水印
    val customWatermark: String = "",         // 用户输入的水印
    val style: FrameStyle = FrameStyle.GRADIENT,      // 当前相框风格
    val bgAlpha: Float = DEFAULT_BG_ALPHA.toFloat(),  // 背景透明度 (0-255)
    val mainScale: Float = DEFAULT_MAIN_SCALE,        // 主体图大小 (0.5-1.0)
    val rotation: Int = 0,                    // 图片旋转角度（90 的倍数）
    val renderedScale: Float = DEFAULT_MAIN_SCALE,    // 当前 frameBitmap 实际渲染时对应的 mainScale
    val exportFormat: ExportFormat = ExportFormat.JPG,  // 导出图片格式规则（PNG/JPG）
    val isLoading: Boolean = false,           // 是否正在加载/保存
    val messageResId: Int? = null,            // 一次性提示消息的字符串资源 ID
    val messageArgs: Array<String> = emptyArray()  // 提示消息的格式化参数（如格式名称）
)

/**
 * 相框界面 ViewModel
 * 持有全部状态与业务逻辑，状态不会因屏幕旋转等配置变更而丢失
 */
class PhotoFrameViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FrameUiState())
    val uiState: StateFlow<FrameUiState> = _uiState.asStateFlow()

    private var renderJob: Job? = null  // 渲染任务：新渲染启动前取消旧任务
    private var watermarkDebounceJob: Job? = null  // 水印输入防抖任务
    private var exifInfo: ShotInfo = ShotInfo()  // 缓存的 EXIF 信息，切换风格时无需重新读取

    /** 选中图片后：加载图片、读取 EXIF、合成相框 */
    fun onImagePicked(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // 取消进行中的渲染/防抖任务并等待其结束，避免旧任务继续操作即将回收的位图
            renderJob?.cancelAndJoin()
            watermarkDebounceJob?.cancel()

            // 先解除旧位图引用，再延迟回收，避免其仍在当前帧被绘制时就被回收
            scheduleRecycle(_uiState.value.frameBitmap)
            scheduleRecycle(_uiState.value.originalBitmap)
            _uiState.value.styleThumbs.forEach { scheduleRecycle(it.bitmap) }  // 回收旧的各风格缩略图
            // 新图片重置旋转角度与缩略图
            _uiState.update { it.copy(frameBitmap = null, originalBitmap = null, rotation = 0, styleThumbs = emptyList()) }

            // 在后台线程加载图片
            val bmp = withContext(Dispatchers.IO) { loadBitmapFromUri(app, uri) }
            if (bmp == null) {
                _uiState.update { it.copy(isLoading = false, messageResId = R.string.load_failed) }
                return@launch
            }

            // 读取 EXIF 信息并缓存，水印文本按风格在渲染时再拼装
            exifInfo = withContext(Dispatchers.IO) { readExifInfo(app, uri) }
            _uiState.update { it.copy(originalBitmap = bmp, imageUri = uri) }

            // 合成相框并显示（同时生成各风格缩略图）
            composeAndShow(bmp, resolveWatermark(_uiState.value.style))
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /** 顺时针旋转 90° */
    fun rotate() {
        _uiState.update { it.copy(rotation = (it.rotation + 90) % 360) }
        reRenderFrame()
    }

    fun onBgAlphaChanged(value: Float) {
        _uiState.update { it.copy(bgAlpha = value) }
    }

    fun onBgAlphaChangeFinished() = reRenderFrame()

    fun onMainScaleChanged(value: Float) {
        _uiState.update { it.copy(mainScale = value) }
    }

    fun onMainScaleChangeFinished() = reRenderFrame()

    /** 水印输入：停止输入 500ms 后才重新渲染，避免每次击键都全量合成位图 */
    fun onWatermarkChanged(text: String) {
        _uiState.update { it.copy(customWatermark = text) }
        watermarkDebounceJob?.cancel()
        watermarkDebounceJob = viewModelScope.launch {
            delay(WATERMARK_DEBOUNCE_MS)
            reRenderFrame()
        }
    }

    /** 切换相框风格（渐变 / 白框） */
    fun onStyleChanged(style: FrameStyle) {
        if (_uiState.value.style == style) return  // 重复点击当前风格不做处理
        _uiState.update { it.copy(style = style) }  // 更新选中风格
        reRenderFrame()  // 按新风格重新合成成图
    }

    /** 切换导出图片格式规则（PNG/JPG） */
    fun onExportFormatChanged(format: ExportFormat) {
        _uiState.update { it.copy(exportFormat = format) }
    }

    /** 保存当前预览的相框图到相册 */
    fun save() {
        val frame = _uiState.value.frameBitmap ?: return
        val format = _uiState.value.exportFormat  // 用户所选的导出格式
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val success = withContext(Dispatchers.IO) { saveComposedImage(app, frame, format) }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    messageResId = if (success) R.string.save_success else R.string.save_failed,
                    messageArgs = if (success) arrayOf(format.label) else emptyArray()  // 成功时提示携带格式名
                )
            }
        }
    }

    /** 提示消息展示后清除，避免重组时重复弹出 */
    fun onMessageShown() {
        _uiState.update { it.copy(messageResId = null, messageArgs = emptyArray()) }
    }

    // 重新渲染相框（透明度、大小、旋转、风格或水印变化时调用）
    private fun reRenderFrame() {
        val b = _uiState.value.originalBitmap ?: return
        renderJob?.cancel()  // 取消进行中的渲染，保证只有最新一次渲染的结果生效
        renderJob = viewModelScope.launch {
            composeAndShow(b, resolveWatermark(_uiState.value.style))
        }
    }

    // 确定指定风格实际生效的水印文本（自定义水印优先规则在各风格内部处理）
    private fun resolveWatermark(style: FrameStyle): List<String> =
        buildWatermarkLines(exifInfo, style, _uiState.value.customWatermark)

    // 合成相框并更新预览
    // 注意：这里不设置 isLoading，保持旧图继续显示，等新图渲染完无缝替换，避免闪烁
    private suspend fun composeAndShow(b: Bitmap, wmLines: List<String>) {
        val state = _uiState.value
        val composed = withContext(Dispatchers.IO) {
            createFrame(b, wmLines, state.style, state.bgAlpha.toInt(), state.mainScale, state.rotation)
        }
        // 已被更新的渲染任务取消时，丢弃本次结果，避免旧结果覆盖新结果
        if (!coroutineContext.isActive) {
            composed.recycle()
            return
        }
        // 先更新缩放记录，再替换图片，保证 graphicsLayer 比例平滑过渡
        val oldFrame = _uiState.value.frameBitmap
        _uiState.update {
            it.copy(frameBitmap = composed, renderedScale = state.mainScale, watermarkLines = wmLines)
        }
        scheduleRecycle(oldFrame)
        // 成图完成后同步刷新风格预览条，保证缩略图与当前参数一致
        renderStyleThumbs(b)
    }

    // 生成各风格的小尺寸预览图：先缩小原图再走同一套渲染逻辑，成本很低
    private suspend fun renderStyleThumbs(b: Bitmap) {
        val state = _uiState.value
        val thumbs = withContext(Dispatchers.IO) {
            FrameStyle.entries.map { style ->
                // 每种风格的水印文本不同，需各自拼装
                val lines = buildWatermarkLines(exifInfo, style, state.customWatermark)
                StyleThumb(
                    style = style,  // 风格标识
                    bitmap = createStyleThumb(  // 小尺寸成图
                        b,
                        style,
                        lines,
                        state.bgAlpha.toInt(),
                        state.mainScale,
                        state.rotation
                    )
                )
            }
        }
        // 任务被取消时丢弃结果并回收，避免新任务的缩略图被旧结果覆盖
        if (!coroutineContext.isActive) {
            thumbs.forEach { it.bitmap.recycle() }
            return
        }
        val oldThumbs = _uiState.value.styleThumbs
        _uiState.update { it.copy(styleThumbs = thumbs) }
        oldThumbs.forEach { scheduleRecycle(it.bitmap) }  // 延迟回收旧缩略图
    }

    // 延迟回收旧位图：旧位图可能仍在当前帧被 Compose 绘制，
    // 立即回收会触发 "trying to use a recycled bitmap"，等重组切换到新图后再回收
    private fun scheduleRecycle(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        viewModelScope.launch {
            delay(RECYCLE_DELAY_MS)
            bitmap.recycle()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // ViewModel 销毁时释放位图内存
        _uiState.value.originalBitmap?.recycle()
        _uiState.value.frameBitmap?.recycle()
        _uiState.value.styleThumbs.forEach { it.bitmap.recycle() }  // 释放各风格缩略图
    }
}
