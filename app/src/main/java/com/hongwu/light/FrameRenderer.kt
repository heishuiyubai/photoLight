package com.hongwu.light

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlin.math.min

// ==================== 常量配置区 ====================

/** 主体图默认缩放比例：原图的 85% */
const val DEFAULT_MAIN_SCALE = 0.85f

/** 圆角半径比例：相对于主体图短边的 4% */
private const val CORNER_RADIUS_RATIO = 0.04f

/** 背景默认透明度：原图作为背景时的 Alpha 值 (0-255)，122 ≈ 48% */
const val DEFAULT_BG_ALPHA = 122

/** 投影阴影 Y 轴偏移量（像素） */
private const val SHADOW_OFFSET_Y = 12f

/** 投影阴影模糊半径（像素） */
private const val SHADOW_BLUR_RADIUS = 24f

/** 投影阴影透明度 (0-255) */
private const val SHADOW_ALPHA = 140

/** 水印文字区域高度比例：占画布高度的 15% */
private const val WATERMARK_AREA_RATIO = 0.15f

/** 主体图边缘羽化比例：边缘向内淡出的宽度占短边的比例 */
private const val EDGE_FEATHER_RATIO = 0.07f

// ==================== 主函数 ====================

/**
 * 生成专业摄影相框图片
 * @param source 原始图片 Bitmap
 * @param watermarkLines 水印文字列表（第1行品牌型号，第2行拍摄参数）
 * @param bgAlpha 背景透明度 (0-255)，默认 122
 * @param mainScale 主体图缩放比例 (0-1)，默认 0.85
 * @param rotation 旋转角度（90 的倍数），默认 0
 * @return 合成后的相框图片 Bitmap
 */
fun createGradientFrame(source: Bitmap, watermarkLines: List<String>, bgAlpha: Int = DEFAULT_BG_ALPHA, mainScale: Float = DEFAULT_MAIN_SCALE, rotation: Int = 0): Bitmap {
    // 若需要旋转，先得到旋转后的位图（90/270 会交换宽高）
    val rotated = if (rotation % 360 == 0) source else rotateBitmap(source, rotation)

    // 获取旋转后宽度
    val W = rotated.width
    // 获取旋转后高度
    val H = rotated.height

    // 创建与旋转图同尺寸的画布 Bitmap
    val result = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    // 创建 Canvas 用于绘制
    val canvas = Canvas(result)

    // ---------- 第1层：绘制半透明背景（铺满画布） ----------
    drawBlurredBackground(canvas, rotated, W, H, bgAlpha)

    // ---------- 第2层：绘制主体图片 ----------
    // 按 mainScale 缩放，在画布中居中显示，带圆角和柔和投影
    drawMainImage(canvas, rotated, W, H, mainScale)
//
//    // ---------- 第3层：绘制水印文字 ----------
//    // 在底部区域显示 EXIF 拍摄参数
    drawWatermarkText(canvas, W, H, watermarkLines, mainScale)

    // 回收临时旋转图（若不是原图）
    if (rotated !== source) rotated.recycle()

    // 返回合成后的图片
    return result
}

/**
 * 按指定角度（90 的倍数）旋转 Bitmap，返回新 Bitmap
 * @param source 原始 Bitmap
 * @param degrees 旋转角度
 */
private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())  // 设置旋转角度
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

// ==================== 第1层：半透明背景 ====================

/**
 * 绘制半透明背景
 * 原图铺满整个画布，整体半透明
 * @param W 画布宽度
 * @param H 画布高度
 * @param bgAlpha 背景透明度 (0-255)
 */
private fun drawBlurredBackground(canvas: Canvas, source: Bitmap, W: Int, H: Int, bgAlpha: Int) {
    // 创建画笔，开启抗锯齿
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 绘制原图作为背景（铺满画布，半透明）
    paint.alpha = bgAlpha  // 设置画笔透明度
    canvas.drawBitmap(source, 0f, 0f, paint)  // 在 (0,0) 位置绘制原图铺满背景
    paint.alpha = 255  // 恢复画笔透明度
}

// ==================== 第2层：主体图片 ====================

/**
 * 绘制主体图片
 * 按 mainScale 缩放，在画布中居中显示，带圆角和柔和投影阴影
 * @param W 画布宽度（= 原图宽度）
 * @param H 画布高度（= 原图高度）
 * @param mainScale 主体图缩放比例 (0-1)
 */
