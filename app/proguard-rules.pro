# AI Photo Master ProGuard Rules
# ================================

# 保留应用程序入口点
-keep class com.aiphotomaster.** { *; }

# ==================== GPUImage ====================
-keep class jp.co.cyberagent.android.gpuimage.** { *; }
-dontwarn jp.co.cyberagent.android.gpuimage.**

# ==================== MediaPipe ====================
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ==================== ML Kit ====================
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ==================== Google Cloud Vision ====================
-keep class com.google.cloud.vision.** { *; }
-keep class com.google.api.** { *; }
-keep class io.grpc.** { *; }
-dontwarn com.google.cloud.**
-dontwarn io.grpc.**

# ==================== Kotlin Coroutines ====================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ==================== Kotlin Serialization ====================
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# ==================== AndroidX ====================
-keep class androidx.lifecycle.** { *; }
-keep class androidx.camera.** { *; }

# ==================== 通用规则 ====================
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
