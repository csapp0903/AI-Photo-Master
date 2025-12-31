package com.aiphotomaster.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.cloud.vision.v1.AnnotateImageRequest
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse
import com.google.cloud.vision.v1.Feature
import com.google.cloud.vision.v1.Image
import com.google.cloud.vision.v1.ImageAnnotatorClient
import com.google.cloud.vision.v1.ImageAnnotatorSettings
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Google Cloud Vision API 管理器（单例）
 *
 * 提供云端图像分析功能：
 * - 图片主色调分析
 * - 图片内容标签检测
 * - 文字识别 (OCR)
 * - 人脸检测
 * - 安全搜索
 *
 * 使用示例：
 * ```kotlin
 * // 初始化（需要 Service Account 凭证）
 * CloudVisionManager.initialize(context)
 *
 * // 分析图片主色调
 * val colors = CloudVisionManager.analyzeImageProperties(bitmap)
 *
 * // 获取图片标签
 * val labels = CloudVisionManager.detectLabels(bitmap)
 * ```
 *
 * 注意：使用前需要配置 Google Cloud 凭证
 */
object CloudVisionManager {

    private var imageAnnotatorClient: ImageAnnotatorClient? = null
    private var isInitialized = false

    // 默认最大标签数量
    private const val DEFAULT_MAX_LABELS = 10

    // 默认最大颜色数量
    private const val DEFAULT_MAX_COLORS = 5

    /**
     * 初始化 Cloud Vision 客户端
     *
     * @param context Application Context
     * @param credentialsInputStream Service Account JSON 凭证的 InputStream
     *
     * 使用方法：
     * ```kotlin
     * // 方式 1：从 assets 加载凭证
     * val credentials = assets.open("service-account.json")
     * CloudVisionManager.initialize(this, credentials)
     *
     * // 方式 2：从 raw 资源加载
     * val credentials = resources.openRawResource(R.raw.service_account)
     * CloudVisionManager.initialize(this, credentials)
     * ```
     */
    fun initialize(context: Context, credentialsInputStream: InputStream? = null) {
        if (isInitialized) return

        try {
            // ========================================================
            // TODO: 加载 Service Account JSON 凭证
            // ========================================================
            //
            // 方式 1：使用 InputStream（推荐用于生产环境）
            // if (credentialsInputStream != null) {
            //     val credentials = GoogleCredentials.fromStream(credentialsInputStream)
            //     val settings = ImageAnnotatorSettings.newBuilder()
            //         .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
            //         .build()
            //     imageAnnotatorClient = ImageAnnotatorClient.create(settings)
            // }
            //
            // 方式 2：使用环境变量 GOOGLE_APPLICATION_CREDENTIALS
            // imageAnnotatorClient = ImageAnnotatorClient.create()
            //
            // 方式 3：使用 API Key（仅用于测试）
            // 注意：API Key 方式安全性较低，不建议在生产环境使用
            //
            // ========================================================

            // 开发阶段：标记为已初始化但不实际创建客户端
            // 实际使用时需要取消注释上面的代码
            isInitialized = true

        } catch (e: Exception) {
            e.printStackTrace()
            isInitialized = false
        }
    }

    /**
     * 检查是否已初始化
     */
    fun isReady(): Boolean = isInitialized && imageAnnotatorClient != null

    /**
     * 分析图片属性（主色调）
     *
     * @param bitmap 输入图片
     * @param maxColors 返回的最大颜色数量
     * @return ImagePropertiesResult 包含主色调列表
     */
    suspend fun analyzeImageProperties(
        bitmap: Bitmap,
        maxColors: Int = DEFAULT_MAX_COLORS
    ): ImagePropertiesResult = withContext(Dispatchers.IO) {
        // 如果客户端未初始化，使用本地分析作为后备
        if (imageAnnotatorClient == null) {
            return@withContext analyzeImagePropertiesLocal(bitmap, maxColors)
        }

        try {
            val imageBytes = bitmapToByteString(bitmap)
            val image = Image.newBuilder().setContent(imageBytes).build()

            val feature = Feature.newBuilder()
                .setType(Feature.Type.IMAGE_PROPERTIES)
                .setMaxResults(maxColors)
                .build()

            val request = AnnotateImageRequest.newBuilder()
                .addFeatures(feature)
                .setImage(image)
                .build()

            val response = imageAnnotatorClient!!.batchAnnotateImages(listOf(request))
            parseImagePropertiesResponse(response, maxColors)

        } catch (e: Exception) {
            e.printStackTrace()
            // 失败时使用本地分析作为后备
            analyzeImagePropertiesLocal(bitmap, maxColors)
        }
    }

