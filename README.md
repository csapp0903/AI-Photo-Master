# AI Photo Master

一款基于 AI 技术的专业修图 Android 应用。

## 项目概述

AI Photo Master 是一款集成了多种 AI 能力的图片编辑应用，支持：
- 基础滤镜与 OpenGL ES 渲染
- 实时面部网格检测（468个3D面部标记点）
- 人像智能分割
- 云端图像分析与识别

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 (Android 14) |
| UI 框架 | XML + ViewBinding |
| 架构模式 | MVVM + Coroutines |
| 图像渲染 | GPUImage 2.1.0 |
| 面部检测 | MediaPipe Face Mesh |
| 人像分割 | ML Kit Selfie Segmentation |
| 云端分析 | Google Cloud Vision API |

## 项目结构

```
AI-Photo-Master/
├── app/
│   ├── build.gradle.kts          # 模块级构建配置
│   ├── proguard-rules.pro        # ProGuard 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml   # 应用清单
│       ├── java/com/aiphotomaster/
│       │   ├── AIPhotoMasterApp.kt    # Application 类
│       │   ├── ui/
│       │   │   └── MainActivity.kt    # 主界面
│       │   ├── face/                  # 人脸处理模块
│       │   │   ├── FaceMeshHelper.kt        # MediaPipe 封装
│       │   │   ├── FaceLandmarkIndices.kt   # 关键点索引常量
│       │   │   └── FaceBeautyProcessor.kt   # 美颜处理器
│       │   ├── filter/                # OpenGL 滤镜模块
│       │   │   ├── GPUImageFaceWarpFilter.kt        # 基础人脸变形滤镜
│       │   │   └── GPUImageAdvancedFaceWarpFilter.kt # 高级多点变形滤镜
│       │   ├── segmentation/          # 人像分割模块
│       │   │   ├── SegmentationHelper.kt      # ML Kit 人像分割封装
│       │   │   └── PortraitEffectProcessor.kt # 人像创意效果处理器
│       │   ├── cloud/                   # 云端分析模块
│       │   │   └── CloudVisionManager.kt     # Google Cloud Vision 单例
│       │   ├── viewmodel/             # ViewModel 层
│       │   │   ├── PhotoEditViewModel.kt      # 图片编辑 ViewModel
│       │   │   └── MainViewModel.kt           # 主界面 ViewModel
│       │   └── utils/
│       │       ├── BitmapUtils.kt     # Bitmap 工具类
│       │       └── PermissionHelper.kt # 权限辅助类
│       └── res/
│           ├── layout/           # 布局文件
│           ├── values/           # 资源值
│           └── xml/              # XML 配置
├── build.gradle.kts              # 项目级构建配置
├── settings.gradle.kts           # 设置配置
└── gradle.properties             # Gradle 属性
```

## 核心依赖

### 图像处理
```kotlin
// GPUImage - 基础滤镜与 OpenGL 渲染
implementation("jp.co.cyberagent.android:gpuimage:2.1.0")
```

### AI/ML 能力
```kotlin
// MediaPipe - 面部网格检测
implementation("com.google.mediapipe:tasks-vision:0.10.9")

// ML Kit - 人像分割
implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta5")

// Google Cloud Vision - 云端分析
implementation("com.google.cloud:google-cloud-vision:3.36.0")
```

### 相机与媒体
```kotlin
// CameraX
implementation("androidx.camera:camera-core:1.3.1")
implementation("androidx.camera:camera-camera2:1.3.1")
implementation("androidx.camera:camera-lifecycle:1.3.1")
implementation("androidx.camera:camera-view:1.3.1")

// EXIF 处理
implementation("androidx.exifinterface:exifinterface:1.3.7")
```

## 核心功能模块

### 人脸美颜引擎

基于 MediaPipe Face Mesh 和 OpenGL ES 的实时人脸美颜系统。

#### 架构设计

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   输入图片/帧    │ ──▶ │  FaceMeshHelper  │ ──▶ │  468个关键点    │
└─────────────────┘     │  (MediaPipe)     │     └────────┬────────┘
                        └──────────────────┘              │
                                                          ▼
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│    输出图片     │ ◀── │  GPUImageFilter  │ ◀── │  GLSL液化着色器  │
└─────────────────┘     │  (OpenGL ES)     │     └─────────────────┘
```

#### FaceMeshHelper - 人脸关键点检测

```kotlin
// 创建检测器（用于静态图片）
val helper = FaceMeshHelper.createForImage(context)

