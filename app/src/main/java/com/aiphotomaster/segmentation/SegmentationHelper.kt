package com.aiphotomaster.segmentation

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 人像分割辅助类
 *
 * 封装 ML Kit Selfie Segmentation，提供：
 * - 静态图片人像分割
 * - 实时流人像分割
 * - 掩码转换为 Bitmap
 *
 * 输出掩码说明：
 * - 每个像素的值范围：0.0 ~ 1.0
 * - 0.0 = 完全是背景
 * - 1.0 = 完全是人像
 * - 中间值表示边缘的过渡区域
 */
class SegmentationHelper private constructor(
    private val segmenter: Segmenter,
    private val options: SelfieSegmenterOptions
) {

    companion object {
        /**
         * 创建用于单张图片的分割器
         */
        fun createForSingleImage(): SegmentationHelper {
            val options = SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()  // 启用原始尺寸掩码
                .build()

            val segmenter = Segmentation.getClient(options)
            return SegmentationHelper(segmenter, options)
        }

        /**
         * 创建用于实时流的分割器
         */
        fun createForStream(): SegmentationHelper {
            val options = SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .enableRawSizeMask()
                .build()

            val segmenter = Segmentation.getClient(options)
            return SegmentationHelper(segmenter, options)
        }
    }

    /**
     * 处理图片并获取分割掩码（异步协程）
     *
     * @param bitmap 输入图片
     * @return SegmentationResult 包含掩码数据
     */
    suspend fun process(bitmap: Bitmap): SegmentationResult = withContext(Dispatchers.Default) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        suspendCancellableCoroutine { continuation ->
            segmenter.process(inputImage)
                .addOnSuccessListener { mask ->
                    val result = SegmentationResult(
                        mask = mask,
                        originalWidth = bitmap.width,
                        originalHeight = bitmap.height
                    )
                    continuation.resume(result)
                }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(e)
                }
        }
    }

    /**
     * 处理图片并直接获取掩码 Bitmap
     *
     * @param bitmap 输入图片
     * @param threshold 阈值 (0.0~1.0)，高于此值的像素被认为是人像
     * @return 掩码 Bitmap（人像=白色，背景=黑色）
     */
    suspend fun processToMaskBitmap(
        bitmap: Bitmap,
        threshold: Float = 0.5f
    ): Bitmap = withContext(Dispatchers.Default) {
        val result = process(bitmap)
        result.toMaskBitmap(threshold)
    }

    /**
     * 处理图片并获取软边缘掩码 Bitmap
     *
     * @param bitmap 输入图片
     * @return 软边缘掩码 Bitmap（灰度值表示置信度）
     */
    suspend fun processToSoftMaskBitmap(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val result = process(bitmap)
        result.toSoftMaskBitmap()
    }

    /**
     * 处理图片并获取人像剪影
     *
     * @param bitmap 输入图片
     * @param threshold 阈值
     * @return 仅包含人像的 Bitmap（背景透明）
     */
    suspend fun extractPortrait(
        bitmap: Bitmap,
        threshold: Float = 0.5f
    ): Bitmap = withContext(Dispatchers.Default) {
        val result = process(bitmap)
        result.extractPortrait(bitmap, threshold)
    }

    /**
     * 释放资源
     */
    fun close() {
        segmenter.close()
    }
}

/**
 * 分割结果数据类
 */
