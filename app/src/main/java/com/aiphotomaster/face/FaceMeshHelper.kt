package com.aiphotomaster.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Face Mesh 辅助类
 *
 * 封装 MediaPipe Face Landmarker，提供：
 * - 静态图片面部关键点检测
 * - 实时视频流面部关键点检测
 * - 468 + 10 (虹膜) = 478 个关键点输出
 */
class FaceMeshHelper private constructor(
    private val faceLandmarker: FaceLandmarker,
    private val runningMode: RunningMode
) {

    companion object {
        // MediaPipe Face Landmarker 模型文件名
        private const val MODEL_ASSET_PATH = "face_landmarker.task"

        // 默认配置
        private const val DEFAULT_NUM_FACES = 1
        private const val DEFAULT_MIN_DETECTION_CONFIDENCE = 0.5f
        private const val DEFAULT_MIN_TRACKING_CONFIDENCE = 0.5f
        private const val DEFAULT_MIN_PRESENCE_CONFIDENCE = 0.5f

        /**
         * 创建用于静态图片的 FaceMeshHelper
         */
        suspend fun createForImage(
            context: Context,
            numFaces: Int = DEFAULT_NUM_FACES,
            minDetectionConfidence: Float = DEFAULT_MIN_DETECTION_CONFIDENCE,
            useGpu: Boolean = true
        ): FaceMeshHelper = withContext(Dispatchers.IO) {
            val faceLandmarker = buildFaceLandmarker(
                context = context,
                runningMode = RunningMode.IMAGE,
                numFaces = numFaces,
                minDetectionConfidence = minDetectionConfidence,
                minTrackingConfidence = DEFAULT_MIN_TRACKING_CONFIDENCE,
                minPresenceConfidence = DEFAULT_MIN_PRESENCE_CONFIDENCE,
                useGpu = useGpu,
                resultListener = null
            )
            FaceMeshHelper(faceLandmarker, RunningMode.IMAGE)
        }

        /**
         * 创建用于视频流的 FaceMeshHelper
         */
        suspend fun createForVideo(
            context: Context,
            numFaces: Int = DEFAULT_NUM_FACES,
            minDetectionConfidence: Float = DEFAULT_MIN_DETECTION_CONFIDENCE,
            minTrackingConfidence: Float = DEFAULT_MIN_TRACKING_CONFIDENCE,
            useGpu: Boolean = true
        ): FaceMeshHelper = withContext(Dispatchers.IO) {
            val faceLandmarker = buildFaceLandmarker(
                context = context,
                runningMode = RunningMode.VIDEO,
                numFaces = numFaces,
                minDetectionConfidence = minDetectionConfidence,
                minTrackingConfidence = minTrackingConfidence,
                minPresenceConfidence = DEFAULT_MIN_PRESENCE_CONFIDENCE,
                useGpu = useGpu,
                resultListener = null
            )
            FaceMeshHelper(faceLandmarker, RunningMode.VIDEO)
        }

        /**
         * 创建用于实时流的 FaceMeshHelper
         */
        suspend fun createForLiveStream(
            context: Context,
            numFaces: Int = DEFAULT_NUM_FACES,
            minDetectionConfidence: Float = DEFAULT_MIN_DETECTION_CONFIDENCE,
            minTrackingConfidence: Float = DEFAULT_MIN_TRACKING_CONFIDENCE,
            useGpu: Boolean = true,
            resultListener: FaceMeshResultListener
        ): FaceMeshHelper = withContext(Dispatchers.IO) {
            val faceLandmarker = buildFaceLandmarker(
                context = context,
                runningMode = RunningMode.LIVE_STREAM,
                numFaces = numFaces,
                minDetectionConfidence = minDetectionConfidence,
                minTrackingConfidence = minTrackingConfidence,
                minPresenceConfidence = DEFAULT_MIN_PRESENCE_CONFIDENCE,
                useGpu = useGpu,
                resultListener = resultListener
            )
            FaceMeshHelper(faceLandmarker, RunningMode.LIVE_STREAM)
        }

        private fun buildFaceLandmarker(
            context: Context,
            runningMode: RunningMode,
            numFaces: Int,
            minDetectionConfidence: Float,
            minTrackingConfidence: Float,
            minPresenceConfidence: Float,
            useGpu: Boolean,
            resultListener: FaceMeshResultListener?
        ): FaceLandmarker {
            val baseOptionsBuilder = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)

            // GPU 加速配置
            if (useGpu) {
                baseOptionsBuilder.setDelegate(Delegate.GPU)
            } else {
                baseOptionsBuilder.setDelegate(Delegate.CPU)
            }

            val optionsBuilder = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setRunningMode(runningMode)
                .setNumFaces(numFaces)
                .setMinFaceDetectionConfidence(minDetectionConfidence)
                .setMinFacePresenceConfidence(minPresenceConfidence)
                .setMinTrackingConfidence(minTrackingConfidence)
                .setOutputFaceBlendshapes(false)  // 不需要表情混合形状
                .setOutputFacialTransformationMatrixes(false)  // 不需要变换矩阵

            // 实时流模式需要结果监听器
            if (runningMode == RunningMode.LIVE_STREAM && resultListener != null) {
                optionsBuilder.setResultListener { result, input ->
                    val faceResult = parseFaceLandmarkerResult(result)
                    resultListener.onResult(faceResult, input.width, input.height)
                }
                optionsBuilder.setErrorListener { error ->
                    resultListener.onError(error.message ?: "Unknown error")
                }
            }

            return FaceLandmarker.createFromOptions(context, optionsBuilder.build())
        }

        /**
         * 解析 FaceLandmarkerResult 为 FaceMeshResult
         */
        private fun parseFaceLandmarkerResult(result: FaceLandmarkerResult): FaceMeshResult {
            val faces = result.faceLandmarks().mapIndexed { index, landmarks ->
                FaceData(
                    faceIndex = index,
                    landmarks = landmarks.map { landmark ->
                        NormalizedLandmark(
                            x = landmark.x(),
                            y = landmark.y(),
                            z = landmark.z()
                        )
                    }
                )
            }
            return FaceMeshResult(faces = faces, timestampMs = System.currentTimeMillis())
        }
    }

    /**
     * 检测静态图片中的面部关键点
     *
     * @param bitmap 输入图片
     * @return FaceMeshResult 包含检测到的所有人脸数据
     */
    fun detectImage(bitmap: Bitmap): FaceMeshResult {
        require(runningMode == RunningMode.IMAGE) {
            "This helper is configured for ${runningMode}, not IMAGE mode"
        }

        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = faceLandmarker.detect(mpImage)
        return parseFaceLandmarkerResult(result)
    }

    /**
     * 检测静态图片（异步）
     */
    suspend fun detectImageAsync(bitmap: Bitmap): FaceMeshResult = withContext(Dispatchers.Default) {
        detectImage(bitmap)
    }

    /**
     * 检测视频帧中的面部关键点
     *
     * @param bitmap 视频帧
     * @param timestampMs 帧时间戳（毫秒）
     * @return FaceMeshResult
     */
    fun detectVideoFrame(bitmap: Bitmap, timestampMs: Long): FaceMeshResult {
        require(runningMode == RunningMode.VIDEO) {
            "This helper is configured for ${runningMode}, not VIDEO mode"
        }

        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = faceLandmarker.detectForVideo(mpImage, timestampMs)
        return parseFaceLandmarkerResult(result)
    }

    /**
     * 处理实时相机帧（异步回调）
     *
     * @param bitmap 相机帧
     * @param timestampMs 帧时间戳
     * @param isFrontCamera 是否为前置摄像头（需要镜像）
     */
    fun detectLiveStream(
        bitmap: Bitmap,
        timestampMs: Long,
        isFrontCamera: Boolean = false
    ) {
        require(runningMode == RunningMode.LIVE_STREAM) {
            "This helper is configured for ${runningMode}, not LIVE_STREAM mode"
        }

        val processedBitmap = if (isFrontCamera) {
            // 前置摄像头需要水平翻转
            val matrix = Matrix().apply { preScale(-1f, 1f) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val mpImage = BitmapImageBuilder(processedBitmap).build()
        faceLandmarker.detectAsync(mpImage, timestampMs)
    }

    /**
     * 释放资源
     */
    fun close() {
        faceLandmarker.close()
    }

    // ==================== 便捷方法 ====================

    /**
     * 从检测结果中提取第一张人脸的关键点
     */
    fun getFirstFaceLandmarks(result: FaceMeshResult): List<NormalizedLandmark>? {
        return result.faces.firstOrNull()?.landmarks
    }

    /**
     * 将归一化坐标转换为像素坐标
     */
    fun landmarksToPixels(
        landmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): List<PixelLandmark> {
        return landmarks.map { landmark ->
            PixelLandmark(
                x = landmark.x * imageWidth,
                y = landmark.y * imageHeight,
                z = landmark.z
            )
        }
    }

    /**
     * 获取特定索引的关键点
     */
    fun getLandmarkAt(landmarks: List<NormalizedLandmark>, index: Int): NormalizedLandmark? {
        return landmarks.getOrNull(index)
    }

    /**
     * 获取多个索引的关键点
     */
    fun getLandmarksAt(
        landmarks: List<NormalizedLandmark>,
        indices: IntArray
    ): List<NormalizedLandmark> {
        return indices.mapNotNull { landmarks.getOrNull(it) }
    }

    /**
     * 计算两个关键点之间的距离
     */
    fun distanceBetween(p1: NormalizedLandmark, p2: NormalizedLandmark): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * 计算关键点的中心位置
     */
    fun centerOf(landmarks: List<NormalizedLandmark>): NormalizedLandmark {
        if (landmarks.isEmpty()) return NormalizedLandmark(0.5f, 0.5f, 0f)

        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        landmarks.forEach { lm ->
            sumX += lm.x
            sumY += lm.y
            sumZ += lm.z
        }
        val count = landmarks.size.toFloat()
        return NormalizedLandmark(sumX / count, sumY / count, sumZ / count)
    }
}

// ==================== 数据类 ====================

/**
 * 归一化关键点（坐标范围 0.0 ~ 1.0）
 */
data class NormalizedLandmark(
    val x: Float,  // 水平位置 (0=左, 1=右)
    val y: Float,  // 垂直位置 (0=上, 1=下)
    val z: Float   // 深度 (相对于面部中心)
)

/**
 * 像素坐标关键点
 */
data class PixelLandmark(
    val x: Float,
    val y: Float,
    val z: Float
)

/**
 * 单张人脸数据
 */
data class FaceData(
    val faceIndex: Int,
    val landmarks: List<NormalizedLandmark>  // 478 个点 (468 面部 + 10 虹膜)
)

/**
 * 面部检测结果
 */
data class FaceMeshResult(
    val faces: List<FaceData>,
    val timestampMs: Long
) {
    val hasFace: Boolean get() = faces.isNotEmpty()
    val faceCount: Int get() = faces.size
}

/**
 * 实时流结果回调接口
 */
interface FaceMeshResultListener {
    fun onResult(result: FaceMeshResult, imageWidth: Int, imageHeight: Int)
    fun onError(error: String)
}
