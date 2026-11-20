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
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import kotlin.math.max
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

// ==================== 白色展框风格常量 ====================

/** 展框左右留白占照片短边的比例（参考官方展框实测值） */
private const val MOUNT_SIDE_RATIO = 0.104f

/** 展框顶部留白占照片短边的比例 */
private const val MOUNT_TOP_RATIO = 0.052f

/** 展框底部文字带占照片短边的比例 */
private const val MOUNT_BOTTOM_RATIO = 0.213f

/** 展框留白系数基准：mainScale 等于默认值时留白系数为 1 */
private const val MOUNT_PAD_BASE = 1f - DEFAULT_MAIN_SCALE

/** 展框留白系数下限：滑块拖到 1.0 时仍保留边框，避免文字无处安放 */
private const val MOUNT_PAD_MIN = 0.35f

/** 展框留白系数上限：避免边框过厚并控制阴影离屏位图的内存占用 */
private const val MOUNT_PAD_MAX = 2f

/** 展框投影模糊半径占照片短边的比例 */
private const val MOUNT_SHADOW_BLUR_RATIO = 0.05f

/** 展框投影透明度 (0-255)：白底上的投影需比暗底柔和 */
private const val MOUNT_SHADOW_ALPHA = 60

/** 展框投影 Y 轴偏移占照片短边的比例 */
private const val MOUNT_SHADOW_OFFSET_RATIO = 0.006f

/** 展框品牌行字号占文字带高度的比例 */
private const val MOUNT_TEXT1_SIZE_RATIO = 0.22f

/** 展框标语行字号占文字带高度的比例 */
private const val MOUNT_TEXT2_SIZE_RATIO = 0.135f

/** 展框参数行字号占文字带高度的比例 */
private const val MOUNT_TEXT3_SIZE_RATIO = 0.145f

/** 展框三行版式中各行中心在文字带内的位置比例 */
private const val MOUNT_LINE1_POS = 0.32f

/** 展框标语行垂直位置比例 */
private const val MOUNT_LINE2_POS = 0.52f

/** 展框参数行垂直位置比例 */
private const val MOUNT_LINE3_POS = 0.76f

/** 展框两行版式中品牌行的垂直位置比例 */
private const val MOUNT_TWO_LINE1_POS = 0.35f

/** 展框两行版式中参数行的垂直位置比例 */
private const val MOUNT_TWO_LINE2_POS = 0.68f

/** 展框品牌行字距（em）：展框风格要求宽字距 */
private const val MOUNT_TEXT1_SPACING = 0.30f

/** 展框标语行字距（em） */
private const val MOUNT_TEXT2_SPACING = 0.45f

/** 展框参数行字距（em） */
private const val MOUNT_TEXT3_SPACING = 0.05f

/** 缩略图最长边像素：风格预览条使用的小图尺寸 */
private const val THUMB_MAX_SIDE = 480

// ==================== 暗色底板风格常量 ====================

/** 底板背景目标亮度 (0-255)：照片均色压暗到该亮度后作为背景 */
private const val DARK_TARGET_LUMA = 40

/** 底板照片上边距占垂直剩余空间的比例：值越小照片越靠上 */
private const val DARK_TOP_WEIGHT = 0.27f

/** 底板圆角半径占照片短边的比例 */
private const val DARK_CORNER_RATIO = 0.021f

/** 底板投影模糊半径占照片短边的比例 */
private const val DARK_SHADOW_BLUR_RATIO = 0.025f

/** 底板投影透明度 (0-255)：深底上的投影需比白底更实 */
private const val DARK_SHADOW_ALPHA = 70

/** 底板投影 Y 轴偏移占照片短边的比例 */
private const val DARK_SHADOW_OFFSET_RATIO = 0.008f

/** 底板文字带高度比例：品牌行字号占文字带高度的比例 */
private const val DARK_TEXT1_SIZE_RATIO = 0.19f

/** 底板文字带高度比例：参数行字号占文字带高度的比例 */
private const val DARK_TEXT2_SIZE_RATIO = 0.14f

/** 底板品牌行中心在文字带内的位置比例 */
private const val DARK_LINE1_POS = 0.34f

/** 底板参数行中心在文字带内的位置比例 */
private const val DARK_LINE2_POS = 0.55f

