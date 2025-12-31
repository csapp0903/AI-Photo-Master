package com.aiphotomaster.filter

import android.opengl.GLES20
import com.aiphotomaster.face.FaceLandmarkIndices
import com.aiphotomaster.face.NormalizedLandmark
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter

/**
 * 高级人脸变形滤镜
 *
 * 使用多点控制的液化算法，支持更精细的面部变形效果：
 * - 多区域瘦脸控制
 * - 精确的大眼效果
 * - 下巴调整
 * - 额头调整
 *
 * 此滤镜使用数组 uniform 传递多个控制点，
 * 在 Fragment Shader 中进行复杂的多点变形计算
 */
class GPUImageAdvancedFaceWarpFilter : GPUImageFilter(VERTEX_SHADER, FRAGMENT_SHADER) {

    companion object {
        // 最大变形控制点数量
        private const val MAX_WARP_POINTS = 16

        private const val VERTEX_SHADER = """
            attribute vec4 position;
            attribute vec4 inputTextureCoordinate;
            varying vec2 textureCoordinate;

            void main() {
                gl_Position = position;
                textureCoordinate = inputTextureCoordinate.xy;
            }
        """

        /**
         * 高级液化片元着色器
         *
         * 支持多个变形点的液化算法：
         * - warpCenters: 变形中心点数组
         * - warpDirections: 变形方向数组
         * - warpRadii: 变形半径数组
         * - warpIntensities: 变形强度数组
         */
        private const val FRAGMENT_SHADER = """
            precision highp float;

            varying vec2 textureCoordinate;
            uniform sampler2D inputImageTexture;

            // 图像参数
            uniform float aspectRatio;
            uniform float hasFace;

            // 变形控制参数 (最多 ${MAX_WARP_POINTS} 个点)
            uniform int warpPointCount;
            uniform vec2 warpCenters[${MAX_WARP_POINTS}];
            uniform vec2 warpDirections[${MAX_WARP_POINTS}];
            uniform float warpRadii[${MAX_WARP_POINTS}];
            uniform float warpIntensities[${MAX_WARP_POINTS}];
            uniform int warpTypes[${MAX_WARP_POINTS}];  // 0=推动, 1=放大, 2=缩小

            // 全局强度乘数
            uniform float globalSlimIntensity;
            uniform float globalEyeIntensity;
            uniform float globalChinIntensity;

            /**
             * 推动型液化变形
             * 将像素沿指定方向推动
             */
            vec2 pushWarp(vec2 coord, vec2 center, vec2 direction, float radius, float intensity) {
                vec2 delta = coord - center;
                delta.x *= aspectRatio;
                float dist = length(delta);

                if (dist > radius || dist < 0.001) {
                    return coord;
                }

                float ratio = dist / radius;
                // 使用平滑的 smoothstep 衰减
                float falloff = 1.0 - smoothstep(0.0, 1.0, ratio);
                falloff = falloff * falloff;

                float displacement = intensity * radius * falloff * 0.4;
                vec2 offset = direction * displacement;
                offset.x /= aspectRatio;

                return coord - offset;
            }

            /**
             * 放大型液化变形
             * 将区域向中心收缩（产生放大效果）
             */
            vec2 enlargeWarp(vec2 coord, vec2 center, float radius, float intensity) {
                vec2 delta = coord - center;
                delta.x *= aspectRatio;
                float dist = length(delta);

                if (dist > radius || dist < 0.001) {
                    return coord;
                }

                float ratio = dist / radius;
                float weight = 1.0 - ratio * ratio;
                weight = weight * weight;

                float scale = 1.0 - intensity * weight * 0.3;
                return center + (coord - center) * scale;
            }

            /**
             * 缩小型液化变形
             * 将区域向外推（产生缩小效果）
             */
            vec2 shrinkWarp(vec2 coord, vec2 center, float radius, float intensity) {
                vec2 delta = coord - center;
                delta.x *= aspectRatio;
                float dist = length(delta);

                if (dist > radius || dist < 0.001) {
                    return coord;
                }

                float ratio = dist / radius;
                float weight = 1.0 - ratio * ratio;

                float scale = 1.0 + intensity * weight * 0.2;
                return center + (coord - center) * scale;
            }

            /**
             * 球形膨胀变形
             * 产生类似鱼眼的局部放大效果
             */
            vec2 sphereWarp(vec2 coord, vec2 center, float radius, float intensity) {
                vec2 delta = coord - center;
                delta.x *= aspectRatio;
                float dist = length(delta);

                if (dist > radius) {
                    return coord;
                }

                // 球形投影公式
                float r = radius;
                float z = sqrt(r * r - dist * dist);
                float factor = (r + z * intensity * 0.5) / (r + z);

                vec2 result = center + (coord - center) * factor;
                return result;
            }

            void main() {
                vec2 coord = textureCoordinate;

                // 无人脸时直接输出
                if (hasFace < 0.5) {
                    gl_FragColor = texture2D(inputImageTexture, coord);
                    return;
                }

                // 依次应用所有变形点
                for (int i = 0; i < ${MAX_WARP_POINTS}; i++) {
                    if (i >= warpPointCount) break;

                    vec2 center = warpCenters[i];
                    vec2 direction = warpDirections[i];
                    float radius = warpRadii[i];
                    float intensity = warpIntensities[i];
                    int warpType = warpTypes[i];

                    // 根据变形类型应用不同算法
                    if (warpType == 0) {
                        // 推动型（瘦脸）
                        coord = pushWarp(coord, center, direction, radius, intensity * globalSlimIntensity);
                    } else if (warpType == 1) {
                        // 放大型（大眼）
                        coord = enlargeWarp(coord, center, radius, intensity * globalEyeIntensity);
                    } else if (warpType == 2) {
                        // 缩小型
                        coord = shrinkWarp(coord, center, radius, intensity * globalChinIntensity);
                    } else if (warpType == 3) {
                        // 球形膨胀
                        coord = sphereWarp(coord, center, radius, intensity * globalEyeIntensity);
                    }
                }

                // 边界检查
                coord = clamp(coord, vec2(0.001), vec2(0.999));

                gl_FragColor = texture2D(inputImageTexture, coord);
            }
        """
    }

