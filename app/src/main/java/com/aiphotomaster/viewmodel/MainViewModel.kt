package com.aiphotomaster.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.aiphotomaster.cloud.CloudVisionManager
import com.aiphotomaster.cloud.ComprehensiveAnalysisResult
import com.aiphotomaster.cloud.DominantColor
import com.aiphotomaster.cloud.ImageLabel
import com.aiphotomaster.face.FaceBeautyProcessor
import com.aiphotomaster.face.FaceMeshHelper
import com.aiphotomaster.face.NormalizedLandmark
import com.aiphotomaster.filter.GPUImageFaceWarpFilter
import com.aiphotomaster.segmentation.PortraitEffectProcessor
import com.aiphotomaster.utils.BitmapUtils
import jp.co.cyberagent.android.gpuimage.GPUImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面 ViewModel
 *
 * 整合所有功能模块：
 * - 基础编辑（旋转、裁剪、滤镜）
 * - 瘦脸大眼（MediaPipe + GPUImage）
 * - 人像特效（ML Kit 分割）
 * - 智能分析（Cloud Vision）
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ==================== 处理器实例 ====================
    private var gpuImage: GPUImage? = null
    private var faceWarpFilter: GPUImageFaceWarpFilter? = null
    private var faceMeshHelper: FaceMeshHelper? = null
    private var faceBeautyProcessor: FaceBeautyProcessor? = null
    private var portraitProcessor: PortraitEffectProcessor? = null

    // ==================== LiveData ====================

    // 加载状态
    private val _loadingState = MutableLiveData<LoadingState>(LoadingState.Idle)
    val loadingState: LiveData<LoadingState> = _loadingState

    // 原始图片
    private val _originalBitmap = MutableLiveData<Bitmap?>()
    val originalBitmap: LiveData<Bitmap?> = _originalBitmap

    // 处理后的图片
    private val _processedBitmap = MutableLiveData<Bitmap?>()
    val processedBitmap: LiveData<Bitmap?> = _processedBitmap

    // 当前模式
    private val _currentMode = MutableLiveData(EditMode.BASIC)
    val currentMode: LiveData<EditMode> = _currentMode

    // 人脸检测状态
    private val _faceDetected = MutableLiveData(false)
    val faceDetected: LiveData<Boolean> = _faceDetected

    // 缓存的人脸关键点
    private val _faceLandmarks = MutableLiveData<List<NormalizedLandmark>?>()
    val faceLandmarks: LiveData<List<NormalizedLandmark>?> = _faceLandmarks

    // 美颜参数
    private val _faceBeautyParams = MutableLiveData(FaceBeautyParams())
    val faceBeautyParams: LiveData<FaceBeautyParams> = _faceBeautyParams

    // 人像特效参数
    private val _portraitEffectParams = MutableLiveData(PortraitEffectParams())
    val portraitEffectParams: LiveData<PortraitEffectParams> = _portraitEffectParams

    // 智能分析结果
    private val _analysisResult = MutableLiveData<AnalysisState>()
    val analysisResult: LiveData<AnalysisState> = _analysisResult

    // Toast 消息
    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    // 当前处理任务
    private var currentJob: Job? = null

    // ==================== 图片加载 ====================

    /**
     * 从 Uri 加载图片
     */
    fun loadImage(uri: Uri) {
        viewModelScope.launch {
            _loadingState.value = LoadingState.Loading("正在加载图片...")

            try {
                val bitmap = BitmapUtils.fromUri(
                    context = getApplication(),
                    uri = uri,
                    maxSize = 2048
                )

                if (bitmap != null) {
                    setOriginalBitmap(bitmap)
                    _loadingState.value = LoadingState.Success
                } else {
                    _loadingState.value = LoadingState.Error("无法加载图片")
                }
            } catch (e: Exception) {
                _loadingState.value = LoadingState.Error("加载失败: ${e.message}")
            }
        }
    }

    /**
     * 设置原始图片
     */
    fun setOriginalBitmap(bitmap: Bitmap) {
        _originalBitmap.value = bitmap
        _processedBitmap.value = bitmap
        _faceDetected.value = false
        _faceLandmarks.value = null

        // 初始化 GPUImage
        if (gpuImage == null) {
            gpuImage = GPUImage(getApplication())
        }
        gpuImage?.setImage(bitmap)

        // 自动检测人脸
        detectFace()
    }

    // ==================== 模式切换 ====================

    /**
     * 切换编辑模式
     */
    fun setEditMode(mode: EditMode) {
        _currentMode.value = mode

        // 切换模式时重置到原图
        resetToOriginal()
    }

    // ==================== 基础编辑 ====================

    /**
     * 旋转图片
     */
    fun rotateImage(degrees: Float) {
        val original = _originalBitmap.value ?: return

        viewModelScope.launch {
            _loadingState.value = LoadingState.Loading("旋转中...")

            val rotated = withContext(Dispatchers.Default) {
                BitmapUtils.rotate(original, degrees)
            }

            setOriginalBitmap(rotated)
            _loadingState.value = LoadingState.Success
        }
    }

    /**
     * 翻转图片
     */
    fun flipImage(horizontal: Boolean) {
        val original = _originalBitmap.value ?: return

        viewModelScope.launch {
            _loadingState.value = LoadingState.Loading("翻转中...")

            val flipped = withContext(Dispatchers.Default) {
                if (horizontal) {
                    BitmapUtils.flipHorizontal(original)
                } else {
                    BitmapUtils.flipVertical(original)
                }
            }

            setOriginalBitmap(flipped)
            _loadingState.value = LoadingState.Success
        }
    }

    // ==================== 瘦脸大眼 ====================

    /**
     * 检测人脸
     */
    fun detectFace() {
        val bitmap = _originalBitmap.value ?: return

        viewModelScope.launch {
            try {
                if (faceMeshHelper == null) {
                    faceMeshHelper = FaceMeshHelper.createForImage(getApplication())
                }

                val result = faceMeshHelper!!.detectImageAsync(bitmap)
                val landmarks = faceMeshHelper!!.getFirstFaceLandmarks(result)

                _faceDetected.value = landmarks != null
                _faceLandmarks.value = landmarks

                if (landmarks != null) {
                    showToast("检测到人脸")
                } else {
                    showToast("未检测到人脸")
                }

            } catch (e: Exception) {
                _faceDetected.value = false
                showToast("人脸检测失败: ${e.message}")
            }
        }
    }

    /**
     * 更新瘦脸强度
     */
    fun setSlimIntensity(intensity: Float) {
        val current = _faceBeautyParams.value ?: FaceBeautyParams()
        _faceBeautyParams.value = current.copy(slimIntensity = intensity.coerceIn(0f, 1f))
        applyFaceBeauty()
    }

    /**
     * 更新大眼强度
     */
    fun setEyeEnlargeIntensity(intensity: Float) {
        val current = _faceBeautyParams.value ?: FaceBeautyParams()
        _faceBeautyParams.value = current.copy(eyeEnlargeIntensity = intensity.coerceIn(0f, 1f))
        applyFaceBeauty()
    }

    /**
     * 应用美颜效果
     */
    private fun applyFaceBeauty() {
        val bitmap = _originalBitmap.value ?: return
        val landmarks = _faceLandmarks.value ?: return
        val params = _faceBeautyParams.value ?: return

        // 取消之前的任务
        currentJob?.cancel()

        currentJob = viewModelScope.launch {
            try {
                // 初始化滤镜
                if (faceWarpFilter == null) {
                    faceWarpFilter = GPUImageFaceWarpFilter()
                }

                val filter = faceWarpFilter!!
                filter.updateLandmarks(landmarks)
                filter.setSlimIntensity(params.slimIntensity)
                filter.setEyeEnlargeIntensity(params.eyeEnlargeIntensity)

                gpuImage?.setFilter(filter)
                gpuImage?.setImage(bitmap)

                val result = withContext(Dispatchers.Default) {
                    gpuImage?.bitmapWithFilterApplied
                }

                if (result != null) {
                    _processedBitmap.value = result
                }

            } catch (e: Exception) {
                showToast("美颜处理失败: ${e.message}")
            }
        }
    }

    // ==================== 人像特效 ====================

    /**
     * 应用背景虚化
     */
    fun applyBackgroundBlur(blurRadius: Float = 25f) {
        applyPortraitEffect { processor, bitmap ->
            processor.applyBackgroundBlur(bitmap, blurRadius)
        }
    }

    /**
     * 应用人像留色
     */
    fun applyColorPop() {
        applyPortraitEffect { processor, bitmap ->
            processor.applyColorPop(bitmap)
        }
    }

    /**
     * 应用反向人像留色
     */
    fun applyInverseColorPop() {
        applyPortraitEffect { processor, bitmap ->
            processor.applyInverseColorPop(bitmap)
        }
    }

    /**
     * 应用纯色背景
     */
    fun applyBackgroundColor(color: Int) {
        applyPortraitEffect { processor, bitmap ->
            processor.replaceBackgroundWithColor(bitmap, color)
        }
    }

    /**
     * 应用边缘光效
     */
    fun applyEdgeGlow(glowColor: Int) {
        applyPortraitEffect { processor, bitmap ->
            processor.applyEdgeGlow(bitmap, glowColor)
        }
    }

    /**
     * 通用人像特效处理
     */
    private fun applyPortraitEffect(
        effect: suspend (PortraitEffectProcessor, Bitmap) -> Bitmap
    ) {
        val bitmap = _originalBitmap.value ?: return

        currentJob?.cancel()

        currentJob = viewModelScope.launch {
            _loadingState.value = LoadingState.Loading("处理中...")

            try {
                if (portraitProcessor == null) {
                    portraitProcessor = PortraitEffectProcessor.create(getApplication())
                }

                val result = withContext(Dispatchers.Default) {
                    effect(portraitProcessor!!, bitmap)
                }

                _processedBitmap.value = result
                _loadingState.value = LoadingState.Success

            } catch (e: Exception) {
                _loadingState.value = LoadingState.Error("处理失败: ${e.message}")
            }
        }
    }

    // ==================== 智能分析 ====================

    /**
     * 执行智能分析
     */
    fun analyzeImage() {
        val bitmap = _originalBitmap.value ?: return

        viewModelScope.launch {
            _analysisResult.value = AnalysisState.Loading

            try {
                // 初始化 Cloud Vision（如果尚未初始化）
                CloudVisionManager.initialize(getApplication())

                val result = CloudVisionManager.comprehensiveAnalysis(bitmap)

                _analysisResult.value = AnalysisState.Success(result)

                // 显示标签 Toast
                val labelsText = result.labels.getFormattedLabels()
                if (labelsText.isNotEmpty()) {
                    showToast("标签: $labelsText")
                }

            } catch (e: Exception) {
                _analysisResult.value = AnalysisState.Error("分析失败: ${e.message}")
                showToast("分析失败: ${e.message}")
            }
        }
    }

    /**
     * 获取主色调
     */
    fun analyzeDominantColors() {
        val bitmap = _originalBitmap.value ?: return

        viewModelScope.launch {
            _loadingState.value = LoadingState.Loading("分析颜色...")

            try {
                CloudVisionManager.initialize(getApplication())
                val result = CloudVisionManager.analyzeImageProperties(bitmap)

                if (result.dominantColors.isNotEmpty()) {
                    val colorText = result.dominantColors.take(3)
                        .joinToString(", ") { it.toHexString() }
                    showToast("主色调: $colorText")
                }

                _loadingState.value = LoadingState.Success

            } catch (e: Exception) {
                _loadingState.value = LoadingState.Error("颜色分析失败")
            }
        }
    }

    // ==================== 重置与工具 ====================

    /**
     * 重置为原图
     */
    fun resetToOriginal() {
        val original = _originalBitmap.value ?: return

        _processedBitmap.value = original
        _faceBeautyParams.value = FaceBeautyParams()

        // 清除滤镜
        gpuImage?.setFilter(null)
        portraitProcessor?.clearCache()
    }

    /**
     * 获取当前处理结果
     */
    fun getCurrentBitmap(): Bitmap? {
        return _processedBitmap.value ?: _originalBitmap.value
    }

    /**
     * 显示 Toast 消息
     */
    private fun showToast(message: String) {
        _toastMessage.value = message
    }

    /**
     * 清除 Toast 消息
     */
    fun clearToast() {
        _toastMessage.value = null
    }

    // ==================== 生命周期 ====================

    override fun onCleared() {
        super.onCleared()

        faceMeshHelper?.close()
        faceBeautyProcessor?.release()
        portraitProcessor?.release()

        faceMeshHelper = null
        faceBeautyProcessor = null
        portraitProcessor = null
        gpuImage = null
        faceWarpFilter = null
    }
}