/** 底板品牌行文字颜色：浅灰白，保证在深底上可读 */
private val DARK_TEXT_BRAND_COLOR = Color.rgb(232, 228, 226)

/** 底板参数行文字颜色：比品牌行略暗，拉开层次 */
private val DARK_TEXT_PARAMS_COLOR = Color.rgb(168, 162, 158)

/** 底板背景取色用的缩略尺寸：越小越快，均值精度足够 */
private const val DARK_AVG_SAMPLE_SIZE = 32

// ==================== 相框风格 ====================

/** 相框风格：不同风格的画布构造方式与绘制层完全不同 */
enum class FrameStyle(val label: String) {
    GRADIENT("渐变"),      // 原图半透明铺底 + 圆角羽化 + 投影（项目原有风格）
    WHITE_MOUNT("白框"),    // 白色扩画布 + 硬边照片 + 底部三行黑色文字（展框风格）
    DARK_PLATE("暗色")      // 照片主色派生的深色底板 + 偏上圆角照片 + 两行浅色文字
}

// ==================== 主函数 ====================

/**
 * 按指定风格生成相框图片
 * @param source 原始图片 Bitmap
 * @param watermarkLines 水印文字列表（内容随风格不同而不同）
 * @param style 相框风格
 * @param bgAlpha 背景透明度 (0-255)，仅渐变风格使用
 * @param mainScale 主体图缩放比例 (0-1)，展框风格下决定留白厚度
 * @param rotation 旋转角度（90 的倍数）
 * @return 合成后的相框图片 Bitmap
 */
fun createFrame(
    source: Bitmap,
    watermarkLines: List<String>,
    style: FrameStyle = FrameStyle.GRADIENT,
    bgAlpha: Int = DEFAULT_BG_ALPHA,
    mainScale: Float = DEFAULT_MAIN_SCALE,
    rotation: Int = 0
): Bitmap = when (style) {
    // 渐变风格：画布尺寸等于原图尺寸
    FrameStyle.GRADIENT -> createGradientFrame(source, watermarkLines, bgAlpha, mainScale, rotation)
    // 展框风格：画布尺寸等于照片加边框，需向外扩展
    FrameStyle.WHITE_MOUNT -> createWhiteMountFrame(source, watermarkLines, mainScale, rotation)
    // 暗色底板风格：画布尺寸等于原图尺寸，背景改为照片主色派生的深色
    FrameStyle.DARK_PLATE -> createDarkPlateFrame(source, watermarkLines, mainScale, rotation)
}

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

// ==================== 白色展框风格 ====================

/**
 * 生成白色展框风格相框
 * 画布 = 照片 + 左右留白 + 顶部留白 + 底部文字带，照片按原始分辨率贴入不做缩放
 * @param source 原始图片 Bitmap
 * @param watermarkLines 水印文字（品牌 / 标语 / 参数）
 * @param mainScale 留白控制参数，值越小留白越厚
 * @param rotation 旋转角度（90 的倍数）
 */
