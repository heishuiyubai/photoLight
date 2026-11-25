package com.hongwu.light

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Typeface
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

/** 日期文案配置：三个配置各自保存起始时间、模板与字体格式，切换后立即生效 */
data class CaptionPreset(
    val start: Long? = null,        // 起始日期（epochDay），null 表示未设置
    val text: String = "",          // 文案模板，用 {0} 表示天数或时长
    val italic: Boolean = false,    // 日期文案是否斜体
    val bold: Boolean = false,      // 日期文案是否加粗
    val sizeScale: Float = 1f,      // 日期文案字号倍数（0.5-1.5）
    val yearMonthDay: Boolean = false,  // 是否把天数差换算为年月日展示
    val name: String = ""           // 配置别名，空串时界面显示"配置 N"
)

/** 应用内页面：不引入导航组件，用状态切换 */
enum class AppScreen { FRAME, SETTINGS }

/** 相框界面 UI 状态 */
data class FrameUiState(
    val originalBitmap: Bitmap? = null,       // 原始图片
    val imageUri: Uri? = null,                // 所选图片的 Uri
    val frameBitmap: Bitmap? = null,          // 合成后的相框预览图
    val styleThumbs: List<StyleThumb> = emptyList(),  // 各风格的小尺寸预览图
    val watermarkLines: List<String> = emptyList(),  // 当前渲染使用的水印
    val captionPreview: String? = null,       // 当前生效的日期文案文本，用于设置页实时预览
    val customWatermark: String = "",         // 用户输入的水印
    val style: FrameStyle = FrameStyle.GRADIENT,      // 当前相框风格
    val screen: AppScreen = AppScreen.FRAME,          // 当前显示的页面
    val saveTreeUri: Uri? = null,             // 自定义保存目录，null 表示保存到系统相册
    val saveDirLabel: String = "",            // 自定义目录的可读路径文本
    val saveDirGranted: Boolean = true,       // 自定义目录的持久化授权是否仍有效
    val showShotTime: Boolean = false,        // 是否在拍摄参数位置展示拍摄时间
    val dayCountEnabled: Boolean = false,     // 是否启用日期文案（如"小朋友出生88天"）
    val captionPresets: List<CaptionPreset> = List(WatermarkPrefs.CAPTION_PRESET_COUNT) { CaptionPreset() },  // 三套可切换的日期文案配置
    val captionPresetIndex: Int = 0,          // 当前选中并生效的日期文案配置序号
    val unifyStyleEnabled: Boolean = false,   // 是否统一各水印行的字体格式、字间距与透明度
    val unifyStyles: Set<FrameStyle> = FrameStyle.entries.toSet(),  // 统一风格生效的相框风格
    val customFontName: String? = null,       // 已导入字体的展示名称，null 表示系统默认字体
    val customFontPath: String? = null,       // 已导入字体的私有文件路径
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
    private var inputDebounceJob: Job? = null  // 文本输入防抖任务（自定义水印 / 日期文案）
    private var exifInfo: ShotInfo = ShotInfo()  // 缓存的 EXIF 信息，切换风格时无需重新读取
    private var customTypeface: Typeface? = null  // 已导入字体对应的 Typeface，null 表示系统字体

    init {
        // 启动时恢复上次选择的自定义保存目录
        loadSaveLocation()
        // 启动时恢复水印相关设置（拍摄时间开关、日期文案）
        val app = getApplication<Application>()
        _uiState.update {
            it.copy(
                showShotTime = WatermarkPrefs.loadShowShotTime(app),  // 拍摄时间开关
                dayCountEnabled = WatermarkPrefs.loadDayCountEnabled(app),  // 日期文案开关
                captionPresets = List(WatermarkPrefs.CAPTION_PRESET_COUNT) { i ->  // 逐套读取配置
                    CaptionPreset(
                        start = WatermarkPrefs.loadDayCountStart(app, i),  // 起始日期
                        text = WatermarkPrefs.loadDayCountText(app, i),  // 文案模板
                        italic = WatermarkPrefs.loadCaptionItalic(app, i),  // 斜体
                        bold = WatermarkPrefs.loadCaptionBold(app, i),  // 加粗
                        sizeScale = WatermarkPrefs.loadCaptionSize(app, i),  // 字号倍数
                        yearMonthDay = WatermarkPrefs.loadCaptionYmd(app, i),  // 年月日格式
                        name = WatermarkPrefs.loadCaptionName(app, i)  // 配置别名
                    )
                },
                captionPresetIndex = WatermarkPrefs.loadDayCountActive(app),  // 上次选中的配置
                unifyStyleEnabled = WatermarkPrefs.loadUnifyEnabled(app),  // 统一风格开关
                unifyStyles = WatermarkPrefs.loadUnifyStyles(app)  // 生效风格集合（名称转枚举）
                    .mapNotNull { name -> FrameStyle.entries.firstOrNull { it.name == name } }
                    .toSet()
            )
        }
        // 启动时恢复已导入的字体
        loadSavedFont()
    }

    // 读取保存位置设置并刷新界面展示文本
    private fun loadSaveLocation() {
        val app = getApplication<Application>()
        val uri = SaveLocationPrefs.loadTreeUri(app)  // 已保存的自定义目录
        _uiState.update {
            it.copy(
                saveTreeUri = uri,  // 目录 Uri
                saveDirLabel = uri?.let { u -> describeTreeUri(app, u) } ?: "",  // 可读路径
                saveDirGranted = uri?.let { u -> SaveLocationPrefs.hasPermission(app, u) } ?: true  // 授权状态
            )
        }
    }

    /** 打开设置页 */
    fun openSettings() {
        loadSaveLocation()  // 进入前刷新，反映用户在系统里撤销授权等外部变化
        _uiState.update { it.copy(screen = AppScreen.SETTINGS) }
    }

    /** 返回相框页 */
    fun closeSettings() {
        _uiState.update { it.copy(screen = AppScreen.FRAME) }
    }

    /** 用户选定自定义保存目录：申请持久化授权并写入偏好 */
    fun onSaveDirPicked(uri: Uri) {
        val app = getApplication<Application>()
        SaveLocationPrefs.takePermission(app, uri)  // 申请重启后仍可写的持久化授权
        SaveLocationPrefs.saveTreeUri(app, uri)  // 保存目录设置
        _uiState.update {
            it.copy(
                saveTreeUri = uri,  // 新目录
                saveDirLabel = describeTreeUri(app, uri),  // 可读路径
                saveDirGranted = SaveLocationPrefs.hasPermission(app, uri)  // 授权结果
            )
        }
    }

    /** 清除自定义目录设置，恢复为保存到系统相册 */
    fun onResetSaveDir() {
        val app = getApplication<Application>()
        _uiState.value.saveTreeUri?.let { SaveLocationPrefs.releasePermission(app, it) }  // 释放持久化授权
        SaveLocationPrefs.saveTreeUri(app, null)  // 清空偏好
        _uiState.update { it.copy(saveTreeUri = null, saveDirLabel = "", saveDirGranted = true) }
    }

    /** 选中图片后：加载图片、读取 EXIF、合成相框 */
    fun onImagePicked(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // 取消进行中的渲染/防抖任务并等待其结束，避免旧任务继续操作即将回收的位图
            renderJob?.cancelAndJoin()
            inputDebounceJob?.cancel()

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
            composeAndShow(bmp, resolveWatermark(_uiState.value.style), resolveCaption(), isUnified(_uiState.value.style), customTypeface)
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
        inputDebounceJob?.cancel()
        inputDebounceJob = viewModelScope.launch {
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

    /** 切换"展示拍摄时间"：开启后用拍摄时间替换快门、光圈等参数，需重新合成水印 */
    fun onShowShotTimeChanged(enabled: Boolean) {
        if (_uiState.value.showShotTime == enabled) return  // 状态未变化不处理
        WatermarkPrefs.saveShowShotTime(getApplication(), enabled)  // 持久化开关
        _uiState.update { it.copy(showShotTime = enabled) }  // 更新状态
        reRenderFrame()  // 水印内容变化，重新渲染
    }

    /** 切换"日期文案"：开启后用起始日期与拍摄时间的天数差替换参数行 */
    fun onDayCountEnabledChanged(enabled: Boolean) {
        if (_uiState.value.dayCountEnabled == enabled) return  // 状态未变化不处理
        WatermarkPrefs.saveDayCountEnabled(getApplication(), enabled)  // 持久化开关
        _uiState.update { it.copy(dayCountEnabled = enabled) }  // 更新状态
        reRenderFrame()  // 水印内容变化，重新渲染
    }

    /** 选择日期文案配置：切换后立即用该配置的文案重新渲染 */
    fun onCaptionPresetSelected(index: Int) {
        if (_uiState.value.captionPresetIndex == index) return  // 重复选择当前配置不处理
        WatermarkPrefs.saveDayCountActive(getApplication(), index)  // 持久化选中项
        _uiState.update { it.copy(captionPresetIndex = index) }  // 更新选中配置
        reRenderFrame()  // 配置变化，重新渲染
    }

    /** 设置当前配置的起始日期（epochDay），如宝宝出生日期 */
    fun onDayCountStartChanged(epochDay: Long) {
        val index = _uiState.value.captionPresetIndex  // 当前选中的配置序号
        if (_uiState.value.captionPresets[index].start == epochDay) return  // 日期未变化不处理
        WatermarkPrefs.saveDayCountStart(getApplication(), index, epochDay)  // 持久化到当前配置
        updateActivePreset { it.copy(start = epochDay) }  // 更新界面状态
        reRenderFrame()  // 天数变化，重新渲染
    }

    /** 当前配置的文案模板输入：立即持久化，停止输入 500ms 后再重新渲染 */
    fun onDayCountTextChanged(text: String) {
        val index = _uiState.value.captionPresetIndex  // 当前选中的配置序号
        WatermarkPrefs.saveDayCountText(getApplication(), index, text)  // 模板持久化到当前配置
        updateActivePreset { it.copy(text = text) }  // 更新界面状态
        inputDebounceJob?.cancel()  // 取消上一次防抖任务
        inputDebounceJob = viewModelScope.launch {
            delay(WATERMARK_DEBOUNCE_MS)  // 等待输入停顿
            reRenderFrame()  // 重新合成
        }
    }

    /** 切换当前配置的日期文案斜体 */
    fun onCaptionItalicChanged(italic: Boolean) {
        val index = _uiState.value.captionPresetIndex  // 当前选中的配置序号
        if (_uiState.value.captionPresets[index].italic == italic) return  // 状态未变化不处理
        WatermarkPrefs.saveCaptionItalic(getApplication(), index, italic)  // 持久化到当前配置
        updateActivePreset { it.copy(italic = italic) }  // 更新界面状态
        reRenderFrame()  // 字体格式变化，重新渲染
    }

    /** 切换当前配置的日期文案加粗 */
    fun onCaptionBoldChanged(bold: Boolean) {
        val index = _uiState.value.captionPresetIndex  // 当前选中的配置序号
        if (_uiState.value.captionPresets[index].bold == bold) return  // 状态未变化不处理
        WatermarkPrefs.saveCaptionBold(getApplication(), index, bold)  // 持久化到当前配置
        updateActivePreset { it.copy(bold = bold) }  // 更新界面状态
        reRenderFrame()  // 字体格式变化，重新渲染
    }

    /** 当前配置的日期文案字号拖动中：仅更新状态，松手后再渲染 */
    fun onCaptionSizeChanged(scale: Float) {
        updateActivePreset { it.copy(sizeScale = scale) }  // 拖动中只更新内存状态
    }

    /** 当前配置的日期文案字号拖动结束：持久化并重新渲染 */
    fun onCaptionSizeChangeFinished() {
        val index = _uiState.value.captionPresetIndex  // 当前选中的配置序号
        val scale = _uiState.value.captionPresets[index].sizeScale  // 当前配置的字号倍数
        WatermarkPrefs.saveCaptionSize(getApplication(), index, scale)  // 持久化倍数
        reRenderFrame()  // 字号变化，重新渲染
    }

    /** 切换当前配置是否按年月日展示天数差 */
    fun onCaptionYmdChanged(enabled: Boolean) {
        val index = _uiState.value.captionPresetIndex  // 当前选中的配置序号
        if (_uiState.value.captionPresets[index].yearMonthDay == enabled) return  // 状态未变化不处理
        WatermarkPrefs.saveCaptionYmd(getApplication(), index, enabled)  // 持久化到当前配置
        updateActivePreset { it.copy(yearMonthDay = enabled) }  // 更新界面状态
        reRenderFrame()  // 文案内容变化，重新渲染
    }

    /** 当前配置的别名输入：仅用于设置页区分配置，立即持久化，不影响成图 */
    fun onCaptionPresetNameChanged(name: String) {
        val index = _uiState.value.captionPresetIndex  // 当前选中的配置序号
        if (_uiState.value.captionPresets[index].name == name) return  // 名称未变化不处理
        WatermarkPrefs.saveCaptionName(getApplication(), index, name)  // 持久化到当前配置
        updateActivePreset { it.copy(name = name) }  // 更新界面状态
    }

    /** 更新当前选中配置并同步到界面状态 */
    private fun updateActivePreset(transform: (CaptionPreset) -> CaptionPreset) {
        _uiState.update { state ->
            val presets = state.captionPresets.toMutableList()  // 拷贝列表以便替换单项
            val index = state.captionPresetIndex  // 当前选中的配置序号
            presets[index] = transform(presets[index])  // 应用配置变更
            state.copy(captionPresets = presets)  // 写回新列表
        }
    }

    /** 切换"统一水印文字风格"：开启后各水印行使用相同字体格式、字间距与透明度 */
    fun onUnifyStyleEnabledChanged(enabled: Boolean) {
        if (_uiState.value.unifyStyleEnabled == enabled) return  // 状态未变化不处理
        WatermarkPrefs.saveUnifyEnabled(getApplication(), enabled)  // 持久化开关
        _uiState.update { it.copy(unifyStyleEnabled = enabled) }  // 更新状态
        reRenderFrame()  // 水印风格变化，重新渲染
    }

    /** 勾选/取消某种相框风格是否应用统一水印风格 */
    fun onUnifyStyleToggled(style: FrameStyle, selected: Boolean) {
        val current = _uiState.value.unifyStyles  // 当前生效风格集合
        val next = if (selected) current + style else current - style  // 勾选为加入，取消为移除
        if (next == current) return  // 集合未变化不处理
        WatermarkPrefs.saveUnifyStyles(getApplication(), next.map { it.name }.toSet())  // 持久化名称集合
        _uiState.update { it.copy(unifyStyles = next) }  // 更新状态
        reRenderFrame()  // 当前风格的统一状态可能变化，重新渲染
    }

    /** 用户选定外部字体：复制到私有目录、校验通过后启用并重新渲染 */
    fun onFontPicked(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }  // 导入期间显示进度
            // 复制字体到临时文件（IO 线程）
            val pending = withContext(Dispatchers.IO) { FontManager.stageFont(app, uri) }
            // 复制失败时提示并结束
            if (pending == null) {
                _uiState.update { it.copy(isLoading = false, messageResId = R.string.font_import_failed) }
                return@launch
            }
            // 校验字体能否加载，失败时删除临时文件并保留原有字体
            val typeface = withContext(Dispatchers.IO) {
                FontManager.loadTypeface(pending.tempPath).also { tf ->
                    if (tf == null) FontManager.discardFont(pending)
                }
            }
            if (typeface == null) {
                _uiState.update { it.copy(isLoading = false, messageResId = R.string.font_import_failed) }
                return@launch
            }
            // 校验通过后提交：删除旧字体并把临时文件转为正式文件
            val imported = FontManager.commitFont(app, pending)
            if (imported == null) {
                FontManager.discardFont(pending)  // 提交失败时清理临时文件
                _uiState.update { it.copy(isLoading = false, messageResId = R.string.font_import_failed) }
                return@launch
            }
            // 持久化字体路径与展示名称
            WatermarkPrefs.saveFontPath(app, imported.path)
            WatermarkPrefs.saveFontName(app, imported.displayName)
            // 更新内存中的字体与界面状态
            customTypeface = typeface
            _uiState.update {
                it.copy(isLoading = false, customFontPath = imported.path, customFontName = imported.displayName)
            }
            reRenderFrame()  // 字体变化后重新合成
        }
    }

    /** 恢复系统默认字体：清除记录并删除已导入的字体文件 */
    fun onResetFont() {
        val app = getApplication<Application>()
        val path = _uiState.value.customFontPath  // 当前字体文件路径
        customTypeface = null  // 内存中立即回退系统字体
        WatermarkPrefs.saveFontPath(app, null)  // 清除路径记录
        WatermarkPrefs.saveFontName(app, null)  // 清除名称记录
        _uiState.update { it.copy(customFontPath = null, customFontName = null) }  // 更新界面状态
        // 删除磁盘上的字体文件，避免占用空间
        viewModelScope.launch(Dispatchers.IO) { FontManager.deleteFont(path) }
        reRenderFrame()  // 字体变化后重新合成
    }

    /** 启动时恢复已导入的字体；文件丢失或损坏时清理失效记录 */
    private fun loadSavedFont() {
        val app = getApplication<Application>()
        val path = WatermarkPrefs.loadFontPath(app)  // 上次保存的字体路径
        val typeface = FontManager.loadTypeface(path)  // 尝试加载字体
        // 记录存在但加载失败时视为失效，清除偏好避免重复尝试
        if (path != null && typeface == null) {
            WatermarkPrefs.saveFontPath(app, null)
            WatermarkPrefs.saveFontName(app, null)
        }
        customTypeface = typeface
        _uiState.update {
            it.copy(
                customFontPath = if (typeface != null) path else null,  // 仅加载成功时展示路径
                customFontName = if (typeface != null) WatermarkPrefs.loadFontName(app) else null  // 仅加载成功时展示名称
            )
        }
    }

    /** 保存当前预览的相框图到相册或自定义目录 */
    fun save() {
        val frame = _uiState.value.frameBitmap ?: return
        val format = _uiState.value.exportFormat  // 用户所选的导出格式
        val treeUri = _uiState.value.saveTreeUri  // 自定义目录，为 null 时保存到系统相册
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val success = withContext(Dispatchers.IO) { saveComposedImage(app, frame, format, treeUri) }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    // 失败提示区分场景：自定义目录失败需引导用户重新选择
                    messageResId = when {
                        success -> R.string.save_success
                        treeUri != null -> R.string.save_failed_custom
                        else -> R.string.save_failed
                    },
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
            composeAndShow(b, resolveWatermark(_uiState.value.style), resolveCaption(), isUnified(_uiState.value.style), customTypeface)
        }
    }

    // 指定风格是否启用统一水印风格：开关打开且该风格被勾选时生效
    private fun isUnified(style: FrameStyle): Boolean =
        _uiState.value.unifyStyleEnabled && style in _uiState.value.unifyStyles

    // 确定指定风格实际生效的水印文本（自定义水印、时间开关与日期文案优先规则在各风格内部处理）
    private fun resolveWatermark(style: FrameStyle): List<String> =
        buildWatermarkLines(
            exifInfo,  // EXIF 信息
            style,  // 风格
            _uiState.value.customWatermark,  // 自定义水印
            _uiState.value.showShotTime,  // 展示拍摄时间
            resolveDayCaption()  // 日期文案（未启用时为 null）
        )

    // 计算当前生效的日期文案：开关关闭、当前配置未设置起始日期或照片无拍摄时间时返回 null
    private fun resolveDayCaption(): String? {
        val state = _uiState.value
        if (!state.dayCountEnabled) return null  // 开关关闭不生成
        val preset = state.captionPresets[state.captionPresetIndex]  // 当前选中的配置
        val start = preset.start ?: return null  // 未设置起始日期不生成
        return buildDayCountCaption(exifInfo, start, preset.text, preset.yearMonthDay)  // 天数或年月日模板替换
    }

    // 计算当前生效的日期文案及其字体格式，供渲染端按文本匹配应用样式
    private fun resolveCaption(): CaptionLine? {
        val text = resolveDayCaption() ?: return null
        val preset = _uiState.value.captionPresets[_uiState.value.captionPresetIndex]  // 当前选中的配置
        return CaptionLine(
            text = text,
            style = CaptionStyle(preset.italic, preset.bold, preset.sizeScale)  // 使用该配置的字体格式
        )
    }

    // 合成相框并更新预览
    // 注意：这里不设置 isLoading，保持旧图继续显示，等新图渲染完无缝替换，避免闪烁
    private suspend fun composeAndShow(b: Bitmap, wmLines: List<String>, caption: CaptionLine?, unifyWatermark: Boolean, customTypeface: Typeface?) {
        val state = _uiState.value
        val composed = withContext(Dispatchers.IO) {
            createFrame(b, wmLines, state.style, state.bgAlpha.toInt(), state.mainScale, state.rotation, caption, unifyWatermark, customTypeface)
        }
        // 已被更新的渲染任务取消时，丢弃本次结果，避免旧结果覆盖新结果
        if (!coroutineContext.isActive) {
            composed.recycle()
            return
        }
        // 先更新缩放记录，再替换图片，保证 graphicsLayer 比例平滑过渡
        val oldFrame = _uiState.value.frameBitmap
        _uiState.update {
            it.copy(
                frameBitmap = composed,  // 新成图
                renderedScale = state.mainScale,  // 记录本次缩放比例
                watermarkLines = wmLines,  // 本次使用的水印行
                captionPreview = caption?.text  // 设置页预览的日期文案
            )
        }
        scheduleRecycle(oldFrame)
        // 成图完成后同步刷新风格预览条，保证缩略图与当前参数一致
        renderStyleThumbs(b)
    }

    // 生成各风格的小尺寸预览图：先缩小原图再走同一套渲染逻辑，成本很低
    private suspend fun renderStyleThumbs(b: Bitmap) {
        val state = _uiState.value
        // 日期文案与风格无关，先算一次供所有缩略图复用（含字体格式）
        val caption = resolveCaption()
        val dayCaption = caption?.text
        val thumbs = withContext(Dispatchers.IO) {
            FrameStyle.entries.map { style ->
                // 每种风格的水印文本不同，需各自拼装
                val lines = buildWatermarkLines(exifInfo, style, state.customWatermark, state.showShotTime, dayCaption)
                StyleThumb(
                    style = style,  // 风格标识
                    bitmap = createStyleThumb(  // 小尺寸成图
                        b,
                        style,
                        lines,
                        state.bgAlpha.toInt(),
                        state.mainScale,
                        state.rotation,
                        caption,  // 日期文案字体格式
                        isUnified(style),  // 该风格是否启用统一水印风格
                        customTypeface  // 已导入字体
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
