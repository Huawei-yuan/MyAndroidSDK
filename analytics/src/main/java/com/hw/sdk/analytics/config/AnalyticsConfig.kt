@file:OptIn(InternalSerializationApi::class)

package com.hw.sdk.analytics.config

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable


@InternalSerializationApi @Serializable
data class AnalyticsConfig(
    val serverUrl: String,
    val appKey: String,
    val uploadInterval: Long = 30_000L,
    val batchSize: Int = 50,
    val maxBatchSize: Int = 100,
    val enableAutoTrack: Boolean = true,
    val enableEncryption: Boolean = false,
    val encryptionKey: String? = null,
    val debugMode: Boolean = false,
    val enableLocationTracking: Boolean = false,
    val sessionTimeout: Long = 30 * 60 * 1000L // 30分钟
)

class AnalyticsConfigBuilder {
    var serverUrl: String = ""
    var appKey: String = ""
    var uploadInterval: Long = 30_000L
    var batchSize: Int = 50
    var maxBatchSize: Int = 100
    var enableAutoTrack: Boolean = true
    var enableEncryption: Boolean = false
    var encryptionKey: String? = null
    var debugMode: Boolean = false
    var enableLocationTracking: Boolean = false
    var sessionTimeout: Long = 30 * 60 * 1000L

    fun build(): AnalyticsConfig {
        require(serverUrl.isNotEmpty()) { "Server URL cannot be empty" }
        require(appKey.isNotEmpty()) { "App key cannot be empty" }

        return AnalyticsConfig(
            serverUrl = serverUrl,
            appKey = appKey,
            uploadInterval = uploadInterval,
            batchSize = batchSize,
            maxBatchSize = maxBatchSize,
            enableAutoTrack = enableAutoTrack,
            enableEncryption = enableEncryption,
            encryptionKey = encryptionKey,
            debugMode = debugMode,
            enableLocationTracking = enableLocationTracking,
            sessionTimeout = sessionTimeout
        )
    }
}

// DSL 扩展函数
fun analyticsConfig(block: AnalyticsConfigBuilder.() -> Unit): AnalyticsConfig {
    return AnalyticsConfigBuilder().apply(block).build()
}