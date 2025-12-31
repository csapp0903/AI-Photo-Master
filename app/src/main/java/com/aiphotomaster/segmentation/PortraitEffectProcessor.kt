package com.aiphotomaster.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 人像效果处理器
 *
 * 提供基于人像分割的创意效果：
 * - 背景虚化（人像模式）
 * - 人像留色（选择性彩色）
 * - 背景替换
 * - 人像边缘光效
 *
 * 使用示例：
 * ```kotlin
 * val processor = PortraitEffectProcessor.create(context)
 *
 * // 背景虚化
 * val blurred = processor.applyBackgroundBlur(bitmap, blurRadius = 25f)
 *
 * // 人像留色
 * val colorPop = processor.applyColorPop(bitmap)
 *
 * processor.release()
 * ```
 */
class PortraitEffectProcessor private constructor(
    private val context: Context,
    private val segmentationHelper: SegmentationHelper
) {

    companion object {
        // 默认模糊半径
        private const val DEFAULT_BLUR_RADIUS = 25f

        // 最大模糊半径（RenderScript 限制）
        private const val MAX_BLUR_RADIUS = 25f

        /**
         * 创建处理器实例
         */
        fun create(context: Context): PortraitEffectProcessor {
            val helper = SegmentationHelper.createForSingleImage()
            return PortraitEffectProcessor(context.applicationContext, helper)
        }
    }

    // 缓存的分割结果
    private var cachedResult: SegmentationResult? = null
    private var cachedBitmapHash: Int = 0

    // ==================== 功能 A: 背景虚化 ====================

    /**
     * 应用背景虚化效果（人像模式）
     *
     * @param bitmap 输入图片
     * @param blurRadius 模糊半径 (1.0 ~ 25.0)
     * @param useSoftEdge 是否使用软边缘过渡
     * @return 处理后的图片
     */
    suspend fun applyBackgroundBlur(
        bitmap: Bitmap,
        blurRadius: Float = DEFAULT_BLUR_RADIUS,
        useSoftEdge: Boolean = true
    ): Bitmap = withContext(Dispatchers.Default) {
        // 获取分割结果
        val segResult = getSegmentationResult(bitmap)

        // 创建模糊背景
        val blurredBackground = applyGaussianBlur(bitmap, blurRadius.coerceIn(1f, MAX_BLUR_RADIUS))

        // 获取掩码
        val maskBitmap = if (useSoftEdge) {
            segResult.toSoftMaskBitmap()
        } else {
            segResult.toMaskBitmap(0.5f)
        }

        // 合成结果
        val result = compositeWithMask(
            foreground = bitmap,
            background = blurredBackground,
            mask = maskBitmap
        )

        // 清理
        blurredBackground.recycle()
        maskBitmap.recycle()

        result
    }

    /**
     * 应用渐进式背景虚化
     * 距离人像越远，模糊程度越高
     *
     * @param bitmap 输入图片
     * @param maxBlurRadius 最大模糊半径
     * @return 处理后的图片
     */
    suspend fun applyGradientBackgroundBlur(
        bitmap: Bitmap,
        maxBlurRadius: Float = DEFAULT_BLUR_RADIUS
    ): Bitmap = withContext(Dispatchers.Default) {
        val segResult = getSegmentationResult(bitmap)

        // 创建多个不同模糊程度的图层
        val blurLayers = listOf(
            applyGaussianBlur(bitmap, maxBlurRadius * 0.3f),
            applyGaussianBlur(bitmap, maxBlurRadius * 0.6f),
            applyGaussianBlur(bitmap, maxBlurRadius)
        )

        val confidenceArray = segResult.getConfidenceArray()
        val width = segResult.maskWidth
        val height = segResult.maskHeight

        // 缩放原图到掩码尺寸
        val scaledOriginal = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }

        val scaledLayers = blurLayers.map { layer ->
            if (layer.width != width || layer.height != height) {
                Bitmap.createScaledBitmap(layer, width, height, true).also { layer.recycle() }
            } else {
                layer
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val originalPixels = IntArray(width * height)
        val layer0Pixels = IntArray(width * height)
        val layer1Pixels = IntArray(width * height)
        val layer2Pixels = IntArray(width * height)
        val resultPixels = IntArray(width * height)

        scaledOriginal.getPixels(originalPixels, 0, width, 0, 0, width, height)
        scaledLayers[0].getPixels(layer0Pixels, 0, width, 0, 0, width, height)
        scaledLayers[1].getPixels(layer1Pixels, 0, width, 0, 0, width, height)
        scaledLayers[2].getPixels(layer2Pixels, 0, width, 0, 0, width, height)

        for (i in resultPixels.indices) {
            val confidence = confidenceArray[i]

            when {
                confidence > 0.7f -> {
                    // 人像区域：使用原图
                    resultPixels[i] = originalPixels[i]
                }
                confidence > 0.4f -> {
                    // 边缘过渡：轻微模糊
                    val t = (confidence - 0.4f) / 0.3f
                    resultPixels[i] = blendPixels(layer0Pixels[i], originalPixels[i], t)
                }
                confidence > 0.2f -> {
                    // 近景背景：中等模糊
                    val t = (confidence - 0.2f) / 0.2f
                    resultPixels[i] = blendPixels(layer1Pixels[i], layer0Pixels[i], t)
                }
                else -> {
                    // 远景背景：最大模糊
                    val t = confidence / 0.2f
                    resultPixels[i] = blendPixels(layer2Pixels[i], layer1Pixels[i], t)
                }
            }
        }

        result.setPixels(resultPixels, 0, width, 0, 0, width, height)

        // 清理
        scaledLayers.forEach { it.recycle() }
        if (scaledOriginal != bitmap) scaledOriginal.recycle()

        // 缩放回原始尺寸
        if (width != bitmap.width || height != bitmap.height) {
            Bitmap.createScaledBitmap(result, bitmap.width, bitmap.height, true).also {
                result.recycle()
            }
        } else {
            result
        }
    }

    // ==================== 功能 B: 人像留色 ====================

    /**
     * 应用人像留色效果
     * 背景转为黑白，人像保留彩色
     *
     * @param bitmap 输入图片
     * @param useSoftEdge 是否使用软边缘过渡
     * @return 处理后的图片
     */
    suspend fun applyColorPop(
        bitmap: Bitmap,
        useSoftEdge: Boolean = true
    ): Bitmap = withContext(Dispatchers.Default) {
        val segResult = getSegmentationResult(bitmap)

        // 创建黑白背景
        val grayscaleBackground = applyGrayscale(bitmap)

        // 获取掩码
        val maskBitmap = if (useSoftEdge) {
            segResult.toSoftMaskBitmap()
        } else {
            segResult.toMaskBitmap(0.5f)
        }

        // 合成结果
        val result = compositeWithMask(
            foreground = bitmap,
            background = grayscaleBackground,
            mask = maskBitmap
        )

        // 清理
        grayscaleBackground.recycle()
        maskBitmap.recycle()

        result
    }

    /**
     * 应用反向人像留色效果
     * 人像转为黑白，背景保留彩色
     *
     * @param bitmap 输入图片
     * @return 处理后的图片
     */
    suspend fun applyInverseColorPop(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val segResult = getSegmentationResult(bitmap)

        // 创建黑白人像
        val grayscaleForeground = applyGrayscale(bitmap)

        // 获取反向掩码
        val maskBitmap = segResult.toSoftMaskBitmap()
        val invertedMask = invertMask(maskBitmap)
        maskBitmap.recycle()

        // 合成结果
        val result = compositeWithMask(
            foreground = bitmap,
            background = grayscaleForeground,
            mask = invertedMask
        )

        // 清理
        grayscaleForeground.recycle()
        invertedMask.recycle()

        result
    }

    // ==================== 功能 C: 背景替换 ====================

    /**
     * 替换背景
     *
     * @param portrait 包含人像的原图
     * @param newBackground 新背景图片
     * @param useSoftEdge 是否使用软边缘
     * @return 合成后的图片
     */
    suspend fun replaceBackground(
        portrait: Bitmap,
        newBackground: Bitmap,
        useSoftEdge: Boolean = true
    ): Bitmap = withContext(Dispatchers.Default) {
        val segResult = getSegmentationResult(portrait)

        // 缩放新背景到原图尺寸
        val scaledBackground = if (newBackground.width != portrait.width ||
            newBackground.height != portrait.height) {
            Bitmap.createScaledBitmap(newBackground, portrait.width, portrait.height, true)
        } else {
            newBackground.copy(Bitmap.Config.ARGB_8888, false)
        }

        // 获取掩码
        val maskBitmap = if (useSoftEdge) {
            segResult.toSoftMaskBitmap()
        } else {
            segResult.toMaskBitmap(0.5f)
        }

        // 合成结果
        val result = compositeWithMask(
            foreground = portrait,
            background = scaledBackground,
            mask = maskBitmap
        )

        // 清理
        scaledBackground.recycle()
        maskBitmap.recycle()

        result
    }

    /**
     * 替换背景为纯色
     *
     * @param portrait 包含人像的原图
     * @param backgroundColor 背景颜色
     * @return 合成后的图片
     */
    suspend fun replaceBackgroundWithColor(
        portrait: Bitmap,
        backgroundColor: Int
    ): Bitmap = withContext(Dispatchers.Default) {
        val segResult = getSegmentationResult(portrait)

        // 创建纯色背景
        val colorBackground = Bitmap.createBitmap(
            portrait.width,
            portrait.height,
            Bitmap.Config.ARGB_8888
        )
        colorBackground.eraseColor(backgroundColor)

        // 获取软边缘掩码
        val maskBitmap = segResult.toSoftMaskBitmap()

        // 合成结果
        val result = compositeWithMask(
            foreground = portrait,
            background = colorBackground,
            mask = maskBitmap
        )

        // 清理
        colorBackground.recycle()
        maskBitmap.recycle()

        result
    }

    // ==================== 功能 D: 边缘光效 ====================

    /**
     * 添加人像边缘光效
     *
     * @param bitmap 输入图片
     * @param glowColor 光效颜色
     * @param glowRadius 光效半径
     * @return 处理后的图片
     */
    suspend fun applyEdgeGlow(
        bitmap: Bitmap,
        glowColor: Int = Color.WHITE,
        glowRadius: Float = 10f
    ): Bitmap = withContext(Dispatchers.Default) {
        val segResult = getSegmentationResult(bitmap)

        // 获取掩码
        val maskBitmap = segResult.toMaskBitmap(0.5f)

        // 创建光效层
        val glowLayer = createEdgeGlow(maskBitmap, glowColor, glowRadius)

        // 合成结果
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // 绘制原图
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        // 绘制光效层（叠加模式）
        val glowPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
        canvas.drawBitmap(glowLayer, 0f, 0f, glowPaint)

        // 清理
        maskBitmap.recycle()
        glowLayer.recycle()

        result
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 获取分割结果（带缓存）
     */
    private suspend fun getSegmentationResult(bitmap: Bitmap): SegmentationResult {
        val hash = bitmap.hashCode()
        if (cachedBitmapHash == hash && cachedResult != null) {
            return cachedResult!!
        }

        val result = segmentationHelper.process(bitmap)
        cachedResult = result
        cachedBitmapHash = hash
        return result
    }

    /**
     * 应用高斯模糊
     */
    @Suppress("DEPRECATION")
    private fun applyGaussianBlur(bitmap: Bitmap, radius: Float): Bitmap {
        val inputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val outputBitmap = Bitmap.createBitmap(inputBitmap)

        val rs = RenderScript.create(context)
        val input = Allocation.createFromBitmap(rs, inputBitmap)
        val output = Allocation.createFromBitmap(rs, outputBitmap)

        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        script.setRadius(radius.coerceIn(1f, 25f))
        script.setInput(input)
        script.forEach(output)

        output.copyTo(outputBitmap)

        input.destroy()
        output.destroy()
        script.destroy()
        rs.destroy()

        inputBitmap.recycle()

        return outputBitmap
    }

    /**
     * 应用灰度效果
     */
    private fun applyGrayscale(bitmap: Bitmap): Bitmap {
        val grayscale = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscale)

        val colorMatrix = ColorMatrix().apply {
            setSaturation(0f)
        }
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }

        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return grayscale
    }

    /**
     * 使用掩码合成前景和背景
     */
    private fun compositeWithMask(
        foreground: Bitmap,
        background: Bitmap,
        mask: Bitmap
    ): Bitmap {
        val width = foreground.width
        val height = foreground.height

        // 确保所有图片尺寸一致
        val scaledMask = if (mask.width != width || mask.height != height) {
            Bitmap.createScaledBitmap(mask, width, height, true)
        } else {
            mask
        }

        val scaledBackground = if (background.width != width || background.height != height) {
            Bitmap.createScaledBitmap(background, width, height, true)
        } else {
            background
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val foregroundPixels = IntArray(width * height)
        val backgroundPixels = IntArray(width * height)
        val maskPixels = IntArray(width * height)
        val resultPixels = IntArray(width * height)

        foreground.getPixels(foregroundPixels, 0, width, 0, 0, width, height)
        scaledBackground.getPixels(backgroundPixels, 0, width, 0, 0, width, height)
        scaledMask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        for (i in resultPixels.indices) {
            val maskValue = Color.red(maskPixels[i]) / 255f  // 使用红色通道作为掩码值
            resultPixels[i] = blendPixels(backgroundPixels[i], foregroundPixels[i], maskValue)
        }

        result.setPixels(resultPixels, 0, width, 0, 0, width, height)

        // 清理临时缩放的图片
        if (scaledMask != mask) scaledMask.recycle()
        if (scaledBackground != background) scaledBackground.recycle()

        return result
    }

    /**
     * 混合两个像素
     */
    private fun blendPixels(pixel1: Int, pixel2: Int, t: Float): Int {
        val r = (Color.red(pixel1) * (1 - t) + Color.red(pixel2) * t).toInt()
        val g = (Color.green(pixel1) * (1 - t) + Color.green(pixel2) * t).toInt()
        val b = (Color.blue(pixel1) * (1 - t) + Color.blue(pixel2) * t).toInt()
        val a = (Color.alpha(pixel1) * (1 - t) + Color.alpha(pixel2) * t).toInt()
        return Color.argb(a, r, g, b)
    }

    /**
     * 反转掩码
     */
    private fun invertMask(mask: Bitmap): Bitmap {
        val width = mask.width
        val height = mask.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        val resultPixels = IntArray(width * height)
        mask.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val r = 255 - Color.red(pixels[i])
            val g = 255 - Color.green(pixels[i])
            val b = 255 - Color.blue(pixels[i])
            resultPixels[i] = Color.rgb(r, g, b)
        }

        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * 创建边缘光效层
     */
    private fun createEdgeGlow(mask: Bitmap, glowColor: Int, radius: Float): Bitmap {
        // 模糊掩码以创建光晕
        val blurredMask = applyGaussianBlur(mask, radius.coerceIn(1f, 25f))

        val width = mask.width
        val height = mask.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val originalPixels = IntArray(width * height)
        val blurredPixels = IntArray(width * height)
        val resultPixels = IntArray(width * height)

        mask.getPixels(originalPixels, 0, width, 0, 0, width, height)
        blurredMask.getPixels(blurredPixels, 0, width, 0, 0, width, height)

        val glowR = Color.red(glowColor)
        val glowG = Color.green(glowColor)
        val glowB = Color.blue(glowColor)

        for (i in resultPixels.indices) {
            val originalValue = Color.red(originalPixels[i])
            val blurredValue = Color.red(blurredPixels[i])

            // 边缘 = 模糊值 - 原始值（只在边缘处有值）
            val edgeValue = (blurredValue - originalValue).coerceIn(0, 255)

            if (edgeValue > 10) {
                val alpha = edgeValue
                resultPixels[i] = Color.argb(alpha, glowR, glowG, glowB)
            } else {
                resultPixels[i] = Color.TRANSPARENT
            }
        }

        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        blurredMask.recycle()

        return result
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        cachedResult = null
        cachedBitmapHash = 0
    }

    /**
     * 释放资源
     */
    fun release() {
        clearCache()
        segmentationHelper.close()
    }
}