// 检测人脸
val result = helper.detectImage(bitmap)
if (result.hasFace) {
    val landmarks = result.faces[0].landmarks  // 478个关键点
    // landmarks[0..467] = 面部关键点
    // landmarks[468..477] = 虹膜关键点
}

// 释放资源
helper.close()
```

#### GPUImageFaceWarpFilter - 液化变形滤镜

核心 GLSL 液化算法实现瘦脸和大眼效果：

```kotlin
val filter = GPUImageFaceWarpFilter()

// 更新人脸关键点
filter.updateLandmarks(landmarks)

// 设置瘦脸强度 (0.0 ~ 1.0)
filter.setSlimIntensity(0.5f)

// 设置大眼强度 (0.0 ~ 1.0)
filter.setEyeEnlargeIntensity(0.3f)

// 应用到 GPUImage
gpuImage.setFilter(filter)
val result = gpuImage.bitmapWithFilterApplied
```

#### FaceBeautyProcessor - 一体化美颜处理器

封装了检测和渲染的完整流程：

```kotlin
// 创建处理器
val processor = FaceBeautyProcessor.create(context)

// 设置美颜参数
processor.setSlimIntensity(0.5f)      // 瘦脸
processor.setEyeEnlargeIntensity(0.3f) // 大眼

// 处理图片（自动检测人脸 + 应用变形）
val beautifiedBitmap = processor.process(inputBitmap)

// 释放资源
processor.release()
```

#### GLSL 液化算法原理

```glsl
// 推动型液化（瘦脸）
vec2 pushWarp(vec2 coord, vec2 center, vec2 direction, float radius, float intensity) {
    float dist = distance(coord, center);
    if (dist > radius) return coord;

    // 平滑衰减：距离中心越近，变形越强
    float falloff = 1.0 - smoothstep(0.0, 1.0, dist / radius);
    falloff = falloff * falloff;

    // 沿方向推动像素
    return coord - direction * intensity * radius * falloff;
}

// 放大型液化（大眼）
vec2 enlargeWarp(vec2 coord, vec2 center, float radius, float intensity) {
    float dist = distance(coord, center);
    if (dist > radius) return coord;

    // 将纹理坐标向中心收缩 = 显示效果放大
    float weight = 1.0 - (dist / radius) * (dist / radius);
    float scale = 1.0 - intensity * weight * 0.35;

    return center + (coord - center) * scale;
}
```

### 人像分割引擎

基于 ML Kit Selfie Segmentation 的人像分割与创意效果系统。

#### 架构设计

```
┌─────────────────┐     ┌──────────────────────┐     ┌─────────────────┐
│   输入图片      │ ──▶ │  SegmentationHelper  │ ──▶ │  分割掩码 Mask  │
└─────────────────┘     │  (ML Kit)            │     └────────┬────────┘
                        └──────────────────────┘              │
                                                              ▼
┌─────────────────┐     ┌──────────────────────┐     ┌─────────────────┐
│    创意效果     │ ◀── │PortraitEffectProcessor│ ◀──│  掩码 + 原图    │
└─────────────────┘     └──────────────────────┘     └─────────────────┘
```

#### SegmentationHelper - 人像分割

```kotlin
// 创建分割器
val helper = SegmentationHelper.createForSingleImage()

// 处理图片获取分割结果
val result = helper.process(bitmap)

// 转换为二值化掩码 Bitmap（人像=白，背景=黑）
val maskBitmap = result.toMaskBitmap(threshold = 0.5f)

// 转换为软边缘掩码（灰度过渡）
val softMask = result.toSoftMaskBitmap()

// 直接提取人像（背景透明）
val portrait = result.extractPortrait(bitmap)

// 释放资源
helper.close()
```

#### PortraitEffectProcessor - 创意效果处理

```kotlin
val processor = PortraitEffectProcessor.create(context)

// 功能 A: 背景虚化
val blurred = processor.applyBackgroundBlur(
    bitmap = original,
    blurRadius = 25f,
    useSoftEdge = true
)

// 功能 B: 人像留色（背景黑白）
val colorPop = processor.applyColorPop(bitmap)

// 反向人像留色（人像黑白，背景彩色）
val inverse = processor.applyInverseColorPop(bitmap)

// 背景替换
val replaced = processor.replaceBackground(portrait, newBackground)

// 纯色背景
val whiteBg = processor.replaceBackgroundWithColor(bitmap, Color.WHITE)

