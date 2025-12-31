package com.aiphotomaster.ui

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.aiphotomaster.databinding.ActivityPhotoEditBinding
import com.aiphotomaster.viewmodel.BeautyParams
import com.aiphotomaster.viewmodel.EffectType
import com.aiphotomaster.viewmodel.PhotoEditViewModel
import com.aiphotomaster.viewmodel.ProcessingState

/**
 * 图片编辑界面示例
 *
 * 展示如何使用 PhotoEditViewModel 实现各种效果
 */
class PhotoEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoEditBinding
    private val viewModel: PhotoEditViewModel by viewModels()

    // 图片选择器
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupObservers()
        setupUI()
    }

    private fun setupObservers() {
        // 观察处理状态
        viewModel.processingState.observe(this) { state ->
            when (state) {
                is ProcessingState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.tvStatus.text = state.message
                }
                is ProcessingState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    binding.ivPreview.setImageBitmap(state.bitmap)
                    binding.tvStatus.text = "处理完成"
                }
                is ProcessingState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = state.message
                    Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                }
                is ProcessingState.Idle -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = ""
                }
            }
        }

        // 观察当前效果
        viewModel.currentEffect.observe(this) { effect ->
            updateEffectUI(effect)
        }

        // 观察美颜参数
        viewModel.beautyParams.observe(this) { params ->
            binding.seekSlim.progress = (params.slimIntensity * 100).toInt()
            binding.seekEye.progress = (params.eyeEnlargeIntensity * 100).toInt()
        }
    }

    private fun setupUI() {
        // 选择图片
        binding.btnPickImage.setOnClickListener {
            pickImage.launch("image/*")
        }

        // 重置
        binding.btnReset.setOnClickListener {
            viewModel.resetToOriginal()
        }

        // ========== 人像分割效果 ==========

        // 背景虚化
        binding.btnBlur.setOnClickListener {
            viewModel.applyBackgroundBlur(blurRadius = 25f)
        }

        // 渐进式虚化
        binding.btnGradientBlur.setOnClickListener {
            viewModel.applyGradientBlur(maxBlurRadius = 25f)
        }

        // 人像留色
        binding.btnColorPop.setOnClickListener {
            viewModel.applyColorPop()
        }

        // 反向人像留色
        binding.btnInverseColorPop.setOnClickListener {
            viewModel.applyInverseColorPop()
        }

        // 白色背景
        binding.btnWhiteBg.setOnClickListener {
            viewModel.replaceBackgroundWithColor(Color.WHITE)
        }

        // 边缘光效
        binding.btnGlow.setOnClickListener {
            viewModel.applyEdgeGlow(glowColor = Color.CYAN, glowRadius = 15f)
        }

        // ========== 面部美颜 ==========

        // 面部美颜
        binding.btnBeauty.setOnClickListener {
            val params = viewModel.beautyParams.value ?: BeautyParams()
            viewModel.applyFaceBeauty(
                slimIntensity = params.slimIntensity,
                eyeEnlargeIntensity = params.eyeEnlargeIntensity
            )
        }

        // 完整美颜（美颜 + 虚化）
        binding.btnFullBeauty.setOnClickListener {
            val params = viewModel.beautyParams.value ?: BeautyParams()
            viewModel.applyFullBeauty(
                slimIntensity = params.slimIntensity,
                eyeEnlargeIntensity = params.eyeEnlargeIntensity,
                blurRadius = params.blurRadius
            )
        }

        // ========== 参数调节 ==========

        // 瘦脸滑块
        binding.seekSlim.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val current = viewModel.beautyParams.value ?: BeautyParams()
                    viewModel.updateBeautyParams(current.copy(slimIntensity = progress / 100f))
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 大眼滑块
        binding.seekEye.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val current = viewModel.beautyParams.value ?: BeautyParams()
                    viewModel.updateBeautyParams(current.copy(eyeEnlargeIntensity = progress / 100f))
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateEffectUI(effect: EffectType) {
        // 更新 UI 以反映当前效果
        val effectName = when (effect) {
            EffectType.NONE -> "原图"
            EffectType.BACKGROUND_BLUR -> "背景虚化"
            EffectType.GRADIENT_BLUR -> "渐进虚化"
            EffectType.COLOR_POP -> "人像留色"
            EffectType.INVERSE_COLOR_POP -> "反向留色"
            EffectType.BACKGROUND_REPLACE -> "背景替换"
            EffectType.BACKGROUND_COLOR -> "纯色背景"
            EffectType.EDGE_GLOW -> "边缘光效"
            EffectType.FACE_BEAUTY -> "面部美颜"
            EffectType.FULL_BEAUTY -> "完整美颜"
        }
        binding.tvCurrentEffect.text = "当前效果: $effectName"
    }
}
