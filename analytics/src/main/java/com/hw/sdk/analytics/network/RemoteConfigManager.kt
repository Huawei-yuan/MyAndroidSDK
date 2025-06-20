package com.hw.sdk.analytics.network

import android.content.Context
import com.hw.sdk.analytics.config.RemoteConfiguration
import com.hw.sdk.analytics.models.AnalyticsResult
import com.hw.sdk.analytics.utils.logError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

@OptIn(InternalSerializationApi::class)
class RemoteConfigManager(
    private val context: Context,
    private val configUrl: String
) {
    private val _configState = MutableStateFlow<AnalyticsResult<RemoteConfiguration>>(
        AnalyticsResult.Loading
    )
    val configState: StateFlow<AnalyticsResult<RemoteConfiguration>> = _configState.asStateFlow()

    private val preferences by lazy {
        context.getSharedPreferences("analytics_remote_config", Context.MODE_PRIVATE)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchConfig() {
        try {
            val request = Request.Builder()
                .url(configUrl)
                .addHeader("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).await()

            if (response.isSuccessful) {
                val json = response.body?.string()
                if (json != null) {
                    val config = Json.decodeFromString<RemoteConfiguration>(json)
                    saveConfigToLocal(config)
                    _configState.value = AnalyticsResult.Success(config)
                } else {
                    _configState.value = AnalyticsResult.Error(Exception("Empty response"))
                }
            } else {
                _configState.value = AnalyticsResult.Error(
                    Exception("HTTP ${response.code}: ${response.message}")
                )
            }
        } catch (e: Exception) {
            logError("Failed to fetch remote config", e)
            _configState.value = AnalyticsResult.Error(e)

            // 尝试加载本地缓存
            loadConfigFromLocal()?.let {
                _configState.value = AnalyticsResult.Success(it)
            }
        }
    }

    private fun saveConfigToLocal(config: RemoteConfiguration) {
        val json = Json.encodeToString(config)
        preferences.edit()
            .putString("config_json", json)
            .putLong("config_timestamp", System.currentTimeMillis())
            .apply()
    }

    private fun loadConfigFromLocal(): RemoteConfiguration? {
        return try {
            val json = preferences.getString("config_json", null)
            if (json != null) {
                Json.decodeFromString<RemoteConfiguration>(json)
            } else null
        } catch (e: Exception) {
            logError("Failed to load local config", e)
            null
        }
    }

    fun isConfigExpired(): Boolean {
        val timestamp = preferences.getLong("config_timestamp", 0)
        val expireTime = 24 * 60 * 60 * 1000L // 24小时过期
        return System.currentTimeMillis() - timestamp > expireTime
    }
}

// OkHttp Call扩展 - 协程支持
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resumeWith(Result.success(response))
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })

    continuation.invokeOnCancellation {
        cancel()
    }
}