private fun drawMainImage(canvas: Canvas, source: Bitmap, W: Int, H: Int, mainScale: Float) {
    // 创建画笔，开启抗锯齿
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 计算主体图尺寸：原图 × mainScale
    val mainW = (W * mainScale).toFloat()  // 主体图宽度 = 画布宽 × mainScale
    val mainH = (H * mainScale).toFloat()  // 主体图高度 = 画布高 × mainScale

    // 计算居中偏移量（四周边距相等）
    val offsetX = (W - mainW) / 2f  // X 轴偏移：(画布宽 - 主体宽) / 2
    val offsetY = (H - mainH) / 2f  // Y 轴偏移：(画布高 - 主体高) / 2

    // 计算圆角半径：主体图短边的 4%
    val cornerRadius = min(mainW, mainH) * CORNER_RADIUS_RATIO

    // 创建主体图的矩形区域
    val rect = RectF(offsetX, offsetY, offsetX + mainW, offsetY + mainH)

    // 步骤1：先绘制投影阴影（在主体图下方）
    drawDropShadow(canvas, rect, cornerRadius)

    // 步骤2：在离屏 Bitmap 上绘制圆角主体图 + 边缘羽化
    val layerW = mainW.toInt()
    val layerH = mainH.toInt()
    val layerBmp = Bitmap.createBitmap(layerW, layerH, Bitmap.Config.ARGB_8888)
    val layerCanvas = Canvas(layerBmp)

    // 2a: 用 BitmapShader 绘制圆角矩形图片
    val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    val matrix = Matrix()
    val scaleX = mainW / source.width
    val scaleY = mainH / source.height
    matrix.setScale(scaleX, scaleY)
    shader.setLocalMatrix(matrix)
    paint.shader = shader

    val localRect = RectF(0f, 0f, mainW, mainH)
    layerCanvas.drawRoundRect(localRect, cornerRadius, cornerRadius, paint)

    // 2b: 使用 BlurMaskFilter + PorterDuff.DST_IN 实现边缘羽化
    // 绘制一个略向内缩的圆角矩形遮罩并模糊其边缘，保持矩形形状，仅四边柔和淡出
    val feather = min(min(mainW, mainH) * EDGE_FEATHER_RATIO, 100f)  // 羽化宽度（BlurMaskFilter 半径上限约 128）
    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)  // 保留重叠部分，透明区域擦除
        maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)  // 模糊遮罩边缘
    }
    // 遮罩向内缩半个羽化宽度，使图片边界处透明度趋近 0，消除硬边
    val half = feather / 2f
    val maskRect = RectF(half, half, mainW - half, mainH - half)
    layerCanvas.drawRoundRect(maskRect, cornerRadius, cornerRadius, maskPaint)

    // 步骤3：将带羽化效果的图片绘制到主画布
    canvas.drawBitmap(layerBmp, offsetX, offsetY, null)
    layerBmp.recycle()
}

/**
 * 绘制柔和投影阴影
 * 使用离屏 Bitmap + BlurMaskFilter 实现高斯模糊阴影
 */
private fun drawDropShadow(canvas: Canvas, rect: RectF, cornerRadius: Float) {
    // 阴影 Bitmap 需要比主体图大，留出模糊扩散空间
    val padding = 80  // 阴影扩散边距（像素）
    val shadowW = rect.width().toInt() + padding * 2  // 阴影 Bitmap 宽度
    val shadowH = rect.height().toInt() + padding * 2  // 阴影 Bitmap 高度

    // 创建离屏 Bitmap 用于绘制阴影
    val shadowBmp = Bitmap.createBitmap(shadowW, shadowH, Bitmap.Config.ARGB_8888)
    val shadowCanvas = Canvas(shadowBmp)  // 创建离屏画布

    // 创建阴影画笔，设置模糊效果
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(SHADOW_ALPHA, 0, 0, 0)  // 半透明黑色
        maskFilter = BlurMaskFilter(SHADOW_BLUR_RADIUS, BlurMaskFilter.Blur.NORMAL)  // 高斯模糊
    }

    // 在离屏画布中心绘制圆角矩形（作为阴影形状）
    val localRect = RectF(
        padding.toFloat(),  // 左边距
        padding.toFloat(),  // 上边距
        padding + rect.width(),  // 右边距
        padding + rect.height()  // 下边距
    )
    shadowCanvas.drawRoundRect(localRect, cornerRadius, cornerRadius, shadowPaint)

    // 将阴影绘制到主画布（带 Y 轴偏移，模拟光源从上方照射）
    val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG)  // 创建绘制画笔
    canvas.save()  // 保存画布状态
    canvas.translate(
        rect.left - padding,  // X 轴对齐到主体图左边
        rect.top - padding + SHADOW_OFFSET_Y  // Y 轴对齐并向下偏移
    )
    canvas.drawBitmap(shadowBmp, 0f, 0f, drawPaint)  // 绘制阴影 Bitmap
    canvas.restore()  // 恢复画布状态
    shadowBmp.recycle()  // 回收阴影 Bitmap
}

