package com.aiphotomaster.filter

import android.opengl.GLES20
import com.aiphotomaster.face.FaceLandmarkIndices
import com.aiphotomaster.face.NormalizedLandmark
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import java.nio.FloatBuffer

/**
 * GPUImage 人脸变形滤镜
 *
 * 实现基于局部液化算法的人脸变形效果：
 * - 瘦脸：将脸颊区域向面部中心移动
 * - 大眼：将眼睛周围纹理向外扩张
 *
 * 使用 Fragment Shader 实现像素级变形
 */
class GPUImageFaceWarpFilter : GPUImageFilter(VERTEX_SHADER, FRAGMENT_SHADER) {

    companion object {
        // 变形控制点数量
        private const val MAX_WARP_POINTS = 8

        // ==================== 顶点着色器 ====================
        private const val VERTEX_SHADER = """
            attribute vec4 position;
            attribute vec4 inputTextureCoordinate;
            varying vec2 textureCoordinate;

            void main() {
                gl_Position = position;
                textureCoordinate = inputTextureCoordinate.xy;
            }
        """

        // ==================== 片元着色器（核心液化算法）====================
        private const val FRAGMENT_SHADER = """
            precision highp float;

            varying vec2 textureCoordinate;
            uniform sampler2D inputImageTexture;

            // 图像宽高比
            uniform float aspectRatio;

            // 瘦脸参数
            uniform float slimIntensity;      // 瘦脸强度 (0.0 ~ 1.0)
            uniform vec2 leftCheekCenter;     // 左脸颊中心
            uniform vec2 rightCheekCenter;    // 右脸颊中心
            uniform vec2 faceCenter;          // 面部中心
            uniform float cheekRadius;        // 脸颊影响半径

            // 大眼参数
            uniform float eyeEnlargeIntensity; // 大眼强度 (0.0 ~ 1.0)
            uniform vec2 leftEyeCenter;        // 左眼中心
            uniform vec2 rightEyeCenter;       // 右眼中心
            uniform float eyeRadius;           // 眼睛影响半径

            // 是否检测到人脸
            uniform float hasFace;

            /**
             * 局部液化变形函数
             *
             * @param coord 当前纹理坐标
             * @param center 变形中心点
             * @param direction 变形方向（归一化向量）
             * @param radius 影响半径
             * @param intensity 变形强度
             * @return 变形后的纹理坐标
             */
            vec2 liquify(vec2 coord, vec2 center, vec2 direction, float radius, float intensity) {
                // 计算当前点到中心的距离（考虑宽高比）
                vec2 delta = coord - center;
                delta.x *= aspectRatio;
                float dist = length(delta);

                // 在影响半径外，不进行变形
                if (dist > radius || dist < 0.001) {
                    return coord;
                }

                // 使用平滑的衰减函数（余弦平滑）
                // 距离越近，变形越强；距离越远，逐渐衰减到0
                float ratio = dist / radius;
                float falloff = 1.0 - ratio * ratio;
                falloff = falloff * falloff; // 四次方衰减，更平滑

                // 计算位移量
                float displacement = intensity * radius * falloff * 0.5;

                // 应用变形（将纹理坐标向相反方向偏移，实现"推动"效果）
                vec2 offset = direction * displacement;
                offset.x /= aspectRatio;

                return coord - offset;
            }

            /**
             * 放大变形函数（用于大眼效果）
             *
             * @param coord 当前纹理坐标
             * @param center 变形中心点
             * @param radius 影响半径
             * @param intensity 放大强度
             * @return 变形后的纹理坐标
             */
            vec2 enlarge(vec2 coord, vec2 center, float radius, float intensity) {
                // 计算当前点到中心的距离
                vec2 delta = coord - center;
                delta.x *= aspectRatio;
                float dist = length(delta);

                // 在影响半径外，不进行变形
                if (dist > radius || dist < 0.001) {
                    return coord;
                }

                // 计算放大效果
                // 使用 (1 - (dist/radius)^2) 作为权重
                float ratio = dist / radius;
                float weight = 1.0 - ratio * ratio;

                // 放大因子：将点向中心拉近（纹理坐标向中心偏移 = 显示放大）
                float scale = 1.0 - intensity * weight * 0.35;

                // 应用缩放（将纹理坐标向中心拉近）
                vec2 result = center + (coord - center) * scale;

                return result;
            }

            void main() {
                vec2 coord = textureCoordinate;

                // 如果没有检测到人脸，直接输出原始纹理
                if (hasFace < 0.5) {
                    gl_FragColor = texture2D(inputImageTexture, coord);
                    return;
                }

                // ========== 瘦脸变形 ==========
                if (slimIntensity > 0.01) {
                    // 左脸颊瘦脸：将脸颊向面部中心推动
                    vec2 leftDir = normalize(faceCenter - leftCheekCenter);
                    coord = liquify(coord, leftCheekCenter, leftDir, cheekRadius, slimIntensity);

                    // 右脸颊瘦脸
                    vec2 rightDir = normalize(faceCenter - rightCheekCenter);
                    coord = liquify(coord, rightCheekCenter, rightDir, cheekRadius, slimIntensity);
                }

                // ========== 大眼变形 ==========
                if (eyeEnlargeIntensity > 0.01) {
                    // 左眼放大
                    coord = enlarge(coord, leftEyeCenter, eyeRadius, eyeEnlargeIntensity);

                    // 右眼放大
                    coord = enlarge(coord, rightEyeCenter, eyeRadius, eyeEnlargeIntensity);
                }

                // 边界检查
                coord = clamp(coord, vec2(0.0), vec2(1.0));

                // 采样并输出
                gl_FragColor = texture2D(inputImageTexture, coord);
            }
        """
    }