    // Uniform 位置
    private var aspectRatioLoc = 0
    private var hasFaceLoc = 0
    private var warpPointCountLoc = 0
    private var warpCentersLoc = 0
    private var warpDirectionsLoc = 0
    private var warpRadiiLoc = 0
    private var warpIntensitiesLoc = 0
    private var warpTypesLoc = 0
    private var globalSlimIntensityLoc = 0
    private var globalEyeIntensityLoc = 0
    private var globalChinIntensityLoc = 0

    // 变形点数据
    private val warpPoints = mutableListOf<WarpPoint>()
    private var hasFace = false
    private var aspectRatio = 1.0f

    // 全局强度
    private var globalSlimIntensity = 1.0f
    private var globalEyeIntensity = 1.0f
    private var globalChinIntensity = 1.0f

    override fun onInit() {
        super.onInit()

        aspectRatioLoc = GLES20.glGetUniformLocation(program, "aspectRatio")
        hasFaceLoc = GLES20.glGetUniformLocation(program, "hasFace")
        warpPointCountLoc = GLES20.glGetUniformLocation(program, "warpPointCount")
        warpCentersLoc = GLES20.glGetUniformLocation(program, "warpCenters")
        warpDirectionsLoc = GLES20.glGetUniformLocation(program, "warpDirections")
        warpRadiiLoc = GLES20.glGetUniformLocation(program, "warpRadii")
        warpIntensitiesLoc = GLES20.glGetUniformLocation(program, "warpIntensities")
        warpTypesLoc = GLES20.glGetUniformLocation(program, "warpTypes")
        globalSlimIntensityLoc = GLES20.glGetUniformLocation(program, "globalSlimIntensity")
        globalEyeIntensityLoc = GLES20.glGetUniformLocation(program, "globalEyeIntensity")
        globalChinIntensityLoc = GLES20.glGetUniformLocation(program, "globalChinIntensity")
    }

