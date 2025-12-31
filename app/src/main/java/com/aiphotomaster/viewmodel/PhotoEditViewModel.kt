package com.aiphotomaster.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.aiphotomaster.face.FaceBeautyProcessor
import com.aiphotomaster.segmentation.PortraitEffectProcessor
import com.aiphotomaster.segmentation.SegmentationHelper
import com.aiphotomaster.utils.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图片编辑 ViewModel
 *
 * 管理图片编辑状态和处理逻辑，展示如何在协程中使用各种 Helper
 *
 * 使用示例：
 * ```kotlin
 * class EditActivity : AppCompatActivity() {
 *     private val viewModel: PhotoEditViewModel by viewModels()
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *
 *         // 观察处理状态
 *         viewModel.processingState.observe(this) { state ->
 *             when (state) {
 *                 is ProcessingState.Loading -> showLoading()
 *                 is ProcessingState.Success -> showImage(state.bitmap)
 *                 is ProcessingState.Error -> showError(state.message)
 *                 is ProcessingState.Idle -> hideLoading()
 *             }
 *         }
 *
 *         // 加载图片
 *         viewModel.loadImage(imageUri)
 *
 *         // 应用效果
 *         btnBlur.setOnClickListener {
 *             viewModel.applyBackgroundBlur(blurRadius = 20f)
 *         }
 *     }
 * }
 * ```
 */
class PhotoEditViewModel(application: Application) : AndroidViewModel(application) {

    // ==================== 处理器实例 ====================
    private var portraitProcessor: PortraitEffectProcessor? = null
    private var faceBeautyProcessor: FaceBeautyProcessor? = null
    private var segmentationHelper: SegmentationHelper? = null

    // ==================== LiveData ====================

    /**
     * 处理状态
     */
    private val _processingState = MutableLiveData<ProcessingState>(ProcessingState.Idle)
    val processingState: LiveData<ProcessingState> = _processingState

    /**
     * 原始图片
     */
    private val _originalBitmap = MutableLiveData<Bitmap?>()
    val originalBitmap: LiveData<Bitmap?> = _originalBitmap

    /**
     * 处理后的图片
     */
    private val _processedBitmap = MutableLiveData<Bitmap?>()
    val processedBitmap: LiveData<Bitmap?> = _processedBitmap

    /**
     * 当前效果类型
     */
    private val _currentEffect = MutableLiveData<EffectType>(EffectType.NONE)
    val currentEffect: LiveData<EffectType> = _currentEffect

    /**
     * 美颜参数
     */
    private val _beautyParams = MutableLiveData(BeautyParams())
    val beautyParams: LiveData<BeautyParams> = _beautyParams

    // 当前处理任务
    private var currentJob: Job? = null

    // ==================== 图片加载 ====================

    /**
     * 从 Uri 加载图片
     */
    fun loadImage(uri: Uri) {
        viewModelScope.launch {
            _processingState.value = ProcessingState.Loading("正在加载图片...")

            try {
                val bitmap = BitmapUtils.fromUri(
                    context = getApplication(),
                    uri = uri,
                    maxSize = 2048
                )

                if (bitmap != null) {
                    _originalBitmap.value = bitmap
                    _processedBitmap.value = bitmap
                    _currentEffect.value = EffectType.NONE
                    _processingState.value = ProcessingState.Success(bitmap)
                } else {
                    _processingState.value = ProcessingState.Error("无法加载图片")
                }
            } catch (e: Exception) {
                _processingState.value = ProcessingState.Error("加载失败: ${e.message}")
            }
        }
    }

    /**
     * 直接设置 Bitmap
     */
    fun setBitmap(bitmap: Bitmap) {
        _originalBitmap.value = bitmap
        _processedBitmap.value = bitmap
        _currentEffect.value = EffectType.NONE
        _processingState.value = ProcessingState.Success(bitmap)
    }

    // ==================== 人像分割效果 ====================

    /**
     * 应用背景虚化效果
     */
    fun applyBackgroundBlur(blurRadius: Float = 25f, useSoftEdge: Boolean = true) {
        processWithEffect(EffectType.BACKGROUND_BLUR) { original ->
            getPortraitProcessor().applyBackgroundBlur(original, blurRadius, useSoftEdge)
        }
    }

    /**
     * 应用渐进式背景虚化
     */
    fun applyGradientBlur(maxBlurRadius: Float = 25f) {
        processWithEffect(EffectType.GRADIENT_BLUR) { original ->
            getPortraitProcessor().applyGradientBackgroundBlur(original, maxBlurRadius)
        }
    }

    /**
     * 应用人像留色效果
     */
    fun applyColorPop(useSoftEdge: Boolean = true) {
        processWithEffect(EffectType.COLOR_POP) { original ->
            getPortraitProcessor().applyColorPop(original, useSoftEdge)
        }
    }

    /**
     * 应用反向人像留色效果
     */
    fun applyInverseColorPop() {
        processWithEffect(EffectType.INVERSE_COLOR_POP) { original ->
            getPortraitProcessor().applyInverseColorPop(original)
        }
    }

    /**
     * 替换背景
     */
    fun replaceBackground(newBackground: Bitmap) {
        processWithEffect(EffectType.BACKGROUND_REPLACE) { original ->
            getPortraitProcessor().replaceBackground(original, newBackground)
        }
    }

    /**
     * 替换背景为纯色
     */
    fun replaceBackgroundWithColor(color: Int = Color.WHITE) {
        processWithEffect(EffectType.BACKGROUND_COLOR) { original ->
            getPortraitProcessor().replaceBackgroundWithColor(original, color)
        }
    }