class SegmentationResult(
    val mask: SegmentationMask,
    val originalWidth: Int,
    val originalHeight: Int
) {
    /**
     * 掩码宽度
     */
    val maskWidth: Int get() = mask.width

    /**
     * 掩码高度
     */
    val maskHeight: Int get() = mask.height

    /**
     * 获取原始 ByteBuffer 掩码
     * 每个像素占用 4 字节（Float 格式，值范围 0.0~1.0）
     */
    fun getRawMaskBuffer(): ByteBuffer {
        return mask.buffer
    }

    /**
     * 获取指定位置的置信度值
     *
     * @param x X 坐标（在掩码尺寸范围内）
     * @param y Y 坐标
     * @return 置信度 (0.0~1.0)
     */
    fun getConfidenceAt(x: Int, y: Int): Float {
        if (x < 0 || x >= maskWidth || y < 0 || y >= maskHeight) {
            return 0f
        }

        val buffer = mask.buffer
        buffer.rewind()

        val index = y * maskWidth + x
        buffer.position(index * 4)  // Float = 4 bytes
        return buffer.float
    }

    /**
     * 将掩码转换为二值化 Bitmap
     *
     * @param threshold 阈值
     * @return 黑白掩码 Bitmap
     */
    fun toMaskBitmap(threshold: Float = 0.5f): Bitmap {
        val buffer = mask.buffer
        buffer.rewind()

        val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(maskWidth * maskHeight)

        for (i in pixels.indices) {
            val confidence = buffer.float
            pixels[i] = if (confidence >= threshold) Color.WHITE else Color.BLACK
        }

        maskBitmap.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

        // 如果掩码尺寸与原图不同，进行缩放
        return if (maskWidth != originalWidth || maskHeight != originalHeight) {
            Bitmap.createScaledBitmap(maskBitmap, originalWidth, originalHeight, true).also {
                maskBitmap.recycle()
            }
        } else {
            maskBitmap
        }
    }

    /**
     * 将掩码转换为软边缘 Bitmap（灰度）
     *
     * @return 灰度掩码 Bitmap
     */
    fun toSoftMaskBitmap(): Bitmap {
        val buffer = mask.buffer
        buffer.rewind()

        val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(maskWidth * maskHeight)

        for (i in pixels.indices) {
            val confidence = buffer.float
            val gray = (confidence * 255).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(gray, gray, gray)
        }

        maskBitmap.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

        return if (maskWidth != originalWidth || maskHeight != originalHeight) {
            Bitmap.createScaledBitmap(maskBitmap, originalWidth, originalHeight, true).also {
                maskBitmap.recycle()
            }
        } else {
            maskBitmap
        }
    }

    /**
     * 将掩码转换为 Alpha 通道 Bitmap
     * 可用于直接作为 PorterDuff 遮罩
     *
     * @return 带 Alpha 通道的掩码
     */
    fun toAlphaMaskBitmap(): Bitmap {
        val buffer = mask.buffer
        buffer.rewind()

        val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(maskWidth * maskHeight)

        for (i in pixels.indices) {
            val confidence = buffer.float
            val alpha = (confidence * 255).toInt().coerceIn(0, 255)
            pixels[i] = Color.argb(alpha, 255, 255, 255)
        }

        maskBitmap.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

        return if (maskWidth != originalWidth || maskHeight != originalHeight) {
            Bitmap.createScaledBitmap(maskBitmap, originalWidth, originalHeight, true).also {
                maskBitmap.recycle()
            }
        } else {
            maskBitmap
        }
    }

    /**
     * 提取人像（背景透明）
     *
     * @param original 原始图片
     * @param threshold 阈值
     * @return 人像 Bitmap（背景透明）
     */
    fun extractPortrait(original: Bitmap, threshold: Float = 0.5f): Bitmap {
        val buffer = mask.buffer
        buffer.rewind()

        // 确保尺寸匹配
        val scaledOriginal = if (original.width != maskWidth || original.height != maskHeight) {
            Bitmap.createScaledBitmap(original, maskWidth, maskHeight, true)
        } else {
            original
        }

        val result = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
        val originalPixels = IntArray(maskWidth * maskHeight)
        val resultPixels = IntArray(maskWidth * maskHeight)

        scaledOriginal.getPixels(originalPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

        for (i in resultPixels.indices) {
            val confidence = buffer.float
            if (confidence >= threshold) {
                resultPixels[i] = originalPixels[i]
            } else {
                resultPixels[i] = Color.TRANSPARENT
            }
        }

        result.setPixels(resultPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

        // 缩放回原始尺寸
        return if (maskWidth != originalWidth || maskHeight != originalHeight) {
            Bitmap.createScaledBitmap(result, originalWidth, originalHeight, true).also {
                result.recycle()
                if (scaledOriginal != original) scaledOriginal.recycle()
            }
        } else {
            if (scaledOriginal != original) scaledOriginal.recycle()
            result
        }
    }

    /**
     * 提取人像（使用软边缘，更平滑）
     *
     * @param original 原始图片
     * @return 人像 Bitmap（软边缘过渡）
     */
    fun extractPortraitSoft(original: Bitmap): Bitmap {
        val buffer = mask.buffer
        buffer.rewind()

        val scaledOriginal = if (original.width != maskWidth || original.height != maskHeight) {
            Bitmap.createScaledBitmap(original, maskWidth, maskHeight, true)
        } else {
            original
        }

        val result = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
        val originalPixels = IntArray(maskWidth * maskHeight)
        val resultPixels = IntArray(maskWidth * maskHeight)

        scaledOriginal.getPixels(originalPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

        for (i in resultPixels.indices) {
            val confidence = buffer.float
            val originalPixel = originalPixels[i]

            // 使用置信度作为 alpha 值
            val alpha = (confidence * 255).toInt().coerceIn(0, 255)
            val r = Color.red(originalPixel)
            val g = Color.green(originalPixel)
            val b = Color.blue(originalPixel)

            resultPixels[i] = Color.argb(alpha, r, g, b)
        }

        result.setPixels(resultPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

        return if (maskWidth != originalWidth || maskHeight != originalHeight) {
            Bitmap.createScaledBitmap(result, originalWidth, originalHeight, true).also {
                result.recycle()
                if (scaledOriginal != original) scaledOriginal.recycle()
            }
        } else {
            if (scaledOriginal != original) scaledOriginal.recycle()
            result
        }
    }

    /**
     * 获取置信度数组
     *
     * @return Float 数组，每个元素表示对应像素是人像的置信度
     */
    fun getConfidenceArray(): FloatArray {
        val buffer = mask.buffer
        buffer.rewind()

        val array = FloatArray(maskWidth * maskHeight)
        for (i in array.indices) {
            array[i] = buffer.float
        }
        return array
    }
}