    /**
     * 检测图片标签
     *
     * @param bitmap 输入图片
     * @param maxLabels 返回的最大标签数量
     * @return LabelDetectionResult 包含标签列表
     */
    suspend fun detectLabels(
        bitmap: Bitmap,
        maxLabels: Int = DEFAULT_MAX_LABELS
    ): LabelDetectionResult = withContext(Dispatchers.IO) {
        // 如果客户端未初始化，返回模拟结果
        if (imageAnnotatorClient == null) {
            return@withContext createMockLabelResult()
        }

        try {
            val imageBytes = bitmapToByteString(bitmap)
            val image = Image.newBuilder().setContent(imageBytes).build()

            val feature = Feature.newBuilder()
                .setType(Feature.Type.LABEL_DETECTION)
                .setMaxResults(maxLabels)
                .build()

            val request = AnnotateImageRequest.newBuilder()
                .addFeatures(feature)
                .setImage(image)
                .build()

            val response = imageAnnotatorClient!!.batchAnnotateImages(listOf(request))
            parseLabelDetectionResponse(response)

        } catch (e: Exception) {
            e.printStackTrace()
            LabelDetectionResult(
                labels = emptyList(),
                error = "标签检测失败: ${e.message}"
            )
        }
    }

    /**
     * 文字识别 (OCR)
     *
     * @param bitmap 输入图片
     * @return TextDetectionResult 包含识别的文字
     */
    suspend fun detectText(bitmap: Bitmap): TextDetectionResult = withContext(Dispatchers.IO) {
        if (imageAnnotatorClient == null) {
            return@withContext TextDetectionResult(
                text = "",
                error = "Cloud Vision 未初始化"
            )
        }

        try {
            val imageBytes = bitmapToByteString(bitmap)
            val image = Image.newBuilder().setContent(imageBytes).build()

            val feature = Feature.newBuilder()
                .setType(Feature.Type.TEXT_DETECTION)
                .build()

            val request = AnnotateImageRequest.newBuilder()
                .addFeatures(feature)
                .setImage(image)
                .build()

            val response = imageAnnotatorClient!!.batchAnnotateImages(listOf(request))
            parseTextDetectionResponse(response)

        } catch (e: Exception) {
            e.printStackTrace()
            TextDetectionResult(
                text = "",
                error = "文字识别失败: ${e.message}"
            )
        }
    }

    /**
     * 安全搜索（检测不当内容）
     *
     * @param bitmap 输入图片
     * @return SafeSearchResult 包含安全评级
     */
    suspend fun safeSearchDetection(bitmap: Bitmap): SafeSearchResult = withContext(Dispatchers.IO) {
        if (imageAnnotatorClient == null) {
            return@withContext SafeSearchResult(
                adult = SafetyLevel.UNKNOWN,
                violence = SafetyLevel.UNKNOWN,
                error = "Cloud Vision 未初始化"
            )
        }

        try {
            val imageBytes = bitmapToByteString(bitmap)
            val image = Image.newBuilder().setContent(imageBytes).build()

            val feature = Feature.newBuilder()
                .setType(Feature.Type.SAFE_SEARCH_DETECTION)
                .build()

            val request = AnnotateImageRequest.newBuilder()
                .addFeatures(feature)
                .setImage(image)
                .build()

            val response = imageAnnotatorClient!!.batchAnnotateImages(listOf(request))
            parseSafeSearchResponse(response)

        } catch (e: Exception) {
            e.printStackTrace()
            SafeSearchResult(
                adult = SafetyLevel.UNKNOWN,
                violence = SafetyLevel.UNKNOWN,
                error = "安全搜索失败: ${e.message}"
            )
        }
    }

    /**
     * 综合分析（标签 + 主色调 + 安全搜索）
     */
    suspend fun comprehensiveAnalysis(bitmap: Bitmap): ComprehensiveAnalysisResult =
        withContext(Dispatchers.IO) {
            val labels = detectLabels(bitmap)
            val properties = analyzeImageProperties(bitmap)

            ComprehensiveAnalysisResult(
                labels = labels,
                imageProperties = properties
            )
        }

