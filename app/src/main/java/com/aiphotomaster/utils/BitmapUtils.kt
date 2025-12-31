package com.aiphotomaster.utils

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Bitmap 工具类
 *
 * 提供 Bitmap 的常用操作：
 * - 旋转
 * - 缩放
 * - Uri 转 Bitmap
 * - 保存到文件
 * - EXIF 方向处理
 */
object BitmapUtils {

    /**
     * 旋转 Bitmap
     *
     * @param bitmap 原始 Bitmap
     * @param degrees 旋转角度（顺时针）
     * @return 旋转后的 Bitmap
     */
    fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap

        val matrix = Matrix().apply {
            postRotate(degrees)
        }
        return Bitmap.createBitmap(
            bitmap,
            0, 0,
            bitmap.width, bitmap.height,
            matrix,
            true
        )
    }

    /**
     * 按比例缩放 Bitmap
     *
     * @param bitmap 原始 Bitmap
     * @param scale 缩放比例 (0.5 = 缩小一半, 2.0 = 放大两倍)
     * @return 缩放后的 Bitmap
     */
    fun scale(bitmap: Bitmap, scale: Float): Bitmap {
        if (scale == 1f) return bitmap

        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * 缩放 Bitmap 到指定尺寸
     *
     * @param bitmap 原始 Bitmap
     * @param targetWidth 目标宽度
     * @param targetHeight 目标高度
     * @param keepAspectRatio 是否保持宽高比
     * @return 缩放后的 Bitmap
     */
    fun scaleTo(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        keepAspectRatio: Boolean = true
    ): Bitmap {
        if (!keepAspectRatio) {
            return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }

        val aspectRatio = bitmap.width.toFloat() / bitmap.height
        val targetAspectRatio = targetWidth.toFloat() / targetHeight

        val (newWidth, newHeight) = if (aspectRatio > targetAspectRatio) {
            // 原图更宽，以宽度为基准
            targetWidth to (targetWidth / aspectRatio).toInt()
        } else {
            // 原图更高，以高度为基准
            (targetHeight * aspectRatio).toInt() to targetHeight
        }

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * 缩放 Bitmap 到最大边不超过指定值
     *
     * @param bitmap 原始 Bitmap
     * @param maxSize 最大边长度
     * @return 缩放后的 Bitmap（如果原图已经小于 maxSize，返回原图）
     */
    fun scaleToMaxSize(bitmap: Bitmap, maxSize: Int): Bitmap {
        val maxDimension = maxOf(bitmap.width, bitmap.height)
        if (maxDimension <= maxSize) return bitmap

        val scale = maxSize.toFloat() / maxDimension
        return scale(bitmap, scale)
    }

    /**
     * 从 Uri 加载 Bitmap（异步）
     *
     * @param context Context
     * @param uri 图片 Uri
     * @param maxSize 最大边长度（用于内存优化，默认 2048）
     * @return Bitmap 或 null
     */
    suspend fun fromUri(
        context: Context,
        uri: Uri,
        maxSize: Int = 2048
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver

            // 第一次读取：获取图片尺寸
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // 计算采样率
            options.inSampleSize = calculateInSampleSize(options, maxSize, maxSize)
            options.inJustDecodeBounds = false

            // 第二次读取：实际解码
            val bitmap = contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // 处理 EXIF 方向
            bitmap?.let { bmp ->
                val orientation = getExifOrientation(contentResolver, uri)
                applyExifOrientation(bmp, orientation)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 从 Uri 同步加载 Bitmap
     *
     * @param context Context
     * @param uri 图片 Uri
     * @param maxSize 最大边长度
     * @return Bitmap 或 null
     */
    fun fromUriSync(
        context: Context,
        uri: Uri,
        maxSize: Int = 2048
    ): Bitmap? {
        return try {
            val contentResolver = context.contentResolver

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            options.inSampleSize = calculateInSampleSize(options, maxSize, maxSize)
            options.inJustDecodeBounds = false

            val bitmap = contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            bitmap?.let { bmp ->
                val orientation = getExifOrientation(contentResolver, uri)
                applyExifOrientation(bmp, orientation)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 从文件路径加载 Bitmap
     *
     * @param filePath 文件路径
     * @param maxSize 最大边长度
     * @return Bitmap 或 null
     */
    fun fromFile(filePath: String, maxSize: Int = 2048): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(filePath, options)

            options.inSampleSize = calculateInSampleSize(options, maxSize, maxSize)
            options.inJustDecodeBounds = false

            val bitmap = BitmapFactory.decodeFile(filePath, options)

            bitmap?.let { bmp ->
                val exif = ExifInterface(filePath)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                applyExifOrientation(bmp, orientation)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 保存 Bitmap 到文件（异步）
     *
     * @param bitmap 要保存的 Bitmap
     * @param file 目标文件
     * @param format 图片格式
     * @param quality 质量（0-100）
     * @return 是否保存成功
     */
    suspend fun saveToFile(
        bitmap: Bitmap,
        file: File,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 90
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { outputStream ->
                bitmap.compress(format, quality, outputStream)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 水平翻转 Bitmap
     */
    fun flipHorizontal(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply {
            preScale(-1f, 1f)
        }
        return Bitmap.createBitmap(
            bitmap,
            0, 0,
            bitmap.width, bitmap.height,
            matrix,
            true
        )
    }

    /**
     * 垂直翻转 Bitmap
     */
    fun flipVertical(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply {
            preScale(1f, -1f)
        }
        return Bitmap.createBitmap(
            bitmap,
            0, 0,
            bitmap.width, bitmap.height,
            matrix,
            true
        )
    }

    /**
     * 裁剪 Bitmap
     *
     * @param bitmap 原始 Bitmap
     * @param x 起始 X 坐标
     * @param y 起始 Y 坐标
     * @param width 裁剪宽度
     * @param height 裁剪高度
     * @return 裁剪后的 Bitmap
     */
    fun crop(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
        val safeX = x.coerceIn(0, bitmap.width - 1)
        val safeY = y.coerceIn(0, bitmap.height - 1)
        val safeWidth = width.coerceIn(1, bitmap.width - safeX)
        val safeHeight = height.coerceIn(1, bitmap.height - safeY)

        return Bitmap.createBitmap(bitmap, safeX, safeY, safeWidth, safeHeight)
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 计算采样率
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight &&
                (halfWidth / inSampleSize) >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 获取 EXIF 方向信息
     */
    private fun getExifOrientation(contentResolver: ContentResolver, uri: Uri): Int {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /**
     * 根据 EXIF 方向调整 Bitmap
     */
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }

        return Bitmap.createBitmap(
            bitmap,
            0, 0,
            bitmap.width, bitmap.height,
            matrix,
            true
        )
    }
}