    override fun onOutputSizeChanged(width: Int, height: Int) {
        super.onOutputSizeChanged(width, height)
        aspectRatio = width.toFloat() / height.toFloat()
    }

    override fun onDrawArraysPre() {
        super.onDrawArraysPre()

        GLES20.glUniform1f(aspectRatioLoc, aspectRatio)
        GLES20.glUniform1f(hasFaceLoc, if (hasFace) 1.0f else 0.0f)
        GLES20.glUniform1f(globalSlimIntensityLoc, globalSlimIntensity)
        GLES20.glUniform1f(globalEyeIntensityLoc, globalEyeIntensity)
        GLES20.glUniform1f(globalChinIntensityLoc, globalChinIntensity)

        val count = minOf(warpPoints.size, MAX_WARP_POINTS)
        GLES20.glUniform1i(warpPointCountLoc, count)

        if (count > 0) {
            val centers = FloatArray(MAX_WARP_POINTS * 2)
            val directions = FloatArray(MAX_WARP_POINTS * 2)
            val radii = FloatArray(MAX_WARP_POINTS)
            val intensities = FloatArray(MAX_WARP_POINTS)
            val types = IntArray(MAX_WARP_POINTS)

            for (i in 0 until count) {
                val point = warpPoints[i]
                centers[i * 2] = point.centerX
                centers[i * 2 + 1] = point.centerY
                directions[i * 2] = point.directionX
                directions[i * 2 + 1] = point.directionY
                radii[i] = point.radius
                intensities[i] = point.intensity
                types[i] = point.type.ordinal
            }

            GLES20.glUniform2fv(warpCentersLoc, MAX_WARP_POINTS, centers, 0)
            GLES20.glUniform2fv(warpDirectionsLoc, MAX_WARP_POINTS, directions, 0)
            GLES20.glUniform1fv(warpRadiiLoc, MAX_WARP_POINTS, radii, 0)
            GLES20.glUniform1fv(warpIntensitiesLoc, MAX_WARP_POINTS, intensities, 0)
            GLES20.glUniform1iv(warpTypesLoc, MAX_WARP_POINTS, types, 0)
        }
    }