// 边缘光效
val glowing = processor.applyEdgeGlow(bitmap, glowColor = Color.CYAN)

processor.release()
```

### 云端智能分析引擎

基于 Google Cloud Vision API 的云端图像分析系统。

#### 架构设计

```
┌─────────────────┐     ┌──────────────────────┐     ┌─────────────────┐
│   输入图片      │ ──▶ │  CloudVisionManager  │ ──▶ │   分析结果      │
└─────────────────┘     │  (Cloud Vision API)  │     └─────────────────┘
                        └──────────────────────┘
                                 │
                    ┌────────────┼────────────┐
                    ▼            ▼            ▼
              ┌──────────┐ ┌──────────┐ ┌──────────┐
              │ 标签识别 │ │ 颜色分析 │ │ 文字识别 │
              └──────────┘ └──────────┘ └──────────┘
```

#### CloudVisionManager - 云端分析单例

```kotlin
// 分析图片标签
val labelResult = CloudVisionManager.detectLabels(bitmap, maxLabels = 10)
if (labelResult.success) {
    labelResult.labels.forEach { label ->
        println("${label.description}: ${label.score}")
    }
}

// 分析主色调
val colorResult = CloudVisionManager.analyzeImageProperties(bitmap, maxColors = 5)
if (colorResult.success) {
    colorResult.dominantColors.forEach { color ->
        println("颜色: ${color.toHexString()}, 占比: ${color.pixelFraction}")
    }
}

// 文字识别
val textResult = CloudVisionManager.detectText(bitmap)
if (textResult.success) {
    println("识别文字: ${textResult.text}")
}

// 安全搜索检测
val safeResult = CloudVisionManager.safeSearchDetection(bitmap)
if (safeResult.success) {
    println("成人内容: ${safeResult.adult}")
    println("暴力内容: ${safeResult.violence}")
}
```

#### 配置 Service Account

```kotlin
// 在 assets 目录放置 service-account.json 文件
// CloudVisionManager 会自动加载凭证

// 或者使用本地回退模式（无需 API 密钥）
// 本地模式使用 Palette 分析颜色，返回模拟标签数据
```

### 主界面集成

#### MainViewModel - 统一状态管理

```kotlin
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 观察编辑模式
        viewModel.currentMode.observe(this) { mode ->
            when (mode) {
                EditMode.BASIC -> showBasicPanel()
                EditMode.FACE -> showFacePanel()
                EditMode.PORTRAIT -> showPortraitPanel()
                EditMode.ANALYSIS -> showAnalysisPanel()
            }
        }

        // 观察处理后的图片
        viewModel.processedBitmap.observe(this) { bitmap ->
            gpuImageView.setImage(bitmap)
        }

        // 观察分析结果
        viewModel.analysisResult.observe(this) { state ->
            when (state) {
                is AnalysisState.Success -> showResult(state.result)
                is AnalysisState.Error -> showError(state.message)
            }
        }

        // 应用效果
        btnSlim.setOnClickListener {
            viewModel.setSlimIntensity(0.5f)
        }

        btnBlur.setOnClickListener {
            viewModel.applyBackgroundBlur(25f)
        }

        btnAnalyze.setOnClickListener {
            viewModel.analyzeImage()
        }
    }
}
```

#### 底部导航栏

应用使用底部导航栏切换四大功能模块：

| 标签 | 功能 | 说明 |
|------|------|------|
| 基础编辑 | 旋转、翻转、重置 | 基础图片变换 |
| 瘦脸大眼 | 人脸检测 + 美颜 | MediaPipe + GLSL |
| 人像特效 | 背景虚化、留色等 | ML Kit 分割 |
| 智能分析 | 标签/颜色识别 | Cloud Vision |

#### PhotoEditViewModel - UI 集成示例

```kotlin
class EditActivity : AppCompatActivity() {
    private val viewModel: PhotoEditViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 观察处理状态
        viewModel.processingState.observe(this) { state ->
            when (state) {
                is ProcessingState.Loading -> showLoading(state.message)
                is ProcessingState.Success -> showImage(state.bitmap)
                is ProcessingState.Error -> showError(state.message)
                is ProcessingState.Idle -> hideLoading()
            }
        }

        // 加载图片
        viewModel.loadImage(imageUri)

        // 应用效果
        btnBlur.setOnClickListener {
            viewModel.applyBackgroundBlur(blurRadius = 20f)
        }

