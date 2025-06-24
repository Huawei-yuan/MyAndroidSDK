package com.hw.sdk.testsdk

import android.app.Application
import com.hw.sdk.analytics.AnalyticsSDK
import com.hw.sdk.analytics.BuildConfig

class MyApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        AnalyticsSDK.init(this) {
            serverUrl = "https://your-analytics-server.com/api"
            appKey = "your_app_key_here"
            uploadInterval = 30_000L // 30秒
            batchSize = 50
            enableAutoTrack = true
            enableEncryption = true
            encryptionKey = "your_32_char_encryption_key_here"
            debugMode = BuildConfig.DEBUG
            enableLocationTracking = false
            sessionTimeout = 30 * 60 * 1000L // 30分钟
        }
    }
}