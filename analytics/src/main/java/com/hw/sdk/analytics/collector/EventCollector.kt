@file:OptIn(InternalSerializationApi::class)

package com.hw.sdk.analytics.collector

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.hw.sdk.analytics.config.AnalyticsConfig
import com.hw.sdk.analytics.models.DeviceInfo
import com.hw.sdk.analytics.models.Event
import com.hw.sdk.analytics.processor.EventProcessor
import com.hw.sdk.analytics.storage.EventDatabase
import com.hw.sdk.analytics.utils.getDeviceInfo
import com.hw.sdk.analytics.utils.logDebug
import com.hw.sdk.analytics.utils.logError
import com.hw.sdk.analytics.utils.toEntity
import com.hw.sdk.analytics.utils.toJsonElement
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class EventCollector(
    private val context: Context,
    private val config: AnalyticsConfig,
    private val database: EventDatabase
) {
    private val deviceInfo: DeviceInfo by lazy { context.getDeviceInfo() }
    private val sessionManager = SessionManager(config.sessionTimeout)
    private val eventProcessor = EventProcessor(config)

    // 用户属性（线程安全）
    private val userProperties = ConcurrentHashMap<String, JsonElement>()

    suspend fun collectEvent(eventName: String, properties: Map<String, Any>) {
        try {
            val event = buildEvent(eventName, properties)
            val processedEvent = eventProcessor.processEvent(event)

            // 保存到数据库
            database.eventDao().insertEvent(processedEvent.toEntity())

            if (config.debugMode) {
                logDebug("Event collected: $eventName")
            }

        } catch (e: Exception) {
            logError("Failed to collect event: $eventName", e)
        }
    }

    suspend fun setUserProperties(properties: Map<String, Any>) {
        properties.forEach { (key, value) ->
            userProperties[key] = value.toJsonElement()
        }
    }

    private fun buildEvent(eventName: String, properties: Map<String, Any>): Event {
        return Event(
            eventName = eventName,
            properties = properties.mapValues { it.value.toJsonElement() },
            sessionId = sessionManager.getCurrentSessionId(),
            userId = getCurrentUserId(),
            deviceInfo = deviceInfo,
            userProperties = userProperties.toMap()
        )
    }

    private fun getCurrentUserId(): String? {
        // 从SharedPreferences或其他地方获取用户ID
        return context.getSharedPreferences("analytics", Context.MODE_PRIVATE)
            ?.getString("user_id", null)
    }
}

// 会话管理器
class SessionManager(private val sessionTimeout: Long) {
    private var currentSessionId: String = generateSessionId()
    private var lastActivityTime: Long = System.currentTimeMillis()

    @Synchronized
    fun getCurrentSessionId(): String {
        val now = System.currentTimeMillis()
        if (now - lastActivityTime > sessionTimeout) {
            currentSessionId = generateSessionId()
        }
        lastActivityTime = now
        return currentSessionId
    }

    private fun generateSessionId(): String {
        return "${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}