    // ==================== Uniform 变量句柄 ====================
    private var aspectRatioLocation: Int = 0
    private var slimIntensityLocation: Int = 0
    private var leftCheekCenterLocation: Int = 0
    private var rightCheekCenterLocation: Int = 0
    private var faceCenterLocation: Int = 0
    private var cheekRadiusLocation: Int = 0
    private var eyeEnlargeIntensityLocation: Int = 0
    private var leftEyeCenterLocation: Int = 0
    private var rightEyeCenterLocation: Int = 0
    private var eyeRadiusLocation: Int = 0
    private var hasFaceLocation: Int = 0

    // ==================== 参数 ====================
    private var aspectRatio: Float = 1.0f
    private var slimIntensity: Float = 0.0f
    private var eyeEnlargeIntensity: Float = 0.0f

    // 关键点坐标（归一化）
    private var leftCheekCenter = floatArrayOf(0.3f, 0.6f)
    private var rightCheekCenter = floatArrayOf(0.7f, 0.6f)
    private var faceCenter = floatArrayOf(0.5f, 0.5f)
    private var leftEyeCenter = floatArrayOf(0.35f, 0.35f)
    private var rightEyeCenter = floatArrayOf(0.65f, 0.35f)

    // 影响半径
    private var cheekRadius: Float = 0.15f
    private var eyeRadius: Float = 0.08f

    // 是否有有效的人脸数据
    private var hasFace: Boolean = false

    override fun onInit() {
        super.onInit()

        // 获取 uniform 变量位置
        aspectRatioLocation = GLES20.glGetUniformLocation(program, "aspectRatio")
        slimIntensityLocation = GLES20.glGetUniformLocation(program, "slimIntensity")
        leftCheekCenterLocation = GLES20.glGetUniformLocation(program, "leftCheekCenter")
        rightCheekCenterLocation = GLES20.glGetUniformLocation(program, "rightCheekCenter")
        faceCenterLocation = GLES20.glGetUniformLocation(program, "faceCenter")
        cheekRadiusLocation = GLES20.glGetUniformLocation(program, "cheekRadius")
        eyeEnlargeIntensityLocation = GLES20.glGetUniformLocation(program, "eyeEnlargeIntensity")
        leftEyeCenterLocation = GLES20.glGetUniformLocation(program, "leftEyeCenter")
        rightEyeCenterLocation = GLES20.glGetUniformLocation(program, "rightEyeCenter")
        eyeRadiusLocation = GLES20.glGetUniformLocation(program, "eyeRadius")
        hasFaceLocation = GLES20.glGetUniformLocation(program, "hasFace")
    }

    override fun onInitialized() {
        super.onInitialized()
        updateUniforms()
    }

    override fun onOutputSizeChanged(width: Int, height: Int) {
        super.onOutputSizeChanged(width, height)
        aspectRatio = width.toFloat() / height.toFloat()
    }

    override fun onDrawArraysPre() {
        super.onDrawArraysPre()
        updateUniforms()
    }

    private fun updateUniforms() {
        // 设置宽高比
        setFloat(aspectRatioLocation, aspectRatio)

        // 设置瘦脸参数
        setFloat(slimIntensityLocation, slimIntensity)
        setFloatVec2(leftCheekCenterLocation, leftCheekCenter)
        setFloatVec2(rightCheekCenterLocation, rightCheekCenter)
        setFloatVec2(faceCenterLocation, faceCenter)
        setFloat(cheekRadiusLocation, cheekRadius)

        // 设置大眼参数
        setFloat(eyeEnlargeIntensityLocation, eyeEnlargeIntensity)
        setFloatVec2(leftEyeCenterLocation, leftEyeCenter)
        setFloatVec2(rightEyeCenterLocation, rightEyeCenter)
        setFloat(eyeRadiusLocation, eyeRadius)

        // 设置人脸检测标志
        setFloat(hasFaceLocation, if (hasFace) 1.0f else 0.0f)
    }

