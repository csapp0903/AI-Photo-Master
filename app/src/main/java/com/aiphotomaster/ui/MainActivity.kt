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
import com.aiphotomaster.R
import com.aiphotomaster.databinding.ActivityMainBinding
import com.aiphotomaster.utils.PermissionHelper
import com.aiphotomaster.viewmodel.AnalysisState
import com.aiphotomaster.viewmodel.EditMode
import com.aiphotomaster.viewmodel.LoadingState
import com.aiphotomaster.viewmodel.MainViewModel
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.GPUImageView

/**
 * 主界面 Activity
 *
 * 整合所有编辑功能：
 * - 底部导航切换模式
 * - GPUImageView 实时预览
 * - 滑动条调节参数
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // GPUImageView 用于实时预览
    private lateinit var gpuImageView: GPUImageView

    // 图片选择器
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadImage(it) }
    }

    // 权限请求
    private val storagePermissionLauncher = PermissionHelper.createMultiplePermissionLauncher(
        this,
        object : PermissionHelper.PermissionCallback {
            override fun onGranted() {
                pickImage.launch("image/*")
            }

            override fun onDenied(permanentlyDenied: Boolean) {
                if (permanentlyDenied) {
                    PermissionHelper.showSettingsDialog(
                        this@MainActivity,
                        message = getString(R.string.permission_storage_message)
                    )
                } else {
                    Toast.makeText(this@MainActivity, "需要存储权限", Toast.LENGTH_SHORT).show()
                }
            }
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGPUImageView()
        setupObservers()
        setupBottomNavigation()
        setupBasicPanel()
        setupFacePanel()
        setupPortraitPanel()
        setupAnalysisPanel()
    }

    /**
     * 初始化 GPUImageView
     */
    private fun setupGPUImageView() {
        gpuImageView = binding.gpuImageView
        gpuImageView.setScaleType(GPUImage.ScaleType.CENTER_INSIDE)
    }

    /**
     * 设置观察者
     */
    private fun setupObservers() {
        // 加载状态
        viewModel.loadingState.observe(this) { state ->
            when (state) {
                is LoadingState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.tvStatus.text = state.message
                }
                is LoadingState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = ""
                }
                is LoadingState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = state.message
                    Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                }
                is LoadingState.Idle -> {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }

        // 处理后的图片
        viewModel.processedBitmap.observe(this) { bitmap ->
            bitmap?.let {
                gpuImageView.setImage(it)
            }
        }

        // 当前模式
        viewModel.currentMode.observe(this) { mode ->
            updatePanelVisibility(mode)
            updateBottomNavSelection(mode)
        }

        // 人脸检测状态
        viewModel.faceDetected.observe(this) { detected ->
            binding.tvFaceStatus.text = if (detected) "已检测到人脸" else "未检测到人脸"
            binding.faceControlPanel.visibility = if (detected) View.VISIBLE else View.GONE
        }

        // 美颜参数
        viewModel.faceBeautyParams.observe(this) { params ->
            binding.seekSlim.progress = (params.slimIntensity * 100).toInt()
            binding.seekEye.progress = (params.eyeEnlargeIntensity * 100).toInt()
            binding.tvSlimValue.text = "${(params.slimIntensity * 100).toInt()}%"
            binding.tvEyeValue.text = "${(params.eyeEnlargeIntensity * 100).toInt()}%"
        }

        // 分析结果
        viewModel.analysisResult.observe(this) { state ->
            when (state) {
                is AnalysisState.Loading -> {
                    binding.tvAnalysisResult.text = "分析中..."
                }
                is AnalysisState.Success -> {
                    val labels = state.result.labels.getFormattedLabels()
                    val colors = state.result.imageProperties.dominantColors
                        .take(3)
                        .joinToString(", ") { it.toHexString() }

                    binding.tvAnalysisResult.text = buildString {
                        append("标签: $labels\n")
                        append("主色调: $colors")
                    }
                }
                is AnalysisState.Error -> {
                    binding.tvAnalysisResult.text = state.message
                }
                else -> {}
            }
        }

        // Toast 消息
        viewModel.toastMessage.observe(this) { message ->
            message?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                viewModel.clearToast()
            }
        }
    }

    /**
     * 设置底部导航栏
     */
    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_basic -> {
                    viewModel.setEditMode(EditMode.BASIC)
                    true
                }
                R.id.nav_face -> {
                    viewModel.setEditMode(EditMode.FACE)
                    true
                }
                R.id.nav_portrait -> {
                    viewModel.setEditMode(EditMode.PORTRAIT)
                    true
                }
                R.id.nav_analysis -> {
                    viewModel.setEditMode(EditMode.ANALYSIS)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 设置基础编辑面板
     */
    private fun setupBasicPanel() {
        // 选择图片
        binding.btnPickImage.setOnClickListener {
            requestStoragePermissionAndPickImage()
        }

        // 旋转
        binding.btnRotateLeft.setOnClickListener {
            viewModel.rotateImage(-90f)
        }

        binding.btnRotateRight.setOnClickListener {
            viewModel.rotateImage(90f)
        }

        // 翻转
        binding.btnFlipH.setOnClickListener {
            viewModel.flipImage(horizontal = true)
        }

        binding.btnFlipV.setOnClickListener {
            viewModel.flipImage(horizontal = false)
        }

        // 重置
        binding.btnReset.setOnClickListener {
            viewModel.resetToOriginal()
        }
    }

    /**
     * 设置瘦脸大眼面板
     */
    private fun setupFacePanel() {
        // 检测人脸按钮
        binding.btnDetectFace.setOnClickListener {
            viewModel.detectFace()
        }

        // 瘦脸滑块
        binding.seekSlim.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewModel.setSlimIntensity(progress / 100f)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 大眼滑块
        binding.seekEye.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewModel.setEyeEnlargeIntensity(progress / 100f)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /**
     * 设置人像特效面板
     */
    private fun setupPortraitPanel() {
        // 背景虚化
        binding.btnBlur.setOnClickListener {
            viewModel.applyBackgroundBlur(25f)
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
            viewModel.applyBackgroundColor(Color.WHITE)
        }

        // 边缘光效
        binding.btnGlow.setOnClickListener {
            viewModel.applyEdgeGlow(Color.CYAN)
        }
    }

    /**
     * 设置智能分析面板
     */
    private fun setupAnalysisPanel() {
        // 智能分析
        binding.btnAnalyze.setOnClickListener {
            viewModel.analyzeImage()
        }

        // 颜色分析
        binding.btnAnalyzeColors.setOnClickListener {
            viewModel.analyzeDominantColors()
        }
    }

    /**
     * 更新面板可见性
     */
    private fun updatePanelVisibility(mode: EditMode) {
        binding.basicPanel.visibility = if (mode == EditMode.BASIC) View.VISIBLE else View.GONE
        binding.facePanel.visibility = if (mode == EditMode.FACE) View.VISIBLE else View.GONE
        binding.portraitPanel.visibility = if (mode == EditMode.PORTRAIT) View.VISIBLE else View.GONE
        binding.analysisPanel.visibility = if (mode == EditMode.ANALYSIS) View.VISIBLE else View.GONE
    }

    /**
     * 更新底部导航选中状态
     */
    private fun updateBottomNavSelection(mode: EditMode) {
        val itemId = when (mode) {
            EditMode.BASIC -> R.id.nav_basic
            EditMode.FACE -> R.id.nav_face
            EditMode.PORTRAIT -> R.id.nav_portrait
            EditMode.ANALYSIS -> R.id.nav_analysis
        }
        binding.bottomNavigation.selectedItemId = itemId
    }

    /**
     * 请求存储权限并选择图片
     */
    private fun requestStoragePermissionAndPickImage() {
        if (PermissionHelper.hasStoragePermission(this)) {
            pickImage.launch("image/*")
        } else {
            storagePermissionLauncher.launch(PermissionHelper.STORAGE_PERMISSIONS)
        }
    }
}
