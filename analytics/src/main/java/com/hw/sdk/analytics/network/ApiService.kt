@file:OptIn(InternalSerializationApi::class)

package com.hw.sdk.analytics.network

import com.hw.sdk.analytics.models.Event
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface AnalyticsApiService {
    @POST("events")
    suspend fun uploadEvents(
        @Header("Authorization") authorization: String,
        @Body request: UploadRequest
    ): Response<UploadResponse>
}

@InternalSerializationApi @Serializable
data class UploadRequest(
    val events: List<Event>,
    val appKey: String,
    val timestamp: Long = System.currentTimeMillis()
)

@InternalSerializationApi @Serializable
data class UploadResponse(
    val success: Boolean,
    val message: String? = null,
    val receivedCount: Int = 0
)