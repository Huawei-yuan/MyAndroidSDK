@file:OptIn(InternalSerializationApi::class)

package com.hw.sdk.analytics.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.hw.sdk.analytics.models.DeviceInfo
import com.hw.sdk.analytics.models.Event
import com.hw.sdk.analytics.storage.EventEntity
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.RequestBody
import okio.BufferedSink
import okio.GzipSink
import okio.buffer
import java.util.Locale
import java.util.TimeZone

// Context扩展
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
fun Context.getDeviceInfo(): DeviceInfo {
    val displayMetrics = resources.displayMetrics
    val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    return DeviceInfo(
        deviceId = deviceId.toString(),
        osVersion = Build.VERSION.RELEASE,
        appVersion = getAppVersion(),
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        screenWidth = displayMetrics.widthPixels,
        screenHeight = displayMetrics.heightPixels,
        networkType = getNetworkType(),
        carrier = telephonyManager?.networkOperatorName,
        language = Locale.getDefault().language,
        timezone = TimeZone.getDefault().id
    )
}

fun getAppVersion(): String {
    return ""
}

fun Context.isNetworkAvailable(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } else {
        @Suppress("DEPRECATION")
        connectivityManager.activeNetworkInfo?.isConnected == true
    }
}

fun Context.getNetworkType(): String {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile"
            else -> "Unknown"
        }
    } else {
        @Suppress("DEPRECATION")
        when (connectivityManager.activeNetworkInfo?.type) {
            ConnectivityManager.TYPE_WIFI -> "WiFi"
            ConnectivityManager.TYPE_MOBILE -> "Mobile"
            else -> "Unknown"
        }
    }
}

// View扩展
fun View.getResourceName(): String? = try {
    if (id != View.NO_ID) {
        resources.getResourceEntryName(id)
    } else null
} catch (e: Exception) {
    null
}

fun View.getViewContent(): String? = when (this) {
    is TextView -> text?.toString()
    is Button -> text?.toString()
    is ImageView -> contentDescription?.toString()
    else -> null
}

fun View.getViewPosition(): Map<String, Int>? {
    val location = IntArray(2)
    getLocationOnScreen(location)
    return mapOf(
        "x" to location[0],
        "y" to location[1]
    )
}

// Map扩展
fun <K, V> Map<K, V?>.filterNotNullValues(): Map<K, V> {
    return mapNotNull { (key, value) ->
        value?.let { key to it }
    }.toMap()
}

// Any扩展 - 类型转换
fun Any.toJsonElement(): JsonElement = when (this) {
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(this.mapKeys { it.key.toString() }
        .mapValues { it.value?.toJsonElement() ?: JsonNull })
    is List<*> -> JsonArray(this.map { it?.toJsonElement() ?: JsonNull })
    else -> JsonPrimitive(toString())
}

// EventEntity扩展
fun EventEntity.toEvent(): Event {
    val json = Json { ignoreUnknownKeys = true }
    return Event(
        eventName = eventName,
        properties = json.decodeFromString(properties),
        timestamp = timestamp,
        sessionId = sessionId,
        userId = userId,
        deviceInfo = json.decodeFromString(deviceInfo),
        userProperties = json.decodeFromString(userProperties)
    )
}

fun Event.toEntity(): EventEntity {
    val json = Json { ignoreUnknownKeys = true }
    return EventEntity(
        eventName = eventName,
        properties = properties.toJsonString(),
        timestamp = timestamp,
        sessionId = sessionId,
        userId = userId,
        deviceInfo = json.encodeToString(DeviceInfo.serializer(), deviceInfo),
        userProperties = userProperties.toJsonString()
    )
}

// RequestBody扩展 - GZIP压缩
fun RequestBody.gzip(): RequestBody {
    return object : RequestBody() {
        override fun contentType() = this@gzip.contentType()

        override fun writeTo(sink: BufferedSink) {
            val gzipSink = GzipSink(sink)
            val bufferedSink = gzipSink.buffer()
            this@gzip.writeTo(bufferedSink)
            bufferedSink.close()
        }
    }
}

// 日志扩展
fun Any.logDebug(message: String) {
    if (BuildConfig.DEBUG) {
        Log.d("AnalyticsSDK", "${this::class.simpleName}: $message")
    }
}

fun Any.logError(message: String, throwable: Throwable? = null) {
    Log.e("AnalyticsSDK", "${this::class.simpleName}: $message", throwable)
}

fun Any.logWarning(message: String) {
    Log.w("AnalyticsSDK", "${this::class.simpleName}: $message")
}
fun Map<String, JsonElement>.toJsonString(): String {
    return JsonObject(this).toString()
}