    /**
     * 更新面部关键点，自动生成变形控制点
     */
    fun updateLandmarks(landmarks: List<NormalizedLandmark>?) {
        warpPoints.clear()

        if (landmarks == null || landmarks.size < 468) {
            hasFace = false
            return
        }

        hasFace = true

        // 提取关键位置
        val faceCenter = landmarks[FaceLandmarkIndices.FACE_CENTER]
        val leftCheek = landmarks[FaceLandmarkIndices.LEFT_CHEEK_CENTER]
        val rightCheek = landmarks[FaceLandmarkIndices.RIGHT_CHEEK_CENTER]
        val leftCheekAnchor = landmarks[FaceLandmarkIndices.LEFT_CHEEK_ANCHOR]
        val rightCheekAnchor = landmarks[FaceLandmarkIndices.RIGHT_CHEEK_ANCHOR]

        // 计算面部宽度用于动态半径
        val faceWidth = kotlin.math.abs(rightCheekAnchor.x - leftCheekAnchor.x)

        // ========== 添加瘦脸变形点 ==========
        // 左脸颊
        val leftDir = normalize(faceCenter.x - leftCheek.x, faceCenter.y - leftCheek.y)
        warpPoints.add(
            WarpPoint(
                centerX = leftCheek.x,
                centerY = leftCheek.y,
                directionX = leftDir.first,
                directionY = leftDir.second,
                radius = faceWidth * 0.25f,
                intensity = 1.0f,
                type = WarpType.PUSH
            )
        )

        // 右脸颊
        val rightDir = normalize(faceCenter.x - rightCheek.x, faceCenter.y - rightCheek.y)
        warpPoints.add(
            WarpPoint(
                centerX = rightCheek.x,
                centerY = rightCheek.y,
                directionX = rightDir.first,
                directionY = rightDir.second,
                radius = faceWidth * 0.25f,
                intensity = 1.0f,
                type = WarpType.PUSH
            )
        )

        // ========== 添加大眼变形点 ==========
        // 左眼
        val leftEye = if (landmarks.size > 468) landmarks[468] else {
            calculateCenter(landmarks, FaceLandmarkIndices.LEFT_EYE)
        }
        warpPoints.add(
            WarpPoint(
                centerX = leftEye.x,
                centerY = leftEye.y,
                directionX = 0f,
                directionY = 0f,
                radius = faceWidth * 0.12f,
                intensity = 1.0f,
                type = WarpType.ENLARGE
            )
        )

        // 右眼
        val rightEye = if (landmarks.size > 473) landmarks[473] else {
            calculateCenter(landmarks, FaceLandmarkIndices.RIGHT_EYE)
        }
        warpPoints.add(
            WarpPoint(
                centerX = rightEye.x,
                centerY = rightEye.y,
                directionX = 0f,
                directionY = 0f,
                radius = faceWidth * 0.12f,
                intensity = 1.0f,
                type = WarpType.ENLARGE
            )
        )

        // ========== 添加下巴变形点（可选）==========
        val chin = landmarks[FaceLandmarkIndices.CHIN_CENTER]
        warpPoints.add(
            WarpPoint(
                centerX = chin.x,
                centerY = chin.y,
                directionX = 0f,
                directionY = -1f,  // 向上推
                radius = faceWidth * 0.15f,
                intensity = 0.5f,
                type = WarpType.PUSH
            )
        )
    }

    private fun calculateCenter(landmarks: List<NormalizedLandmark>, indices: IntArray): NormalizedLandmark {
        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        var count = 0
        for (idx in indices) {
            landmarks.getOrNull(idx)?.let {
                sumX += it.x
                sumY += it.y
                sumZ += it.z
                count++
            }
        }
        return if (count > 0) {
            NormalizedLandmark(sumX / count, sumY / count, sumZ / count)
        } else {
            NormalizedLandmark(0.5f, 0.5f, 0f)
        }
    }

    private fun normalize(x: Float, y: Float): Pair<Float, Float> {
        val len = kotlin.math.sqrt(x * x + y * y)
        return if (len > 0.001f) Pair(x / len, y / len) else Pair(0f, 0f)
    }

    // ==================== 公共 API ====================

    fun setSlimIntensity(intensity: Float) {
        globalSlimIntensity = intensity.coerceIn(0f, 1.5f)
    }

    fun setEyeEnlargeIntensity(intensity: Float) {
        globalEyeIntensity = intensity.coerceIn(0f, 1.5f)
    }

    fun setChinIntensity(intensity: Float) {
        globalChinIntensity = intensity.coerceIn(0f, 1.5f)
    }

    fun reset() {
        warpPoints.clear()
        hasFace = false
        globalSlimIntensity = 1.0f
        globalEyeIntensity = 1.0f
        globalChinIntensity = 1.0f
    }
}

/**
 * 变形点数据
 */
data class WarpPoint(
    val centerX: Float,
    val centerY: Float,
    val directionX: Float,
    val directionY: Float,
    val radius: Float,
    val intensity: Float,
    val type: WarpType
)

/**
 * 变形类型
 */
enum class WarpType {
    PUSH,     // 推动（瘦脸）
    ENLARGE,  // 放大（大眼）
    SHRINK,   // 缩小
    SPHERE    // 球形膨胀
}
