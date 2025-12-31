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

## 后续开发计划

- [ ] 实现滤镜选择界面
- [ ] 集成 MediaPipe 面部美化
- [ ] 添加人像背景替换功能
- [ ] 实现云端智能分析
- [ ] 添加图片导出与分享

## 许可证

MIT License
