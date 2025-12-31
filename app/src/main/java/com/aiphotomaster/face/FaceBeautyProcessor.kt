package com.aiphotomaster.face

import android.content.Context
import android.graphics.Bitmap
import com.aiphotomaster.filter.GPUImageFaceWarpFilter
import jp.co.cyberagent.android.gpuimage.GPUImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 人脸美颜处理器
 *
 * 整合 FaceMeshHelper 和 GPUImageFaceWarpFilter，
 * 提供简单的 API 来处理人脸美颜效果。
 *
 * 使用示例：
 * ```kotlin
 * val processor = FaceBeautyProcessor.create(context)
 *
 * // 设置参数
 * processor.setSlimIntensity(0.5f)
 * processor.setEyeEnlargeIntensity(0.3f)
 *
 * // 处理图片
 * val result = processor.process(inputBitmap)
 *
 * // 释放资源
 * processor.release()
 * ```
 */
class FaceBeautyProcessor private constructor(
    private val context: Context,
    private val faceMeshHelper: FaceMeshHelper,
    private val gpuImage: GPUImage,
    private val faceWarpFilter: GPUImageFaceWarpFilter
) {

    companion object {
        /**
         * 创建 FaceBeautyProcessor 实例
         */
        suspend fun create(context: Context): FaceBeautyProcessor = withContext(Dispatchers.IO) {
            val faceMeshHelper = FaceMeshHelper.createForImage(context)
            val gpuImage = GPUImage(context)
            val faceWarpFilter = GPUImageFaceWarpFilter()

            gpuImage.setFilter(faceWarpFilter)

            FaceBeautyProcessor(context, faceMeshHelper, gpuImage, faceWarpFilter)
        }

        /**
         * 创建 FaceBeautyProcessor 实例（同步版本）
         * 注意：此方法会阻塞当前线程，建议在后台线程调用
         */
        fun createSync(context: Context): FaceBeautyProcessor {
            val faceMeshHelper = kotlinx.coroutines.runBlocking {
                FaceMeshHelper.createForImage(context)
            }
            val gpuImage = GPUImage(context)
            val faceWarpFilter = GPUImageFaceWarpFilter()

            gpuImage.setFilter(faceWarpFilter)

            return FaceBeautyProcessor(context, faceMeshHelper, gpuImage, faceWarpFilter)
        }
    }

    // 当前参数
    private var slimIntensity: Float = 0f
    private var eyeEnlargeIntensity: Float = 0f

    // 缓存的关键点数据
    private var cachedLandmarks: List<NormalizedLandmark>? = null

    /**
     * 设置瘦脸强度
     *
     * @param intensity 0.0 ~ 1.0
     */
    fun setSlimIntensity(intensity: Float) {
        slimIntensity = intensity.coerceIn(0f, 1f)
        faceWarpFilter.setSlimIntensity(slimIntensity)
    }

    /**
     * 获取当前瘦脸强度
     */
    fun getSlimIntensity(): Float = slimIntensity

    /**
     * 设置大眼强度
     *
     * @param intensity 0.0 ~ 1.0
     */
    fun setEyeEnlargeIntensity(intensity: Float) {
        eyeEnlargeIntensity = intensity.coerceIn(0f, 1f)
        faceWarpFilter.setEyeEnlargeIntensity(eyeEnlargeIntensity)
    }

    /**
     * 获取当前大眼强度
     */
    fun getEyeEnlargeIntensity(): Float = eyeEnlargeIntensity

    /**
     * 处理图片（异步）
     *
     * @param bitmap 输入图片
     * @return 处理后的图片
     */
    suspend fun process(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        // 检测人脸关键点
        val result = faceMeshHelper.detectImageAsync(bitmap)

        // 更新滤镜的关键点数据
        val landmarks = faceMeshHelper.getFirstFaceLandmarks(result)
        cachedLandmarks = landmarks
        faceWarpFilter.updateLandmarks(landmarks)

        // 应用滤镜
        gpuImage.setImage(bitmap)
        gpuImage.bitmapWithFilterApplied
    }

    /**
     * 处理图片（同步）
     */
    fun processSync(bitmap: Bitmap): Bitmap {
        // 检测人脸关键点
        val result = faceMeshHelper.detectImage(bitmap)

        // 更新滤镜的关键点数据
        val landmarks = faceMeshHelper.getFirstFaceLandmarks(result)
        cachedLandmarks = landmarks
        faceWarpFilter.updateLandmarks(landmarks)

        // 应用滤镜
        gpuImage.setImage(bitmap)
        return gpuImage.bitmapWithFilterApplied
    }

    /**
     * 仅检测人脸，不应用变形
     *
     * @param bitmap 输入图片
     * @return 是否检测到人脸
     */
    suspend fun detectFace(bitmap: Bitmap): Boolean = withContext(Dispatchers.Default) {
        val result = faceMeshHelper.detectImageAsync(bitmap)
        val landmarks = faceMeshHelper.getFirstFaceLandmarks(result)
        cachedLandmarks = landmarks
        faceWarpFilter.updateLandmarks(landmarks)
        landmarks != null
    }

    /**
     * 使用缓存的关键点重新处理
     * 适用于只调整参数不重新检测人脸的场景
     *
     * @param bitmap 输入图片
     * @return 处理后的图片
     */
    suspend fun reprocessWithCachedLandmarks(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        gpuImage.setImage(bitmap)
        gpuImage.bitmapWithFilterApplied
    }

    /**
     * 获取检测到的人脸数量
     */
    fun getFaceCount(): Int {
        return if (cachedLandmarks != null) 1 else 0
    }

    /**
     * 获取缓存的关键点
     */
    fun getCachedLandmarks(): List<NormalizedLandmark>? = cachedLandmarks

    /**
     * 重置所有参数
     */
    fun reset() {
        slimIntensity = 0f
        eyeEnlargeIntensity = 0f
        faceWarpFilter.reset()
        cachedLandmarks = null
    }

    /**
     * 释放资源
     */
    fun release() {
        faceMeshHelper.close()
        faceWarpFilter.reset()
    }
}