        btnColorPop.setOnClickListener {
            viewModel.applyColorPop()
        }

        btnFullBeauty.setOnClickListener {
            viewModel.applyFullBeauty(
                slimIntensity = 0.5f,
                eyeEnlargeIntensity = 0.3f,
                blurRadius = 20f
            )
        }
    }
}
```

## 工具类说明

### BitmapUtils

提供 Bitmap 的常用操作：

```kotlin
// 旋转
val rotated = BitmapUtils.rotate(bitmap, 90f)

// 缩放
val scaled = BitmapUtils.scale(bitmap, 0.5f)
val scaledTo = BitmapUtils.scaleTo(bitmap, 1080, 1920)

// 从 Uri 加载（协程）
val bitmap = BitmapUtils.fromUri(context, uri)

// 保存到文件
BitmapUtils.saveToFile(bitmap, file, Bitmap.CompressFormat.JPEG, 90)

// 裁剪
val cropped = BitmapUtils.crop(bitmap, x, y, width, height)

// 翻转
val flippedH = BitmapUtils.flipHorizontal(bitmap)
val flippedV = BitmapUtils.flipVertical(bitmap)
```

### PermissionHelper

简化运行时权限处理：

```kotlin
// 检查权限
if (PermissionHelper.hasCameraPermission(context)) {
    // 有相机权限
}

if (PermissionHelper.hasStoragePermission(context)) {
    // 有存储权限（自动适配 Android 13+）
}

// 创建权限请求启动器
private val cameraLauncher = PermissionHelper.createPermissionLauncher(
    this,
    Manifest.permission.CAMERA,
    object : PermissionHelper.PermissionCallback {
        override fun onGranted() {
            // 权限已授予
        }
        override fun onDenied(permanentlyDenied: Boolean) {
            if (permanentlyDenied) {
                // 引导用户去设置页
                PermissionHelper.showSettingsDialog(this@MainActivity,
                    message = "需要相机权限来拍摄照片")
            }
        }
    }
)

// 请求权限
cameraLauncher.launch(Manifest.permission.CAMERA)
```

## 权限说明

应用需要以下权限：

| 权限 | 用途 | 版本适配 |
|------|------|----------|
| `CAMERA` | 拍摄照片 | 全版本 |
| `INTERNET` | 云端 AI 分析 | 全版本 |
| `READ_MEDIA_IMAGES` | 读取图片 | Android 13+ |
| `READ_EXTERNAL_STORAGE` | 读取存储 | Android 12 及以下 |

## 开发环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Gradle 8.2+
- Kotlin 1.9.22

## 构建与运行

1. 克隆项目
```bash
git clone https://github.com/your-repo/AI-Photo-Master.git
```

2. 在 Android Studio 中打开项目

3. 同步 Gradle 依赖

4. 运行应用到设备或模拟器

## 注意事项

1. **Google Cloud Vision API**: 需要配置 API 密钥才能使用云端分析功能
2. **MediaPipe**: 首次运行时会下载模型文件
3. **内存优化**: 建议在 AndroidManifest.xml 中启用 `android:largeHeap="true"`

## 开发进度

### 已完成
- [x] 项目基础架构搭建
- [x] BitmapUtils / PermissionHelper 工具类
- [x] FaceMeshHelper - MediaPipe 人脸检测封装
- [x] GPUImageFaceWarpFilter - GLSL 液化变形滤镜
- [x] GPUImageAdvancedFaceWarpFilter - 多点高级变形滤镜
- [x] FaceBeautyProcessor - 一体化美颜处理器
- [x] SegmentationHelper - ML Kit 人像分割封装
- [x] PortraitEffectProcessor - 人像创意效果处理器
- [x] PhotoEditViewModel - 图片编辑 ViewModel 示例
- [x] 背景虚化 / 渐进虚化
- [x] 人像留色 / 反向人像留色
- [x] 背景替换 / 纯色背景
- [x] 边缘光效
- [x] CloudVisionManager - 云端图像分析单例
- [x] MainViewModel - 主界面状态管理
- [x] MainActivity - 四大功能模块集成
- [x] GPUImageView 实时预览
- [x] 底部导航栏切换

### 进行中
- [ ] 添加保存和分享功能
- [ ] 实时相机美颜预览

### 待开发
- [ ] 更多滤镜效果
- [ ] 图片裁剪功能
- [ ] 贴纸和文字功能

## 许可证

MIT License
