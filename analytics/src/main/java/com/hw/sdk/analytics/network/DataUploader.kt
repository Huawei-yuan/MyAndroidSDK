@file:OptIn(InternalSerializationApi::class)

package com.hw.sdk.analytics.network

import android.content.Context
import com.hw.sdk.analytics.config.AnalyticsConfig
import com.hw.sdk.analytics.models.AnalyticsResult
import com.hw.sdk.analytics.storage.EventDatabase
import com.hw.sdk.analytics.storage.EventEntity
import com.hw.sdk.analytics.utils.gzip
import com.hw.sdk.analytics.utils.isNetworkAvailable
import com.hw.sdk.analytics.utils.logDebug
import com.hw.sdk.analytics.utils.logError
import com.hw.sdk.analytics.utils.toEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import kotlin.collections.map
import kotlin.jvm.java

class DataUploader(
    private val context: Context,
    private val config: AnalyticsConfig,
    private val database: EventDatabase
) {
    private val apiService: AnalyticsApiService by lazy {
        Retrofit.Builder()
            .baseUrl(config.serverUrl)
            .client(createOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AnalyticsApiService::class.java)
    }

    private val retryPolicy = RetryPolicy()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun uploadEvents(): AnalyticsResult<Int> = withContext(Dispatchers.IO) {
        try {
            val events = database.eventDao().getUnuploadedEvents(config.batchSize)
            if (events.isEmpty()) {
                return@withContext AnalyticsResult.Success(0)
            }

            // 检查网络连接
            if (!context.isNetworkAvailable()) {
                return@withContext AnalyticsResult.Error(
                    Exception("No network connection")
                )
            }

            // 转换为Event对象
            val eventList = events.map { it.toEvent() }
            val request = UploadRequest(eventList, config.appKey)

            // 执行网络请求
            val response = apiService.uploadEvents(
                authorization = "Bearer ${config.appKey}",
                request = request
            )

            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    // 标记为已上报
                    val eventIds = events.map { it.id }
                    database.eventDao().markAsUploaded(eventIds)

                    // 清理旧数据
                    cleanOldData()

                    if (config.debugMode) {
                        logDebug("Successfully uploaded ${events.size} events")
                    }

                    AnalyticsResult.Success(events.size)
                } else {
                    AnalyticsResult.Error(Exception(body?.message ?: "Upload failed"))
                }
            } else {
                handleUploadFailure(events)
                AnalyticsResult.Error(Exception("HTTP ${response.code()}: ${response.message()}"))
            }

        } catch (e: Exception) {
            logError("Upload failed", e)
            AnalyticsResult.Error(e)
        }
    }

    private suspend fun handleUploadFailure(events: List<EventEntity>) {
        val eventIds = events.map { it.id }
        database.eventDao().incrementRetryCount(eventIds)
    }

    private suspend fun cleanOldData() {
        val oneWeekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        database.eventDao().deleteOldUploadedEvents(oneWeekAgo)
        database.eventDao().deleteFailedEvents(oneWeekAgo)
    }

    private fun createOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
//            .addInterceptor(createLoggingInterceptor())
            .addInterceptor(createCompressionInterceptor())
            .build()
    }

//    private fun createLoggingInterceptor(): Interceptor {
//        return if (config.debugMode) {
//            HttpLoggingInterceptor().apply {
//                level = HttpLoggingInterceptor.Level.BODY
//            }
//        } else {
//            Interceptor { chain -> chain.proceed(chain.request()) }
//        }
//    }

    private fun createCompressionInterceptor(): Interceptor {
        return Interceptor { chain ->
            val originalRequest = chain.request()
            val compressedRequest = originalRequest.newBuilder()
                .header("Content-Encoding", "gzip")
                .method(originalRequest.method, originalRequest.body?.gzip())
                .build()
            chain.proceed(compressedRequest)
        }
    }
}