private fun createWhiteMountFrame(source: Bitmap, watermarkLines: List<String>, mainScale: Float, rotation: Int): Bitmap {
    // 若需要旋转，先得到旋转后的位图（90/270 会交换宽高）
    val rotated = if (rotation % 360 == 0) source else rotateBitmap(source, rotation)

    // 照片尺寸（展框不对照片做缩放，直接沿用旋转后的宽高）
    val photoW = rotated.width.toFloat()  // 照片宽度
    val photoH = rotated.height.toFloat()  // 照片高度

    // 以照片短边为留白基准，保证横图与竖图的边框厚度观感一致
    val shortSide = min(photoW, photoH)  // 照片短边长度

    // 留白系数：mainScale 每偏离默认值一档，边框等比变厚或变薄
    val padScale = ((1f - mainScale) / MOUNT_PAD_BASE).coerceIn(MOUNT_PAD_MIN, MOUNT_PAD_MAX)  // 留白缩放系数

    // 各方向留白（像素）
    val sidePad = shortSide * MOUNT_SIDE_RATIO * padScale  // 左右留白
    val topPad = shortSide * MOUNT_TOP_RATIO * padScale  // 顶部留白
    val bandH = shortSide * MOUNT_BOTTOM_RATIO * padScale  // 底部文字带高度

    // 画布尺寸 = 照片尺寸 + 留白（因此输出宽高比与原图不同）
    val W = (photoW + sidePad * 2f).toInt()  // 画布宽度
    val H = (photoH + topPad + bandH).toInt()  // 画布高度

    // 创建画布 Bitmap 并铺纯白底色
    val result = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    canvas.drawColor(Color.WHITE)

    // 照片在画布中的矩形区域（水平居中，上方留白薄、下方留白厚）
    val rect = RectF(sidePad, topPad, sidePad + photoW, topPad + photoH)

    // 先绘制照片下方的柔和投影，模糊半径与偏移量随照片尺寸等比缩放
    drawDropShadow(
        canvas,  // 主画布
        rect,  // 照片矩形
        0f,  // 展框为直角，圆角半径 0
        blur = (shortSide * MOUNT_SHADOW_BLUR_RATIO * padScale).coerceIn(8f, 64f),  // 模糊半径，限制上限以控制阴影离屏位图大小
        alpha = MOUNT_SHADOW_ALPHA,  // 投影透明度
        offsetY = shortSide * MOUNT_SHADOW_OFFSET_RATIO * padScale  // 投影下移量
    )

    // 按原始分辨率绘制照片（不做缩放，保留全部细节，边缘为硬边）
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawBitmap(rotated, sidePad, topPad, paint)

    // 在底部文字带内绘制三行黑色文字
    drawMountWatermark(canvas, W, topPad + photoH, bandH, watermarkLines)

    // 回收临时旋转图（若不是原图）
    if (rotated !== source) rotated.recycle()

    return result
}

/**
 * 绘制展框底部文字带：品牌行（衬线斜体宽字距）、标语行、参数行
 * @param bandTop 文字带顶部 Y 坐标
 * @param bandH 文字带高度
 */
private fun drawMountWatermark(canvas: Canvas, W: Int, bandTop: Float, bandH: Float, lines: List<String>) {
    // 无文字或文字带过窄时不绘制
    if (lines.isEmpty() || bandH <= 0f) return

    // 创建文字画笔，展框文字统一为纯黑、居中对齐
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER  // 文字居中对齐
        color = Color.BLACK  // 黑色文字
    }

    // 画布水平中心 X 坐标
    val centerX = W / 2f

    // 按行数选择版式：三行为完整版式，两行时省略标语行并重新分布位置
    val sizeRatios: FloatArray  // 各行字号占带高的比例
    val posRatios: FloatArray  // 各行中心占带高的位置比例
    when (lines.size) {
        1 -> {  // 仅一行：垂直居中显示
            sizeRatios = floatArrayOf(MOUNT_TEXT1_SIZE_RATIO)
            posRatios = floatArrayOf(0.5f)
        }
        2 -> {  // 两行：品牌 + 参数
            sizeRatios = floatArrayOf(MOUNT_TEXT1_SIZE_RATIO, MOUNT_TEXT3_SIZE_RATIO)
            posRatios = floatArrayOf(MOUNT_TWO_LINE1_POS, MOUNT_TWO_LINE2_POS)
        }
        else -> {  // 三行：品牌 + 标语 + 参数
            sizeRatios = floatArrayOf(MOUNT_TEXT1_SIZE_RATIO, MOUNT_TEXT2_SIZE_RATIO, MOUNT_TEXT3_SIZE_RATIO)
            posRatios = floatArrayOf(MOUNT_LINE1_POS, MOUNT_LINE2_POS, MOUNT_LINE3_POS)
        }
    }

    // 逐行绘制
    for (i in lines.indices) {
        // 首行为品牌行，末行（行数大于 1 时）为参数行，其余为标语行
        val isBrand = i == 0  // 是否品牌行
        val isParams = i == lines.size - 1 && lines.size > 1  // 是否参数行

        // 品牌行使用衬线斜体还原展框质感，标语与参数行跟随系统默认字体
        paint.typeface = if (isBrand) {
            Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)  // 衬线粗斜体
        } else {
            Typeface.DEFAULT  // 系统默认字体
        }

        // 字距：标语行最宽，品牌行次之，参数行接近常规
        paint.letterSpacing = when {
            isBrand -> MOUNT_TEXT1_SPACING  // 品牌行字距
            isParams -> MOUNT_TEXT3_SPACING  // 参数行字距
            else -> MOUNT_TEXT2_SPACING  // 标语行字距
        }

        // 字号按文字带高度等比计算
        paint.textSize = bandH * sizeRatios[i]

        // 用字体度量把行的视觉中心对齐到目标位置，保证不同字号下仍居中
        val fm = paint.fontMetrics  // 字体度量
        val baseline = bandTop + bandH * posRatios[i] - (fm.ascent + fm.descent) / 2f  // 基线 Y 坐标
        canvas.drawText(lines[i], centerX, baseline, paint)  // 绘制该行文字
    }
}

