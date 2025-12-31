plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aiphotomaster"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aiphotomaster"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 启用 ViewBinding
    buildFeatures {
        viewBinding = true
    }

    // 打包配置
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    // ==================== AndroidX 核心库 ====================
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // ==================== Lifecycle & ViewModel (MVVM) ====================
    val lifecycleVersion = "2.7.0"
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-common-java8:$lifecycleVersion")

    // ==================== Kotlin Coroutines ====================
    val coroutinesVersion = "1.7.3"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    // ==================== GPUImage - 基础滤镜与渲染 ====================
    // 用于图像滤镜处理和 OpenGL ES 渲染
    implementation("jp.co.cyberagent.android:gpuimage:2.1.0")

    // ==================== MediaPipe Face Mesh - 面部网格检测 ====================
    // 用于实时面部特征点检测 (468个3D面部标记点)
    implementation("com.google.mediapipe:tasks-vision:0.10.9")

    // ==================== ML Kit Selfie Segmentation - 人像分割 ====================
    // 用于将人像从背景中分离
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta5")

    // ==================== Google Cloud Vision API - 云端图像分析 ====================
    // 用于高级图像识别、标签检测、OCR等功能
    implementation("com.google.cloud:google-cloud-vision:3.36.0")
    // gRPC 支持
    implementation("io.grpc:grpc-okhttp:1.60.1")
    implementation("io.grpc:grpc-stub:1.60.1")

    // ==================== 图片加载库 ====================
    implementation("io.coil-kt:coil:2.5.0")

    // ==================== CameraX - 相机功能 ====================
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ==================== Exif 信息处理 ====================
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // ==================== 测试依赖 ====================
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