    /**
     * 添加边缘光效
     */
    fun applyEdgeGlow(glowColor: Int = Color.WHITE, glowRadius: Float = 10f) {
        processWithEffect(EffectType.EDGE_GLOW) { original ->
            getPortraitProcessor().applyEdgeGlow(original, glowColor, glowRadius)
        }
    }

    // ==================== 人脸美颜效果 ====================

    /**
     * 应用面部美颜（瘦脸 + 大眼）
     */
    fun applyFaceBeauty(slimIntensity: Float = 0.5f, eyeEnlargeIntensity: Float = 0.3f) {
        processWithEffect(EffectType.FACE_BEAUTY) { original ->
            val processor = getFaceBeautyProcessor()
            processor.setSlimIntensity(slimIntensity)
            processor.setEyeEnlargeIntensity(eyeEnlargeIntensity)
            processor.process(original)
        }
    }

    /**
     * 更新美颜参数并重新处理
     */
    fun updateBeautyParams(params: BeautyParams) {
        _beautyParams.value = params

        if (_currentEffect.value == EffectType.FACE_BEAUTY) {
            applyFaceBeauty(params.slimIntensity, params.eyeEnlargeIntensity)
        }
    }

    // ==================== 组合效果 ====================

    /**
     * 应用组合效果（美颜 + 背景虚化）
     */
    fun applyFullBeauty(
        slimIntensity: Float = 0.5f,
        eyeEnlargeIntensity: Float = 0.3f,
        blurRadius: Float = 20f
    ) {
        processWithEffect(EffectType.FULL_BEAUTY) { original ->
            // 先应用面部美颜
            val faceProcessor = getFaceBeautyProcessor()
            faceProcessor.setSlimIntensity(slimIntensity)
            faceProcessor.setEyeEnlargeIntensity(eyeEnlargeIntensity)
            val beautified = faceProcessor.process(original)

            // 再应用背景虚化
            val result = getPortraitProcessor().applyBackgroundBlur(beautified, blurRadius)

            // 清理中间结果
            if (beautified != original) beautified.recycle()

            result
        }
    }

    // ==================== 通用处理方法 ====================

    /**
     * 通用效果处理方法
     */
    private fun processWithEffect(
        effectType: EffectType,
        process: suspend (Bitmap) -> Bitmap
    ) {
        val original = _originalBitmap.value ?: return

        // 取消之前的处理任务
        currentJob?.cancel()

        currentJob = viewModelScope.launch {
            _processingState.value = ProcessingState.Loading("正在处理...")

            try {
                val result = withContext(Dispatchers.Default) {
                    process(original)
                }

                _processedBitmap.value = result
                _currentEffect.value = effectType
                _processingState.value = ProcessingState.Success(result)

            } catch (e: Exception) {
                _processingState.value = ProcessingState.Error("处理失败: ${e.message}")
            }
        }
    }

    /**
     * 重置为原图
     */
    fun resetToOriginal() {
        val original = _originalBitmap.value ?: return

        // 回收之前处理的图片
        val processed = _processedBitmap.value
        if (processed != null && processed != original) {
            processed.recycle()
        }

        _processedBitmap.value = original
        _currentEffect.value = EffectType.NONE
        _processingState.value = ProcessingState.Idle

        // 清除处理器缓存
        portraitProcessor?.clearCache()
    }

    // ==================== 处理器管理 ====================

    private suspend fun getPortraitProcessor(): PortraitEffectProcessor {
        if (portraitProcessor == null) {
            portraitProcessor = PortraitEffectProcessor.create(getApplication())
        }
        return portraitProcessor!!
    }

    private suspend fun getFaceBeautyProcessor(): FaceBeautyProcessor {
        if (faceBeautyProcessor == null) {
            faceBeautyProcessor = FaceBeautyProcessor.create(getApplication())
        }
        return faceBeautyProcessor!!
    }

    private fun getSegmentationHelper(): SegmentationHelper {
        if (segmentationHelper == null) {
            segmentationHelper = SegmentationHelper.createForSingleImage()
        }
        return segmentationHelper!!
    }

    // ==================== 生命周期 ====================

    override fun onCleared() {
        super.onCleared()

        // 释放资源
        portraitProcessor?.release()
        faceBeautyProcessor?.release()
        segmentationHelper?.close()

        portraitProcessor = null
        faceBeautyProcessor = null
        segmentationHelper = null

        // 回收图片
        _processedBitmap.value?.let { bitmap ->
            if (bitmap != _originalBitmap.value) {
                bitmap.recycle()
            }
        }
    }
}

// ==================== 状态类 ====================

/**
 * 处理状态密封类
 */
sealed class ProcessingState {
    object Idle : ProcessingState()
    data class Loading(val message: String) : ProcessingState()
    data class Success(val bitmap: Bitmap) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}

/**
 * 效果类型枚举
 */
enum class EffectType {
    NONE,                // 无效果
    BACKGROUND_BLUR,     // 背景虚化
    GRADIENT_BLUR,       // 渐进式虚化
    COLOR_POP,           // 人像留色
    INVERSE_COLOR_POP,   // 反向人像留色
    BACKGROUND_REPLACE,  // 背景替换
    BACKGROUND_COLOR,    // 纯色背景
    EDGE_GLOW,           // 边缘光效
    FACE_BEAUTY,         // 面部美颜
    FULL_BEAUTY          // 完整美颜
}

/**
 * 美颜参数数据类
 */
data class BeautyParams(
    val slimIntensity: Float = 0f,        // 瘦脸强度 (0.0 ~ 1.0)
    val eyeEnlargeIntensity: Float = 0f,  // 大眼强度 (0.0 ~ 1.0)
    val blurRadius: Float = 20f           // 背景模糊半径
)