/**
 * 生成指定风格的小尺寸相框图，用于界面风格预览条
 * 先等比缩小原图再走同一套渲染逻辑，保证缩略图与成图版式一致
 * @param maxSide 缩略图最长边像素
 */
fun createStyleThumb(source: Bitmap, style: FrameStyle, watermarkLines: List<String>, bgAlpha: Int, mainScale: Float, rotation: Int, maxSide: Int = THUMB_MAX_SIDE): Bitmap {
    // 先按旋转后的尺寸计算缩放比例（90/270 会交换宽高）
    val swap = rotation % 180 != 0  // 是否交换宽高
    val rotatedW = if (swap) source.height else source.width  // 旋转后宽
    val rotatedH = if (swap) source.width else source.height  // 旋转后高

    // 计算等比缩放系数，长边不超过 maxSide
    val scale = maxSide.toFloat() / max(rotatedW, rotatedH)  // 缩放系数

    // 缩放系数不小于 1 时不缩小，直接复用原图
    val small = if (scale >= 1f) {
        source  // 图本身已足够小，直接使用
    } else {
        // 用 Matrix 等比缩小到位图（新对象，需在使用后回收）
        val matrix = Matrix().apply { setScale(scale, scale) }  // 缩放矩阵
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)  // 生成缩小图
    }

    // 走正式渲染流程生成小图
    val thumb = createFrame(small, watermarkLines, style, bgAlpha, mainScale, rotation)

    // 回收临时缩放图
    if (small !== source) small.recycle()

    return thumb
}

// ==================== 暗色底板风格 ====================

/**
 * 生成暗色底板风格相框
 * 画布尺寸等于原图尺寸，背景由照片均色压暗得到，照片居中偏上、带圆角与柔和投影
 * @param source 原始图片 Bitmap
 * @param watermarkLines 水印文本三段：品牌 / 型号 / 参数（品牌与型号同处一行混排）
 * @param mainScale 照片占画布宽高的比例
 * @param rotation 旋转角度（90 的倍数）
 */
private fun createDarkPlateFrame(source: Bitmap, watermarkLines: List<String>, mainScale: Float, rotation: Int): Bitmap {
    // 若需要旋转，先得到旋转后的位图（90/270 会交换宽高）
    val rotated = if (rotation % 360 == 0) source else rotateBitmap(source, rotation)

    // 画布尺寸等于旋转后的原图尺寸
    val W = rotated.width  // 画布宽度
    val H = rotated.height  // 画布高度

    // 创建画布 Bitmap
    val result = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)

    // 背景层：取照片平均色并压暗到目标亮度，使底板色调跟随照片
    canvas.drawColor(darkenToTargetLuma(averageColor(rotated), DARK_TARGET_LUMA))

    // 照片尺寸：按 mainScale 缩放
    val mainW = (W * mainScale).toFloat()  // 照片宽度
    val mainH = (H * mainScale).toFloat()  // 照片高度

    // 水平居中，垂直方向把剩余空间按 DARK_TOP_WEIGHT 分配给上边距，实现居中偏上
    val offsetX = (W - mainW) / 2f  // X 轴偏移
    val offsetY = (H - mainH) * DARK_TOP_WEIGHT  // Y 轴偏移：上边距小于下边距

    // 照片矩形与圆角半径（短边比例）
    val rect = RectF(offsetX, offsetY, offsetX + mainW, offsetY + mainH)  // 照片区域
    val shortSide = min(mainW, mainH)  // 照片短边
    val cornerRadius = shortSide * DARK_CORNER_RATIO  // 圆角半径

    // 先绘制照片下方的柔和投影
    drawDropShadow(
        canvas,  // 主画布
        rect,  // 照片矩形
        cornerRadius,  // 与照片一致的圆角
        blur = shortSide * DARK_SHADOW_BLUR_RATIO,  // 模糊半径
        alpha = DARK_SHADOW_ALPHA,  // 投影透明度
        offsetY = shortSide * DARK_SHADOW_OFFSET_RATIO  // 投影下移量
    )

    // 绘制圆角照片（直接画在主画布，不做边缘羽化）
    drawRoundedImage(canvas, rotated, rect, cornerRadius)

    // 在照片下方的底板区域绘制两行浅色文字
    drawPlateWatermark(canvas, W, H, rect.bottom, watermarkLines)

    // 回收临时旋转图（若不是原图）
    if (rotated !== source) rotated.recycle()

    return result
}