// ==================== 数据类与枚举 ====================

/**
 * 编辑模式
 */
enum class EditMode {
    BASIC,      // 基础编辑
    FACE,       // 瘦脸大眼
    PORTRAIT,   // 人像特效
    ANALYSIS    // 智能分析
}

/**
 * 加载状态
 */
sealed class LoadingState {
    object Idle : LoadingState()
    data class Loading(val message: String) : LoadingState()
    object Success : LoadingState()
    data class Error(val message: String) : LoadingState()
}

/**
 * 分析状态
 */
sealed class AnalysisState {
    object Loading : AnalysisState()
    data class Success(val result: ComprehensiveAnalysisResult) : AnalysisState()
    data class Error(val message: String) : AnalysisState()
}

/**
 * 美颜参数
 */
data class FaceBeautyParams(
    val slimIntensity: Float = 0f,
    val eyeEnlargeIntensity: Float = 0f
)

/**
 * 人像特效参数
 */
data class PortraitEffectParams(
    val blurRadius: Float = 25f,
    val effectType: PortraitEffectType = PortraitEffectType.NONE
)

/**
 * 人像特效类型
 */
enum class PortraitEffectType {
    NONE,
    BACKGROUND_BLUR,
    COLOR_POP,
    INVERSE_COLOR_POP,
    BACKGROUND_COLOR,
    EDGE_GLOW
}
