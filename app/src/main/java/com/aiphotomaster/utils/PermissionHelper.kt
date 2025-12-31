package com.aiphotomaster.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.aiphotomaster.R

/**
 * 权限辅助类（单例）
 *
 * 用于检查和请求运行时权限，支持：
 * - 相机权限
 * - 存储权限（兼容 Android 13+ 细粒度权限）
 * - 权限拒绝后的设置页引导
 */
object PermissionHelper {

    // ==================== 权限常量 ====================

    /**
     * 相机权限
     */
    val CAMERA_PERMISSION = Manifest.permission.CAMERA

    /**
     * 存储权限（根据 API 级别返回不同权限）
     */
    val STORAGE_PERMISSIONS: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用细粒度媒体权限
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-12 只需要读取权限（Scoped Storage）
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            // Android 9 及以下需要读写权限
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }

    /**
     * 图片读取权限（仅图片）
     */
    val IMAGE_PERMISSION: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    // ==================== 权限检查 ====================

    /**
     * 检查单个权限是否已授予
     */
    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查多个权限是否全部授予
     */
    fun hasPermissions(context: Context, permissions: Array<String>): Boolean {
        return permissions.all { hasPermission(context, it) }
    }

    /**
     * 检查相机权限
     */
    fun hasCameraPermission(context: Context): Boolean {
        return hasPermission(context, CAMERA_PERMISSION)
    }

    /**
     * 检查存储权限
     */
    fun hasStoragePermission(context: Context): Boolean {
        return hasPermissions(context, STORAGE_PERMISSIONS)
    }

    /**
     * 检查图片读取权限
     */
    fun hasImagePermission(context: Context): Boolean {
        return hasPermission(context, IMAGE_PERMISSION)
    }

    // ==================== 权限请求结果处理 ====================

    /**
     * 检查权限请求结果
     *
     * @param grantResults 权限授予结果数组
     * @return 是否所有权限都被授予
     */
    fun isAllGranted(grantResults: IntArray): Boolean {
        return grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
    }

    /**
     * 检查权限请求结果（Map 形式）
     */
    fun isAllGranted(results: Map<String, Boolean>): Boolean {
        return results.isNotEmpty() && results.all { it.value }
    }

    // ==================== 权限请求理由检查 ====================

    /**
     * 检查是否应该显示权限请求理由
     */
    fun shouldShowRationale(activity: Activity, permission: String): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    /**
     * 检查是否应该显示权限请求理由（任意一个权限）
     */
    fun shouldShowRationale(activity: Activity, permissions: Array<String>): Boolean {
        return permissions.any { shouldShowRationale(activity, it) }
    }

    /**
     * 检查用户是否永久拒绝了权限
     * （shouldShowRationale 返回 false 且权限未授予）
     */
    fun isPermanentlyDenied(activity: Activity, permission: String): Boolean {
        return !hasPermission(activity, permission) &&
                !shouldShowRationale(activity, permission)
    }

    // ==================== 设置页引导 ====================

    /**
     * 跳转到应用设置页
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 显示引导用户去设置页的对话框
     */
    fun showSettingsDialog(
        activity: Activity,
        title: String = activity.getString(R.string.permission_denied),
        message: String,
        onPositive: () -> Unit = { openAppSettings(activity) },
        onNegative: () -> Unit = {}
    ) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.permission_go_settings) { dialog, _ ->
                dialog.dismiss()
                onPositive()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                onNegative()
            }
            .setCancelable(false)
            .show()
    }

    // ==================== Activity Result API 辅助 ====================

    /**
     * 权限请求结果回调
     */
    interface PermissionCallback {
        fun onGranted()
        fun onDenied(permanentlyDenied: Boolean)
    }

    /**
     * 创建权限请求启动器（用于单个权限）
     *
     * 使用方法：
     * ```kotlin
     * class MyActivity : AppCompatActivity() {
     *     private val cameraPermissionLauncher = PermissionHelper.createPermissionLauncher(
     *         this,
     *         Manifest.permission.CAMERA,
     *         object : PermissionHelper.PermissionCallback {
     *             override fun onGranted() { /* 权限已授予 */ }
     *             override fun onDenied(permanentlyDenied: Boolean) { /* 权限被拒绝 */ }
     *         }
     *     )
     *
     *     fun requestCamera() {
     *         cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
     *     }
     * }
     * ```
     */
    fun createPermissionLauncher(
        activity: FragmentActivity,
        permission: String,
        callback: PermissionCallback
    ): ActivityResultLauncher<String> {
        return activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                callback.onGranted()
            } else {
                val permanentlyDenied = isPermanentlyDenied(activity, permission)
                callback.onDenied(permanentlyDenied)
            }
        }
    }

    /**
     * 创建多权限请求启动器
     *
     * 使用方法：
     * ```kotlin
     * class MyActivity : AppCompatActivity() {
     *     private val storagePermissionLauncher = PermissionHelper.createMultiplePermissionLauncher(
     *         this,
     *         object : PermissionHelper.PermissionCallback {
     *             override fun onGranted() { /* 所有权限已授予 */ }
     *             override fun onDenied(permanentlyDenied: Boolean) { /* 部分权限被拒绝 */ }
     *         }
     *     )
     *
     *     fun requestStorage() {
     *         storagePermissionLauncher.launch(PermissionHelper.STORAGE_PERMISSIONS)
     *     }
     * }
     * ```
     */
    fun createMultiplePermissionLauncher(
        activity: FragmentActivity,
        callback: PermissionCallback
    ): ActivityResultLauncher<Array<String>> {
        return activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (isAllGranted(results)) {
                callback.onGranted()
            } else {
                val deniedPermissions = results.filterValues { !it }.keys
                val permanentlyDenied = deniedPermissions.any {
                    isPermanentlyDenied(activity, it)
                }
                callback.onDenied(permanentlyDenied)
            }
        }
    }

    /**
     * 创建权限请求启动器（Fragment 版本）
     */
    fun createPermissionLauncher(
        fragment: Fragment,
        permission: String,
        callback: PermissionCallback
    ): ActivityResultLauncher<String> {
        return fragment.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                callback.onGranted()
            } else {
                val activity = fragment.requireActivity()
                val permanentlyDenied = isPermanentlyDenied(activity, permission)
                callback.onDenied(permanentlyDenied)
            }
        }
    }

    /**
     * 创建多权限请求启动器（Fragment 版本）
     */
    fun createMultiplePermissionLauncher(
        fragment: Fragment,
        callback: PermissionCallback
    ): ActivityResultLauncher<Array<String>> {
        return fragment.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (isAllGranted(results)) {
                callback.onGranted()
            } else {
                val activity = fragment.requireActivity()
                val deniedPermissions = results.filterValues { !it }.keys
                val permanentlyDenied = deniedPermissions.any {
                    isPermanentlyDenied(activity, it)
                }
                callback.onDenied(permanentlyDenied)
            }
        }
    }
}
