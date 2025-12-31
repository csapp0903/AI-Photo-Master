package com.aiphotomaster

import android.app.Application

/**
 * AI Photo Master 应用程序类
 *
 * 用于全局初始化和配置
 */
class AIPhotoMasterApp : Application() {

    companion object {
        lateinit var instance: AIPhotoMasterApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化配置
        initializeApp()
    }

    private fun initializeApp() {
        // TODO: 初始化 ML Kit
        // TODO: 初始化 MediaPipe
        // TODO: 初始化 Google Cloud Vision（需要配置 API 密钥）
    }
}