// ==================== 第3层：水印文字 ====================

/**
 * 绘制水印文字
 * 在主体图底边与画布底边之间的边框区域内居中显示
 */
private fun drawWatermarkText(canvas: Canvas, W: Int, H: Int, lines: List<String>, mainScale: Float) {
    // 如果没有水印文字，直接返回
    if (lines.isEmpty()) return

    // 创建文字画笔
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER  // 文字居中对齐
        typeface = Typeface.DEFAULT  // 跟随系统默认字体
        setShadowLayer(2f, 0f, 1f, Color.argb(100, 0, 0, 0))  // 添加文字阴影增强可读性
    }

    // 计算主体图底边位置（与 drawMainImage 保持一致）
    val mainH = (H * mainScale).toFloat()  // 主体图高度
    val mainBottom = (H - mainH) / 2f + mainH  // 主体图底边 Y 坐标

    // 边框区域：主体图底边 → 画布底边
    val borderH = H - mainBottom  // 边框区域高度
    val borderCenterY = mainBottom + borderH / 2f  // 边框区域垂直中心
    val centerX = W / 2f  // 画布水平中心 X 坐标

    if (lines.size == 1) {
        // 单行水印：在边框区域垂直居中
        paint.textSize = borderH * 0.3f  // 字号：边框高度的 40%
        paint.color = Color.WHITE  // 白色文字
        paint.alpha = 200  // 稍微透明
        val textY = borderCenterY + paint.textSize * 0.3f  // 文字基线修正
        canvas.drawText(lines[0], centerX, textY, paint)  // 绘制文字
    } else {
        // 双行水印：第1行品牌型号，第2行拍摄参数

        // 第1行：品牌型号（较小字号，半透明）
        paint.textSize = borderH * 0.30f  // 字号：边框高度的 30%
        paint.color = Color.WHITE  // 白色
        paint.alpha = 170  // 较透明（突出第2行）
        val line1Y = borderCenterY - borderH * 0.1f  // 第1行在中心偏上
        canvas.drawText(lines[0], centerX, line1Y, paint)  // 绘制品牌型号

        // 第2行：拍摄参数（较大字号，更实）
        paint.textSize = borderH * 0.35f  // 字号：边框高度的 35%
        paint.alpha = 240  // 接近不透明
        val line2Y = borderCenterY + borderH * 0.3f  // 第2行在中心偏下
        canvas.drawText(lines[1], centerX, line2Y, paint)  // 绘制拍摄参数
    }
}

// ==================== 保存到相册 ====================

/** 保存日志标签 */
private const val TAG = "FrameRenderer"

/** JPEG 保存质量 (0-100)，照片类图像 95 可在体积与画质间取得平衡 */
private const val SAVE_QUALITY = 95

/**
 * 将已渲染好的相框图片保存到系统相册（JPEG 格式）
 * 直接复用预览中的位图，避免二次合成（更快且保证与预览一致）
 * @param context Android Context
 * @param bitmap 已合成好的相框图片
 * @return 是否保存成功
 */
fun saveComposedImage(context: Context, bitmap: Bitmap): Boolean {
    val timestamp = System.currentTimeMillis()  // 时间戳：用于文件名与 EXIF 时间
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "framed_$timestamp.jpg")  // 文件名
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")  // MIME 类型
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)  // 保存路径：Pictures
        put(MediaStore.Images.Media.DATE_ADDED, timestamp / 1000)  // 添加时间（秒）
        put(MediaStore.Images.Media.DATE_TAKEN, timestamp)  // 拍摄时间（毫秒）
        put(MediaStore.Images.Media.IS_PENDING, 1)  // 标记为待完成，防止相册提前拉取未写完的图片
    }

    val resolver = context.contentResolver  // 获取内容解析器
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    if (uri == null) {
        Log.e(TAG, "保存图片失败：无法创建相册记录")
        return false
    }

    return try {
        // 打开输出流并写入 JPEG 数据
        val os = resolver.openOutputStream(uri) ?: throw java.io.IOException("无法打开输出流")
        os.use { bitmap.compress(Bitmap.CompressFormat.JPEG, SAVE_QUALITY, it) }

        // 写入完成后解除 IS_PENDING 状态，相册即可见
        contentValues.clear()  // 清空内容值
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)  // 标记为完成
        resolver.update(uri, contentValues, null, null)  // 更新记录
        true
    } catch (e: Exception) {
        Log.e(TAG, "保存图片失败", e)
        // 写入失败时删除记录，避免相册留下空/孤儿条目
        resolver.delete(uri, null, null)
        false
    }
}