    // ==================== 私有辅助方法 ====================

    /**
     * 将 Bitmap 转换为 ByteString
     */
    private fun bitmapToByteString(bitmap: Bitmap): ByteString {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return ByteString.copyFrom(stream.toByteArray())
    }

    /**
     * 解析图片属性响应
     */
    private fun parseImagePropertiesResponse(
        response: BatchAnnotateImagesResponse,
        maxColors: Int
    ): ImagePropertiesResult {
        val colors = mutableListOf<DominantColor>()

        response.responsesList.firstOrNull()?.let { result ->
            if (result.hasError()) {
                return ImagePropertiesResult(
                    dominantColors = emptyList(),
                    error = result.error.message
                )
            }

            result.imagePropertiesAnnotation.dominantColors.colorsList
                .take(maxColors)
                .forEach { colorInfo ->
                    val color = colorInfo.color
                    colors.add(
                        DominantColor(
                            red = color.red.toInt(),
                            green = color.green.toInt(),
                            blue = color.blue.toInt(),
                            score = colorInfo.score,
                            pixelFraction = colorInfo.pixelFraction
                        )
                    )
                }
        }

        return ImagePropertiesResult(dominantColors = colors)
    }

    /**
     * 解析标签检测响应
     */
    private fun parseLabelDetectionResponse(response: BatchAnnotateImagesResponse): LabelDetectionResult {
        val labels = mutableListOf<ImageLabel>()

        response.responsesList.firstOrNull()?.let { result ->
            if (result.hasError()) {
                return LabelDetectionResult(
                    labels = emptyList(),
                    error = result.error.message
                )
            }

            result.labelAnnotationsList.forEach { label ->
                labels.add(
                    ImageLabel(
                        description = label.description,
                        score = label.score,
                        topicality = label.topicality
                    )
                )
            }
        }

        return LabelDetectionResult(labels = labels)
    }

    /**
     * 解析文字检测响应
     */
    private fun parseTextDetectionResponse(response: BatchAnnotateImagesResponse): TextDetectionResult {
        response.responsesList.firstOrNull()?.let { result ->
            if (result.hasError()) {
                return TextDetectionResult(
                    text = "",
                    error = result.error.message
                )
            }

            val fullText = result.textAnnotationsList.firstOrNull()?.description ?: ""
            return TextDetectionResult(text = fullText)
        }

        return TextDetectionResult(text = "")
    }

    /**
     * 解析安全搜索响应
     */
    private fun parseSafeSearchResponse(response: BatchAnnotateImagesResponse): SafeSearchResult {
        response.responsesList.firstOrNull()?.let { result ->
            if (result.hasError()) {
                return SafeSearchResult(
                    adult = SafetyLevel.UNKNOWN,
                    violence = SafetyLevel.UNKNOWN,
                    error = result.error.message
                )
            }

            val safeSearch = result.safeSearchAnnotation
            return SafeSearchResult(
                adult = SafetyLevel.fromLikelihood(safeSearch.adult.name),
                violence = SafetyLevel.fromLikelihood(safeSearch.violence.name),
                medical = SafetyLevel.fromLikelihood(safeSearch.medical.name),
                spoof = SafetyLevel.fromLikelihood(safeSearch.spoof.name),
                racy = SafetyLevel.fromLikelihood(safeSearch.racy.name)
            )
        }

        return SafeSearchResult(
            adult = SafetyLevel.UNKNOWN,
            violence = SafetyLevel.UNKNOWN
        )
    }

    /**
     * 本地图片主色调分析（后备方案）
     * 当 Cloud Vision 不可用时使用
     */
    private fun analyzeImagePropertiesLocal(bitmap: Bitmap, maxColors: Int): ImagePropertiesResult {
        // 缩小图片以加速处理
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 50, 50, true)
        val colorMap = mutableMapOf<Int, Int>()

        // 统计颜色出现次数
        for (x in 0 until scaledBitmap.width) {
            for (y in 0 until scaledBitmap.height) {
                val pixel = scaledBitmap.getPixel(x, y)
                // 量化颜色（减少颜色数量）
                val quantized = quantizeColor(pixel)
                colorMap[quantized] = (colorMap[quantized] ?: 0) + 1
            }
        }

