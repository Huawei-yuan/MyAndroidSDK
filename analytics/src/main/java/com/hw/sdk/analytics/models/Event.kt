package com.hw.sdk.analytics.models

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@InternalSerializationApi @Serializable
data class Event(
    val eventName: String,
    val properties: Map<String, JsonElement>,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    val userId: String?,
    val deviceInfo: DeviceInfo,
    val userProperties: Map<String, JsonElement> = emptyMap()
)

@InternalSerializationApi @Serializable
data class DeviceInfo(
    val deviceId: String,
    val platform: String = "Android",
    val osVersion: String,
    val appVersion: String,
    val manufacturer: String,
    val model: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val networkType: String,
    val carrier: String?,
    val language: String,
    val timezone: String
)

// 使用密封类处理结果
sealed class AnalyticsResult<out T> {
    data class Success<T>(val data: T) : AnalyticsResult<T>()
    data class Error(val exception: Throwable) : AnalyticsResult<Nothing>()
    object Loading : AnalyticsResult<Nothing>()
}

// 扩展函数简化结果处理
inline fun <T> AnalyticsResult<T>.onSuccess(action: (T) -> Unit): AnalyticsResult<T> {
    if (this is AnalyticsResult.Success) action(data)
    return this
}

inline fun <T> AnalyticsResult<T>.onError(action: (Throwable) -> Unit): AnalyticsResult<T> {
    if (this is AnalyticsResult.Error) action(exception)
    return this
}