/**
 * 绘制圆角照片：用 BitmapShader 把原图映射到圆角矩形，不做羽化
 */
private fun drawRoundedImage(canvas: Canvas, source: Bitmap, rect: RectF, cornerRadius: Float) {
    // 创建画笔并开启抗锯齿
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // 用原图做着色器，边缘裁切不外铺
    val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    // 缩放矩阵把原图映射到目标矩形
    val matrix = Matrix()
    matrix.setScale(rect.width() / source.width, rect.height() / source.height)
    shader.setLocalMatrix(matrix)
    paint.shader = shader
    // 绘制圆角矩形，一次成型无需离屏位图
    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
}

/**
 * 绘制暗色底板文字：第1行品牌（衬线粗斜体）与型号（系统字体）混排并整体居中，第2行为拍摄参数
 * @param photoBottom 照片底边 Y 坐标，文字带为照片底边到画布底边之间的区域
 */
private fun drawPlateWatermark(canvas: Canvas, W: Int, H: Int, photoBottom: Float, lines: List<String>) {
    // 文字带高度：照片底边到画布底边
    val bandH = H - photoBottom  // 文字带高度
    if (bandH <= 0f) return  // 照片铺满时没有地方放文字

    // 三段文本：品牌、型号、参数
    val brand = lines.getOrElse(0) { "" }  // 品牌段
    val model = lines.getOrElse(1) { "" }  // 型号段
    val params = lines.getOrElse(2) { "" }  // 参数段
    if (brand.isEmpty() && model.isEmpty() && params.isEmpty()) return  // 无内容直接返回

    // 画布水平中心
    val centerX = W / 2f

    // 品牌画笔：衬线粗斜体，浅色保证可读
    val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT  // 分段左对齐绘制，便于整行居中
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)  // 衬线粗斜体
        color = DARK_TEXT_BRAND_COLOR  // 浅灰白
        textSize = bandH * DARK_TEXT1_SIZE_RATIO  // 字号按带高计算
    }
    // 型号画笔：跟随系统默认字体，与品牌同字号同基线
    val modelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT  // 分段左对齐
        typeface = Typeface.DEFAULT  // 系统默认字体
        color = DARK_TEXT_BRAND_COLOR  // 与品牌同色
        textSize = brandPaint.textSize  // 同字号
    }

    // 分别测量两段宽度与中间空格，得到整行总宽以便居中
    val spaceWidth = brandPaint.measureText(" ")  // 空格宽度
    val brandWidth = brandPaint.measureText(brand)  // 品牌段宽度
    val modelWidth = if (model.isEmpty()) 0f else modelPaint.measureText(model)  // 型号段宽度
    val gap = if (model.isEmpty()) 0f else spaceWidth  // 两段之间的空格
    val totalWidth = brandWidth + gap + modelWidth  // 整行宽度

    // 第1行基线：按字体度量把行的视觉中心对齐到文字带的 DARK_LINE1_POS 处
    val fm = brandPaint.fontMetrics  // 字体度量
    val baseline1 = photoBottom + bandH * DARK_LINE1_POS - (fm.ascent + fm.descent) / 2f  // 第1行基线

    // 从整行左端开始依次绘制两段
    var x = centerX - totalWidth / 2f  // 当前绘制起点
    if (brand.isNotEmpty()) {
        canvas.drawText(brand, x, baseline1, brandPaint)  // 品牌段
        x += brandWidth + gap  // 前进到型号段起点
    }
    if (model.isNotEmpty()) {
        canvas.drawText(model, x, baseline1, modelPaint)  // 型号段
    }

    // 第2行：拍摄参数，居中对齐、颜色更暗以拉开层次
    if (params.isNotEmpty()) {
        val paramsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER  // 整行居中
            typeface = Typeface.DEFAULT  // 系统默认字体
            color = DARK_TEXT_PARAMS_COLOR  // 次级浅灰
            textSize = bandH * DARK_TEXT2_SIZE_RATIO  // 字号按带高计算
        }
        val fm2 = paramsPaint.fontMetrics  // 参数行字体度量
        val baseline2 = photoBottom + bandH * DARK_LINE2_POS - (fm2.ascent + fm2.descent) / 2f  // 第2行基线
        canvas.drawText(params, centerX, baseline2, paramsPaint)  // 绘制参数行
    }
}

