package com.hongwu.light

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    // 提示消息（保存成功/失败、图片加载失败）
    uiState.messageResId?.let { resId ->
        LaunchedEffect(resId) {
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (uiState.originalBitmap != null) R.string.title_preview
                            else R.string.title_home
                        )
                    )
                },
                actions = {
                    if (uiState.originalBitmap != null && !uiState.isLoading) {
                        // 旋转按钮：点击后顺时针旋转 90°
                        IconButton(onClick = viewModel::rotate) {
                            Text("⟳", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = launchPicker) {
                            Text("+", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.originalBitmap != null && !uiState.isLoading) {
                FloatingActionButton(onClick = viewModel::save) {
                    Text(stringResource(R.string.action_save), fontSize = 14.sp, color = Color.White)
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
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
                                    scaleX = animatedScale / uiState.renderedScale
                                    scaleY = animatedScale / uiState.renderedScale
                                },
                            contentScale = ContentScale.Fit
                        )
                    }
                    // 状态 3: 未选择图片，显示空状态页
                    else -> EmptyState(onSelectClick = launchPicker)
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
                }
            }
        }
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
