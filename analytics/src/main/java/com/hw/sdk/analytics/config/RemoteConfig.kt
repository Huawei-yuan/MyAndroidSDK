package com.hw.sdk.analytics.config

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@InternalSerializationApi @Serializable
data class RemoteConfiguration(
    val enableAutoTrack: Boolean = true,
    val uploadInterval: Long = 30_000L,
    val batchSize: Int = 50,
    val enableEncryption: Boolean = false,
    val sampleRate: Float = 1.0f, // 采样率
    val eventFilters: List<String> = emptyList(), // 过滤的事件
    val debugMode: Boolean = false,
    val features: Map<String, Boolean> = emptyMap() // 功能开关
)