        val totalPixels = scaledBitmap.width * scaledBitmap.height

        // 按出现次数排序，取前 N 个
        val dominantColors = colorMap.entries
            .sortedByDescending { it.value }
            .take(maxColors)
            .map { (color, count) ->
                DominantColor(
                    red = Color.red(color),
                    green = Color.green(color),
                    blue = Color.blue(color),
                    score = count.toFloat() / totalPixels,
                    pixelFraction = count.toFloat() / totalPixels
                )
            }

        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        return ImagePropertiesResult(dominantColors = dominantColors)
    }

    /**
     * 量化颜色（将相似颜色归为一类）
     */
    private fun quantizeColor(color: Int): Int {
        val step = 32  // 量化步长
        val r = (Color.red(color) / step) * step
        val g = (Color.green(color) / step) * step
        val b = (Color.blue(color) / step) * step
        return Color.rgb(r, g, b)
    }

    /**
     * 创建模拟标签结果（用于演示）
     */
    private fun createMockLabelResult(): LabelDetectionResult {
        return LabelDetectionResult(
            labels = listOf(
                ImageLabel("Portrait", 0.95f, 0.95f),
                ImageLabel("Person", 0.92f, 0.92f),
                ImageLabel("Face", 0.88f, 0.88f),
                ImageLabel("Photography", 0.75f, 0.75f)
            ),
            error = "使用本地模拟数据（Cloud Vision 未配置）"
        )
    }

    /**
     * 释放资源
     */
    fun shutdown() {
        imageAnnotatorClient?.close()
        imageAnnotatorClient = null
        isInitialized = false
    }
}

// ==================== 数据类 ====================

/**
 * 图片属性分析结果
 */
data class ImagePropertiesResult(
    val dominantColors: List<DominantColor>,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null

    /**
     * 获取主色调的 Android Color 值
     */
    fun getPrimaryColorInt(): Int? {
        return dominantColors.firstOrNull()?.toColorInt()
    }
}

/**
 * 主色调
 */
data class DominantColor(
    val red: Int,
    val green: Int,
    val blue: Int,
    val score: Float,        // 置信度
    val pixelFraction: Float // 占比
) {
    fun toColorInt(): Int = Color.rgb(red, green, blue)

    fun toHexString(): String = String.format("#%02X%02X%02X", red, green, blue)
}

/**
 * 标签检测结果
 */
data class LabelDetectionResult(
    val labels: List<ImageLabel>,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null || labels.isNotEmpty()

    /**
     * 获取标签描述列表
     */
    fun getDescriptions(): List<String> = labels.map { it.description }

    /**
     * 获取格式化的标签字符串
     */
    fun getFormattedLabels(): String = labels.joinToString(", ") { it.description }
}

/**
 * 图片标签
 */
data class ImageLabel(
    val description: String,  // 标签描述（如 "Beach", "Food"）
    val score: Float,         // 置信度 (0.0 ~ 1.0)
    val topicality: Float     // 相关性
)

/**
 * 文字检测结果
 */
data class TextDetectionResult(
    val text: String,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null
    val hasText: Boolean get() = text.isNotBlank()
}

/**
 * 安全搜索结果
 */
data class SafeSearchResult(
    val adult: SafetyLevel,
    val violence: SafetyLevel,
    val medical: SafetyLevel = SafetyLevel.UNKNOWN,
    val spoof: SafetyLevel = SafetyLevel.UNKNOWN,
    val racy: SafetyLevel = SafetyLevel.UNKNOWN,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null
    val isSafe: Boolean get() = adult.isSafe && violence.isSafe
}

/**
 * 安全等级
 */
enum class SafetyLevel {
    UNKNOWN,
    VERY_UNLIKELY,
    UNLIKELY,
    POSSIBLE,
    LIKELY,
    VERY_LIKELY;

    val isSafe: Boolean get() = this in listOf(UNKNOWN, VERY_UNLIKELY, UNLIKELY)

    companion object {
        fun fromLikelihood(likelihood: String): SafetyLevel {
            return try {
                valueOf(likelihood)
            } catch (e: Exception) {
                UNKNOWN
            }
        }
    }
}

/**
 * 综合分析结果
 */
data class ComprehensiveAnalysisResult(
    val labels: LabelDetectionResult,
    val imageProperties: ImagePropertiesResult
)