/**
 * 实时人脸美颜处理器
 *
 * 用于相机实时预览的美颜处理
 */
class LiveFaceBeautyProcessor private constructor(
    private val faceMeshHelper: FaceMeshHelper,
    private val faceWarpFilter: GPUImageFaceWarpFilter,
    private val resultCallback: LiveBeautyCallback
) : FaceMeshResultListener {

    companion object {
        /**
         * 创建实时处理器
         */
        suspend fun create(
            context: Context,
            callback: LiveBeautyCallback
        ): LiveFaceBeautyProcessor = withContext(Dispatchers.IO) {
            val faceWarpFilter = GPUImageFaceWarpFilter()
            val processor = LiveFaceBeautyProcessor(
                faceMeshHelper = FaceMeshHelper.createForLiveStream(
                    context = context,
                    resultListener = object : FaceMeshResultListener {
                        override fun onResult(result: FaceMeshResult, imageWidth: Int, imageHeight: Int) {
                            // 将在 LiveFaceBeautyProcessor 中处理
                        }
                        override fun onError(error: String) {}
                    }
                ),
                faceWarpFilter = faceWarpFilter,
                resultCallback = callback
            )
            processor
        }
    }

    private var slimIntensity: Float = 0f
    private var eyeEnlargeIntensity: Float = 0f

    /**
     * 获取滤镜实例（用于添加到 GPUImage 或 GPUImageView）
     */
    fun getFilter(): GPUImageFaceWarpFilter = faceWarpFilter

    /**
     * 处理相机帧
     */
    fun processFrame(bitmap: Bitmap, timestampMs: Long, isFrontCamera: Boolean = false) {
        faceMeshHelper.detectLiveStream(bitmap, timestampMs, isFrontCamera)
    }

    /**
     * 设置瘦脸强度
     */
    fun setSlimIntensity(intensity: Float) {
        slimIntensity = intensity.coerceIn(0f, 1f)
        faceWarpFilter.setSlimIntensity(slimIntensity)
    }

    /**
     * 设置大眼强度
     */
    fun setEyeEnlargeIntensity(intensity: Float) {
        eyeEnlargeIntensity = intensity.coerceIn(0f, 1f)
        faceWarpFilter.setEyeEnlargeIntensity(eyeEnlargeIntensity)
    }

    override fun onResult(result: FaceMeshResult, imageWidth: Int, imageHeight: Int) {
        val landmarks = result.faces.firstOrNull()?.landmarks
        faceWarpFilter.updateLandmarks(landmarks)
        resultCallback.onFaceDetected(result.hasFace, landmarks)
    }

    override fun onError(error: String) {
        resultCallback.onError(error)
    }

    /**
     * 释放资源
     */
    fun release() {
        faceMeshHelper.close()
    }
}

/**
 * 实时美颜回调接口
 */
interface LiveBeautyCallback {
    fun onFaceDetected(hasFace: Boolean, landmarks: List<NormalizedLandmark>?)
    fun onError(error: String)
}
