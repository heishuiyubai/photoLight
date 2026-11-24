package com.hongwu.light

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoFrameApp(viewModel: PhotoFrameViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // 平滑缩放动画：拖动过程中实时响应，松手后再渲染实际 Bitmap
    val animatedScale by animateFloatAsState(
        targetValue = uiState.mainScale,
        animationSpec = tween(durationMillis = 200),
        label = "scaleAnimation"
    )

    // 提示消息（保存成功/失败、图片加载失败），成功消息带格式参数（PNG/JPG）
    uiState.messageResId?.let { resId ->
        LaunchedEffect(resId) {
            val text = if (uiState.messageArgs.isNotEmpty()) {
                context.getString(resId, *uiState.messageArgs)  // 格式化提示（如"已保存到相册（PNG）"）
            } else {
                context.getString(resId)  // 无参数提示
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            viewModel.onMessageShown()
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.onImagePicked(it) }
    }

    // 启动系统相册选择器
    val launchPicker = {
        pickLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // 系统文件夹选择器：返回用户授权的目录树 Uri
    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.onSaveDirPicked(it) }  // 选定自定义保存目录
    }

    // 设置页拦截系统返回键，返回相框页而不是直接退出应用
    BackHandler(enabled = uiState.screen == AppScreen.SETTINGS, onBack = viewModel::closeSettings)

    val onFrameScreen = uiState.screen == AppScreen.FRAME  // 当前是否相框页

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when {
                                !onFrameScreen -> R.string.title_settings  // 设置页标题
                                uiState.originalBitmap != null -> R.string.title_preview
                                else -> R.string.title_home
                            }
                        )
                    )
                },
                navigationIcon = {
                    // 设置页显示返回按钮
                    if (!onFrameScreen) {
                        IconButton(onClick = viewModel::closeSettings) {
                            Text("←", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                actions = {
                    if (onFrameScreen && uiState.originalBitmap != null && !uiState.isLoading) {
                        // 旋转按钮：点击后顺时针旋转 90°
                        IconButton(onClick = viewModel::rotate) {
                            Text("⟳", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = launchPicker) {
                            Text("+", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    // 设置入口：仅在相框页显示
                    if (onFrameScreen) {
                        IconButton(onClick = viewModel::openSettings) {
                            Text("⚙", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            // 保存按钮只在相框页出现
            if (onFrameScreen && uiState.originalBitmap != null && !uiState.isLoading) {
                FloatingActionButton(onClick = viewModel::save) {
                    Text(stringResource(R.string.action_save), fontSize = 14.sp, color = Color.White)
                }
            }
        }
    ) { innerPadding ->
        // 键盘弹出时把内容整体顶到键盘上方：先消费系统栏内边距，再应用 IME 内边距，避免重复留白
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            // 设置页与相框页共用同一个 Scaffold，按当前页面切换内容
            when (uiState.screen) {
                AppScreen.SETTINGS -> SettingsContent(
                    label = uiState.saveDirLabel,
                    treeUri = uiState.saveTreeUri,
                    granted = uiState.saveDirGranted,
                    showShotTime = uiState.showShotTime,
                    onShowShotTimeChanged = viewModel::onShowShotTimeChanged,
                    dayCountEnabled = uiState.dayCountEnabled,
                    dayCountStart = uiState.dayCountStart,
                    dayCountText = uiState.dayCountText,
                    onDayCountEnabledChanged = viewModel::onDayCountEnabledChanged,
                    onDayCountStartChanged = viewModel::onDayCountStartChanged,
                    onDayCountTextChanged = viewModel::onDayCountTextChanged,
                    captionItalic = uiState.captionItalic,
                    captionBold = uiState.captionBold,
                    captionSizeScale = uiState.captionSizeScale,
                    onCaptionItalicChanged = viewModel::onCaptionItalicChanged,
                    onCaptionBoldChanged = viewModel::onCaptionBoldChanged,
                    onCaptionSizeChanged = viewModel::onCaptionSizeChanged,
                    onCaptionSizeChangeFinished = viewModel::onCaptionSizeChangeFinished,
                    unifyStyleEnabled = uiState.unifyStyleEnabled,
                    unifyStyles = uiState.unifyStyles,
                    onUnifyStyleEnabledChanged = viewModel::onUnifyStyleEnabledChanged,
                    onUnifyStyleToggled = viewModel::onUnifyStyleToggled,
                    onPickDir = { dirPickerLauncher.launch(null) },  // 不预设起始目录
                    onReset = viewModel::onResetSaveDir,
                    modifier = Modifier.fillMaxSize()
                )
                AppScreen.FRAME -> FrameContent(
                    uiState = uiState,
                    animatedScale = animatedScale,
                    launchPicker = launchPicker,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** 相框页内容：预览区 + 风格预览条 + 控制面板 */
@Composable
private fun FrameContent(
    uiState: FrameUiState,
    animatedScale: Float,
    launchPicker: () -> Unit,
    viewModel: PhotoFrameViewModel,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // 图片预览区域
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val frame = uiState.frameBitmap
            when {
                // 状态 1: 正在处理/合成图片
                uiState.isLoading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.frame_generating),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                // 状态 2: 合成完毕，显示图片
                frame != null -> {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = stringResource(R.string.frame_preview_desc),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .graphicsLayer {
                                // 仅渐变风格用整图缩放做拖动过渡；白框与暗色的边框和水印需固定，缩放整图会让位置漂移
                                if (uiState.style == FrameStyle.GRADIENT) {
                                    scaleX = animatedScale / uiState.renderedScale
                                    scaleY = animatedScale / uiState.renderedScale
                                }
                            },
                        contentScale = ContentScale.Fit
                    )
                }
                // 状态 3: 未选择图片，显示空状态页
                else -> EmptyState(onSelectClick = launchPicker)
            }
        }

        // 风格预览条：横向滑动对比各风格的小尺寸成图，点击切换当前风格
        if (uiState.styleThumbs.isNotEmpty()) {
            // 缩略图行的横向滚动状态
            val styleScroll = rememberScrollState()
            Text(
                text = stringResource(R.string.label_frame_style),
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(styleScroll)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FrameStyle.entries.forEach { style ->
                    val selected = uiState.style == style  // 是否为当前选中风格
                    val thumb = uiState.styleThumbs.firstOrNull { it.style == style }  // 该风格的缩略图
                    val cardShape = RoundedCornerShape(8.dp)  // 卡片圆角形状
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(112.dp)
                            .clip(cardShape)
                            .border(
                                width = if (selected) 2.dp else 1.dp,  // 选中项加粗描边
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = cardShape
                            )
                            .clickable { viewModel.onStyleChanged(style) }  // 点击切换风格
                    ) {
                        if (thumb != null && !thumb.bitmap.isRecycled) {
                            // 白底承载缩略图，避免白色展框缩略图与主题背景融为一体
                            Image(
                                bitmap = thumb.bitmap.asImageBitmap(),
                                contentDescription = style.label,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(84.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            // 缩略图未就绪时占位，避免预览条高度跳动
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(84.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        Text(
                            text = style.label,
                            fontSize = 12.sp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // 控制面板：透明度滑块 + 水印输入
        if (uiState.originalBitmap != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // 背景透明度滑块
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.label_bg_alpha), fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Slider(
                        value = uiState.bgAlpha,
                        onValueChange = viewModel::onBgAlphaChanged,
                        onValueChangeFinished = viewModel::onBgAlphaChangeFinished,
                        valueRange = 0f..255f,  // 范围 0-255
                        enabled = uiState.style == FrameStyle.GRADIENT,  // 展框风格为纯白底，透明度不生效
                        modifier = Modifier.weight(1f)
                    )
                    Text("${uiState.bgAlpha.toInt()}", fontSize = 12.sp)
                }

                // 主体图大小滑块
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.label_image_size), fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Slider(
                        value = uiState.mainScale,
                        onValueChange = viewModel::onMainScaleChanged,
                        onValueChangeFinished = viewModel::onMainScaleChangeFinished,
                        valueRange = 0.5f..1.0f,  // 范围 50%-100%
                        modifier = Modifier.weight(1f)
                    )
                    Text("${(uiState.mainScale * 100).toInt()}%", fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 水印输入框（防抖 500ms，停止输入后才重新渲染）
                OutlinedTextField(
                    value = uiState.customWatermark,
                    onValueChange = viewModel::onWatermarkChanged,
                    label = { Text(stringResource(R.string.watermark_input_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 导出格式选择行：PNG（无损）/ JPG（压缩，体积小）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.label_export_format), fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    ExportFormat.entries.forEach { format ->
                        val selected = uiState.exportFormat == format  // 是否为当前选中格式
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.onExportFormatChanged(format) },  // 切换导出格式
                            label = { Text(format.label, fontSize = 13.sp) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 设置页：水印展示选项（拍摄时间 / 日期文案）+ 自定义保存位置 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    label: String,
    treeUri: Uri?,
    granted: Boolean,
    showShotTime: Boolean,
    onShowShotTimeChanged: (Boolean) -> Unit,
    dayCountEnabled: Boolean,
    dayCountStart: Long?,
    dayCountText: String,
    onDayCountEnabledChanged: (Boolean) -> Unit,
    onDayCountStartChanged: (Long) -> Unit,
    onDayCountTextChanged: (String) -> Unit,
    captionItalic: Boolean,
    captionBold: Boolean,
    captionSizeScale: Float,
    onCaptionItalicChanged: (Boolean) -> Unit,
    onCaptionBoldChanged: (Boolean) -> Unit,
    onCaptionSizeChanged: (Float) -> Unit,
    onCaptionSizeChangeFinished: () -> Unit,
    unifyStyleEnabled: Boolean,
    unifyStyles: Set<FrameStyle>,
    onUnifyStyleEnabledChanged: (Boolean) -> Unit,
    onUnifyStyleToggled: (FrameStyle, Boolean) -> Unit,
    onPickDir: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 日期选择弹窗开关
    var showDatePicker by remember { mutableStateOf(false) }
    // 起始日期展示文本：未设置时显示占位提示
    val startDateLabel = dayCountStart
        ?.let { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ISO_LOCAL_DATE) }
        ?: stringResource(R.string.day_count_start_unset)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())  // 内容较多时允许滚动
            .padding(16.dp)
    ) {
        // ===== 水印分区 =====
        Text(
            text = stringResource(R.string.settings_watermark_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 展示拍摄时间开关：开启后用拍摄时间替换拍摄参数
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 左侧标题与说明
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_show_shot_time),
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_show_shot_time_hint),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 右侧开关
            Switch(
                checked = showShotTime,
                onCheckedChange = onShowShotTimeChanged
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()  // 水印分区内部细分隔线
        Spacer(modifier = Modifier.height(16.dp))

        // 日期文案开关：开启后用"拍摄时间 − 起始时间"的天数生成文案，并占用参数行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 左侧标题与说明
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_day_count),
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_day_count_hint),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 右侧开关
            Switch(
                checked = dayCountEnabled,
                onCheckedChange = onDayCountEnabledChanged
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 起始日期：点击打开系统日期选择器
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.label_day_count_start),
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { showDatePicker = true },
                enabled = dayCountEnabled  // 未启用时不可选
            ) {
                Text(startDateLabel, fontSize = 15.sp)
            }
        }

        // 文案模板输入：{0} 会被替换为天数
        OutlinedTextField(
            value = dayCountText,
            onValueChange = onDayCountTextChanged,
            label = { Text(stringResource(R.string.label_day_count_text)) },
            supportingText = { Text(stringResource(R.string.settings_day_count_text_hint), fontSize = 12.sp) },
            singleLine = true,
            enabled = dayCountEnabled,  // 未启用时不可编辑
            modifier = Modifier.fillMaxWidth()
        )

        // 字体格式：斜体 / 加粗
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.label_caption_style),
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = captionItalic,
                onClick = { onCaptionItalicChanged(!captionItalic) },  // 切换斜体
                label = { Text(stringResource(R.string.label_italic), fontSize = 13.sp) },
                enabled = dayCountEnabled,
                modifier = Modifier.padding(end = 8.dp)
            )
            FilterChip(
                selected = captionBold,
                onClick = { onCaptionBoldChanged(!captionBold) },  // 切换加粗
                label = { Text(stringResource(R.string.label_bold), fontSize = 13.sp) },
                enabled = dayCountEnabled
            )
        }

        // 字号：相对风格默认字号的倍数
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.label_caption_size), fontSize = 14.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Slider(
                value = captionSizeScale,
                onValueChange = onCaptionSizeChanged,  // 拖动实时更新百分比
                onValueChangeFinished = onCaptionSizeChangeFinished,  // 松手后重新渲染
                valueRange = 0.5f..1.5f,  // 50% - 150%
                enabled = dayCountEnabled,
                modifier = Modifier.weight(1f)
            )
            Text("${(captionSizeScale * 100).toInt()}%", fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()  // 日期文案与统一风格之间的分隔线
        Spacer(modifier = Modifier.height(16.dp))

        // 统一水印文字风格：开启后各水印行使用相同的字体格式、字间距与透明度
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 左侧标题与说明
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_unify_style),
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_unify_style_hint),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 右侧开关
            Switch(
                checked = unifyStyleEnabled,
                onCheckedChange = onUnifyStyleEnabledChanged
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 生效的相框风格：可多选，未勾选的风格保持原有主次排版
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.label_unify_apply),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            FrameStyle.entries.forEach { style ->
                val selected = style in unifyStyles  // 该风格是否已勾选
                FilterChip(
                    selected = selected,
                    onClick = { onUnifyStyleToggled(style, !selected) },  // 切换该风格
                    label = { Text(style.label, fontSize = 13.sp) },
                    enabled = unifyStyleEnabled,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        // 日期选择弹窗
        if (showDatePicker) {
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = dayCountStart?.times(86_400_000L)  // epochDay 转毫秒，未设置时为空
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        // 选中的毫秒为 UTC 零点，整除得到 epochDay
                        pickerState.selectedDateMillis?.let { onDayCountStartChanged(it / 86_400_000L) }
                        showDatePicker = false
                    }) { Text(stringResource(R.string.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            ) {
                DatePicker(state = pickerState)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider()  // 分区之间的分隔线
        Spacer(modifier = Modifier.height(20.dp))

        // ===== 保存位置分区 =====
        Text(
            text = stringResource(R.string.settings_save_location_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(16.dp))

        // 当前保存位置
        Text(stringResource(R.string.label_save_location), fontSize = 14.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (treeUri == null) stringResource(R.string.save_location_default) else label,
            fontSize = 16.sp,
            style = MaterialTheme.typography.bodyLarge
        )

        // 授权被系统撤销时提示重新选择
        if (treeUri != null && !granted) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.save_location_revoked),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 操作按钮：选择目录 / 恢复默认
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPickDir) {
                Text(stringResource(R.string.action_pick_dir))
            }
            if (treeUri != null) {
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.action_reset_dir))
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 行为说明：自定义目录与相册索引的关系
        Text(
            text = stringResource(R.string.save_location_hint),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun EmptyState(onSelectClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.empty_state_title),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onSelectClick,
            shape = RoundedCornerShape(50),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(stringResource(R.string.select_image), fontSize = 18.sp)
        }
    }
}