    // ==================== 公共 API ====================

    /**
     * 更新面部关键点数据
     *
     * @param landmarks MediaPipe 检测到的 478 个关键点
     */
    fun updateLandmarks(landmarks: List<NormalizedLandmark>?) {
        if (landmarks == null || landmarks.size < 468) {
            hasFace = false
            return
        }

        hasFace = true

        // 提取关键位置
        // 左脸颊中心
        val leftCheek = landmarks.getOrNull(FaceLandmarkIndices.LEFT_CHEEK_CENTER)
        if (leftCheek != null) {
            leftCheekCenter[0] = leftCheek.x
            leftCheekCenter[1] = leftCheek.y
        }

        // 右脸颊中心
        val rightCheek = landmarks.getOrNull(FaceLandmarkIndices.RIGHT_CHEEK_CENTER)
        if (rightCheek != null) {
            rightCheekCenter[0] = rightCheek.x
            rightCheekCenter[1] = rightCheek.y
        }

        // 面部中心（使用鼻根）
        val center = landmarks.getOrNull(FaceLandmarkIndices.FACE_CENTER)
        if (center != null) {
            faceCenter[0] = center.x
            faceCenter[1] = center.y
        }

        // 左眼中心（使用虹膜中心或眼睛轮廓中心）
        val leftEye = if (landmarks.size > 468) {
            landmarks.getOrNull(468)  // 左虹膜中心
        } else {
            // 计算左眼轮廓中心
            calculateCenter(landmarks, FaceLandmarkIndices.LEFT_EYE)
        }
        if (leftEye != null) {
            leftEyeCenter[0] = leftEye.x
            leftEyeCenter[1] = leftEye.y
        }

        // 右眼中心
        val rightEye = if (landmarks.size > 473) {
            landmarks.getOrNull(473)  // 右虹膜中心
        } else {
            calculateCenter(landmarks, FaceLandmarkIndices.RIGHT_EYE)
        }
        if (rightEye != null) {
            rightEyeCenter[0] = rightEye.x
            rightEyeCenter[1] = rightEye.y
        }

        // 根据人脸大小动态调整影响半径
        updateRadii(landmarks)
    }

    /**
     * 计算一组关键点的中心
     */
    private fun calculateCenter(
        landmarks: List<NormalizedLandmark>,
        indices: IntArray
    ): NormalizedLandmark? {
        val points = indices.mapNotNull { landmarks.getOrNull(it) }
        if (points.isEmpty()) return null

        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        points.forEach {
            sumX += it.x
            sumY += it.y
            sumZ += it.z
        }
        val count = points.size.toFloat()
        return NormalizedLandmark(sumX / count, sumY / count, sumZ / count)
    }

    /**
     * 根据人脸大小动态调整影响半径
     */
    private fun updateRadii(landmarks: List<NormalizedLandmark>) {
        // 使用左右脸颊的距离来估算脸部宽度
        val leftCheekPt = landmarks.getOrNull(FaceLandmarkIndices.LEFT_CHEEK_ANCHOR)
        val rightCheekPt = landmarks.getOrNull(FaceLandmarkIndices.RIGHT_CHEEK_ANCHOR)

        if (leftCheekPt != null && rightCheekPt != null) {
            val faceWidth = kotlin.math.abs(rightCheekPt.x - leftCheekPt.x)

            // 脸颊影响半径约为脸宽的 25-30%
            cheekRadius = faceWidth * 0.28f

            // 眼睛影响半径约为脸宽的 12-15%
            eyeRadius = faceWidth * 0.14f
        }
    }

    /**
     * 设置瘦脸强度
     *
     * @param intensity 强度值 (0.0 ~ 1.0)
     *                  0.0 = 无效果
     *                  0.5 = 中等瘦脸
     *                  1.0 = 最大瘦脸
     */
    fun setSlimIntensity(intensity: Float) {
        slimIntensity = intensity.coerceIn(0f, 1f)
    }

    /**
     * 获取当前瘦脸强度
     */
    fun getSlimIntensity(): Float = slimIntensity

    /**
     * 设置大眼强度
     *
     * @param intensity 强度值 (0.0 ~ 1.0)
     *                  0.0 = 无效果
     *                  0.5 = 中等放大
     *                  1.0 = 最大放大
     */
    fun setEyeEnlargeIntensity(intensity: Float) {
        eyeEnlargeIntensity = intensity.coerceIn(0f, 1f)
    }

    /**
     * 获取当前大眼强度
     */
    fun getEyeEnlargeIntensity(): Float = eyeEnlargeIntensity

    /**
     * 重置所有参数
     */
    fun reset() {
        slimIntensity = 0f
        eyeEnlargeIntensity = 0f
        hasFace = false
    }

    /**
     * 检查是否有有效的人脸数据
     */
    fun hasFaceData(): Boolean = hasFace
}
