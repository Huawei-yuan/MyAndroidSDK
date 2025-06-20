@file:OptIn(InternalSerializationApi::class)

package com.hw.sdk.analytics

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.hw.sdk.analytics.collector.EventCollector
import com.hw.sdk.analytics.config.AnalyticsConfig
import com.hw.sdk.analytics.config.AnalyticsConfigBuilder
import com.hw.sdk.analytics.network.DataUploader
import com.hw.sdk.analytics.storage.EventDatabase
import com.hw.sdk.analytics.utils.logError
import com.hw.sdk.analytics.utils.logWarning
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi

class AnalyticsSDK private constructor() {
    companion object {
        @Volatile
        private var INSTANCE: AnalyticsSDK? = null

        fun getInstance(): AnalyticsSDK {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AnalyticsSDK().also { INSTANCE = it }
            }
        }
    }

    private var config: AnalyticsConfig? = null
    private var eventCollector: EventCollector? = null
    private var dataUploader: DataUploader? = null
    private var isInitialized = false

    // 协程作用域
    private val sdkScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("AnalyticsSDK")
    )

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun init(context: Context, block: AnalyticsConfigBuilder.() -> Unit) {
        if (isInitialized) return

        // 使用DSL构建配置
        this.config = AnalyticsConfigBuilder().apply(block).build()

        // 初始化组件
        val database = EventDatabase.getInstance(context)
        this.eventCollector = EventCollector(context, config!!, database)
        this.dataUploader = DataUploader(context, config!!, database)

        // 启动定时上报
        startScheduledUpload()
        isInitialized = true
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun track(eventName: String, properties: Map<String, Any> = emptyMap()) {
        if (!isInitialized) {
            logWarning("SDK not initialized")
            return
        }

        sdkScope.launch {
            eventCollector?.collectEvent(eventName, properties)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun setUserProperties(properties: Map<String, Any>) {
        if (!isInitialized) return

        sdkScope.launch {
            eventCollector?.setUserProperties(properties)
        }
    }

    private fun startScheduledUpload() {
        sdkScope.launch {
            while (isActive) {
                try {
                    dataUploader?.uploadEvents()
                    delay(config?.uploadInterval ?: 30_000L)
                } catch (e: Exception) {
                    logError("Scheduled upload failed", e)
                    delay(60_000L) // 失败后等待更长时间
                }
            }
        }
    }

    fun flush() {
        sdkScope.launch {
            dataUploader?.uploadEvents()
        }
    }

    fun shutdown() {
        sdkScope.cancel()
        isInitialized = false
    }
}