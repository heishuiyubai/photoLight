package com.hongwu.light

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoFrameApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var frameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var watermarkLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var exifWatermark by remember { mutableStateOf<List<String>>(emptyList()) }  // EXIF 读取的水印
    var customWatermark by remember { mutableStateOf("") }  // 用户输入的水印
    var bgAlpha by remember { mutableFloatStateOf(122f) }  // 背景透明度 (0-255)
    var mainScale by remember { mutableFloatStateOf(0.85f) }  // 主体图大小 (0.5-1.0)

    var isLoading by remember { mutableStateOf(false) }

    // 重新渲染相框（透明度或水印变化时调用）
    fun reRenderFrame() {
        val b = originalBitmap ?: return
        scope.launch {
            isLoading = true
            // 确定水印内容：用户输入优先，否则用 EXIF
            val wmLines = if (customWatermark.isNotBlank()) {
                listOf(customWatermark.trim())  // 只用用户输入的内容
            } else {
                exifWatermark  // 使用 EXIF 读取的内容
            }
            watermarkLines = wmLines
            // 合成相框
            val composed = withContext(Dispatchers.IO) {
                createGradientFrame(b, wmLines, bgAlpha.toInt(), mainScale)
            }
            frameBitmap?.recycle()
            frameBitmap = composed
            isLoading = false
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch {
                isLoading = true

                // 1. 回收旧的 Bitmap，释放内存
                frameBitmap?.recycle()
                frameBitmap = null
                originalBitmap?.recycle()
                originalBitmap = null

                // 2. 在后台线程加载并处理图片
                val bmp = withContext(Dispatchers.IO) {
                    loadBitmapFromUri(context, selectedUri)
                }

                originalBitmap = bmp
                imageUri = selectedUri

                bmp?.let { b ->
                    val wmLines = withContext(Dispatchers.IO) {
                        buildWatermarkLines(context, selectedUri)
                    }
                    exifWatermark = wmLines  // 保存 EXIF 水印
                    // 确定实际使用的水印
                    val actualWm = if (customWatermark.isNotBlank()) {
                        listOf(customWatermark.trim())
                    } else {
                        wmLines
                    }
                    watermarkLines = actualWm

                    // 合成相框
                    val composed = withContext(Dispatchers.IO) {
                        createGradientFrame(b, actualWm, bgAlpha.toInt(), mainScale)
                    }
                    frameBitmap = composed
                }

                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (originalBitmap != null) "相框预览" else "图片相框") },
                actions = {
                    if (originalBitmap != null && !isLoading) {
                        IconButton(onClick = {
                            pickLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }) {
                            Text("+", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (originalBitmap != null && !isLoading) {
                FloatingActionButton(onClick = {
                    val orig = originalBitmap ?: return@FloatingActionButton
                    scope.launch {
                        isLoading = true
                        withContext(Dispatchers.IO) {
                            saveComposedImage(context, orig, watermarkLines, bgAlpha.toInt(), mainScale)
                        }
                        isLoading = false
                        Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("保存", fontSize = 14.sp, color = Color.White)
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
                when {
                    // 状态 1：正在处理/合成图片
                    isLoading -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(text = "相框生成中...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    // 状态 2：合成完成，展示图片
                    frameBitmap != null -> {
                        Image(
                            bitmap = frameBitmap!!.asImageBitmap(),
                            contentDescription = "相框预览",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    // 状态 3：未选择图片，展示空状态页面
                    else -> {
                        EmptyState(onSelectClick = {
                            pickLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        })
                    }
                }
            }

            // 控制面板：透明度滑块 + 水印输入
            if (originalBitmap != null) {
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
                        Text("背景透明度", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Slider(
                            value = bgAlpha,
                            onValueChange = { newValue ->
                                bgAlpha = newValue  // 更新透明度值
                                reRenderFrame()  // 重新渲染
                            },
                            valueRange = 0f..255f,  // 范围 0-255
                            modifier = Modifier.weight(1f)
                        )
                        Text("${bgAlpha.toInt()}", fontSize = 12.sp)
                    }

                    // 图片大小滑块
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("图片大小", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Slider(
                            value = mainScale,
                            onValueChange = { newValue ->
                                mainScale = newValue  // 更新图片大小比例
                                reRenderFrame()  // 重新渲染
                            },
                            valueRange = 0.5f..1.0f,  // 范围 50%-100%
                            modifier = Modifier.weight(1f)
                        )
                        Text("${(mainScale * 100).toInt()}%", fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 水印输入框
                    OutlinedTextField(
                        value = customWatermark,
                        onValueChange = { newText ->
                            customWatermark = newText  // 更新输入内容
                            reRenderFrame()  // 重新渲染
                        },
                        label = { Text("自定义水印（留空则显示 EXIF 信息）") },
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
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        Text(
            text = "选择一张照片\n添加专业相框",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onSelectClick,
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("选择图片", fontSize = 18.sp)
        }
    }
}