/**
 * 计算位图的平均颜色：先缩到小尺寸再逐像素求均值，避免全图扫描
 */
private fun averageColor(source: Bitmap): Int {
    // 等比缩小到固定小尺寸，缩放过程本身即是一次面积平均
    val small = Bitmap.createScaledBitmap(source, DARK_AVG_SAMPLE_SIZE, DARK_AVG_SAMPLE_SIZE, true)
    var r = 0L  // 红通道累加
    var g = 0L  // 绿通道累加
    var b = 0L  // 蓝通道累加
    // 累加全部像素
    for (y in 0 until DARK_AVG_SAMPLE_SIZE) {
        for (x in 0 until DARK_AVG_SAMPLE_SIZE) {
            val c = small.getPixel(x, y)  // 读取像素
            r += Color.red(c)  // 累加红
            g += Color.green(c)  // 累加绿
            b += Color.blue(c)  // 累加蓝
        }
    }
    // 回收临时小图
    if (small !== source) small.recycle()

    // 求均值并组装颜色
    val n = (DARK_AVG_SAMPLE_SIZE * DARK_AVG_SAMPLE_SIZE).toLong()  // 像素总数
    return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

/**
 * 把颜色等比压暗到目标亮度，保留色相与饱和度倾向
 * @param targetLuma 目标亮度 (0-255)
 */
private fun darkenToTargetLuma(color: Int, targetLuma: Int): Int {
    // 当前亮度（Rec.601 加权）
    val luma = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
    // 纯黑照片无法再暗化，直接返回深灰
    if (luma < 1f) return Color.rgb(8, 8, 8)
    // 缩放系数：把亮度压到目标值，上限 1 表示不提亮
    val factor = (targetLuma / luma).coerceIn(0.02, 1.0)  // 缩放系数在 Double 域内夹紧
    // 三通道等比缩放并限制在合法范围
    return Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),  // 红通道
        (Color.green(color) * factor).toInt().coerceIn(0, 255),  // 绿通道
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)  // 蓝通道
    )
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
 * @param blur 模糊半径（像素）
 * @param alpha 阴影透明度 (0-255)
 * @param offsetY 阴影 Y 轴偏移量（像素）
 */
private fun drawDropShadow(
    canvas: Canvas,
    rect: RectF,
    cornerRadius: Float,
    blur: Float = SHADOW_BLUR_RADIUS,
    alpha: Int = SHADOW_ALPHA,
    offsetY: Float = SHADOW_OFFSET_Y
) {
    // 阴影 Bitmap 需要比主体图大，留出模糊扩散空间（按模糊半径的 3 倍估算，另加原默认余量）
    val padding = (blur * 3f).toInt() + 8  // 阴影扩散边距（像素）
    val shadowW = rect.width().toInt() + padding * 2  // 阴影 Bitmap 宽度
    val shadowH = rect.height().toInt() + padding * 2  // 阴影 Bitmap 高度

    // 创建离屏 Bitmap 用于绘制阴影
    val shadowBmp = Bitmap.createBitmap(shadowW, shadowH, Bitmap.Config.ARGB_8888)
    val shadowCanvas = Canvas(shadowBmp)  // 创建离屏画布

    // 创建阴影画笔，设置模糊效果
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(alpha, 0, 0, 0)  // 半透明黑色
        maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)  // 高斯模糊
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
        rect.top - padding + offsetY  // Y 轴对齐并向下偏移
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

/** 图片导出格式规则：PNG（无损）或 JPG（有损压缩，体积小） */
enum class ExportFormat(val label: String) {
    PNG("PNG"),  // 无损格式，保留 Alpha 通道，文件较大
    JPG("JPG")   // 有损压缩格式，照片类体积小画质好
}

/**
 * 将已渲染好的相框图片保存到系统相册或用户自定义目录
 * 直接复用预览中的位图，避免二次合成（更快且保证与预览一致）
 * @param context Android Context
 * @param bitmap 已合成好的相框图片
 * @param format 导出格式规则（PNG/JPG），默认 JPG
 * @param treeUri 自定义保存目录（SAF 树 Uri），为 null 时保存到系统相册
 * @return 是否保存成功
 */
fun saveComposedImage(
    context: Context,
    bitmap: Bitmap,
    format: ExportFormat = ExportFormat.JPG,
    treeUri: Uri? = null
): Boolean {
    val timestamp = System.currentTimeMillis()  // 时间戳：用于文件名与 EXIF 时间
    // 根据所选格式确定文件扩展名、MIME 类型与压缩格式
    val ext = if (format == ExportFormat.PNG) "png" else "jpg"  // 文件扩展名
    val mime = if (format == ExportFormat.PNG) "image/png" else "image/jpeg"  // MIME 类型
    val compressFormat = if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG  // 系统压缩格式

    // 未设置自定义目录时走 MediaStore，保证图片一定进入系统相册
    return if (treeUri == null) {
        saveToGallery(context, bitmap, timestamp, ext, mime, compressFormat)
    } else {
        // 已设置自定义目录时写入用户授权的文件夹
        saveToTreeDir(context, bitmap, treeUri, timestamp, ext, mime, compressFormat)
    }
}

/**
 * 保存到系统相册（MediaStore Pictures 目录，无需任何存储权限）
 */
private fun saveToGallery(
    context: Context,
    bitmap: Bitmap,
    timestamp: Long,
    ext: String,
    mime: String,
    compressFormat: Bitmap.CompressFormat
): Boolean {
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "framed_$timestamp.$ext")  // 文件名（含格式后缀）
        put(MediaStore.Images.Media.MIME_TYPE, mime)  // MIME 类型
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
        // 打开输出流，按所选格式写入图片数据（PNG 无损、忽略质量参数；JPG 按 SAVE_QUALITY 压缩）
        val os = resolver.openOutputStream(uri) ?: throw java.io.IOException("无法打开输出流")
        os.use { bitmap.compress(compressFormat, SAVE_QUALITY, it) }

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

/**
 * 保存到用户通过 SAF 授权的自定义目录
 * 依赖目录选择时拿到的持久化读写授权，授权被撤销或目录被删除时返回失败
 */
private fun saveToTreeDir(
    context: Context,
    bitmap: Bitmap,
    treeUri: Uri,
    timestamp: Long,
    ext: String,
    mime: String,
    compressFormat: Bitmap.CompressFormat
): Boolean {
    val resolver = context.contentResolver  // 获取内容解析器
    val fileName = "framed_$timestamp.$ext"  // 文件名（含格式后缀）

    // 由树 Uri 还原出该目录的文档 Uri，作为创建文件的父节点
    val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
    if (treeDocId == null) {
        Log.e(TAG, "保存失败：自定义目录 Uri 不合法")
        return false
    }
    val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)  // 父目录文档 Uri

    // 在自定义目录内创建新文件，返回 null 表示目录不可写或授权已失效
    val fileUri = try {
        DocumentsContract.createDocument(resolver, parentUri, mime, fileName)
    } catch (e: Exception) {
        Log.e(TAG, "创建文件失败：自定义目录不可用或授权已失效", e)
        null
    } ?: return false

    return try {
        // 打开输出流写入图片数据
        val os = resolver.openOutputStream(fileUri) ?: throw java.io.IOException("无法打开输出流")
        os.use { bitmap.compress(compressFormat, SAVE_QUALITY, it) }
        true
    } catch (e: Exception) {
        Log.e(TAG, "写入自定义目录失败", e)
        // 写入失败时删除半成品文件，避免目录里留下 0 字节图片
        runCatching { resolver.delete(fileUri, null, null) }
        false
    }
}
