package com.hw.sdk.analytics.storage

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object Converters {
    // Map<String, String> <-> Json String
    @TypeConverter
    @JvmStatic
    fun fromMap(value: Map<String, String>?): String {
        return if (value == null) "{}" else Json.encodeToString(value)
    }

    @TypeConverter
    @JvmStatic
    fun toMap(value: String?): Map<String, String> {
        return if (value.isNullOrEmpty()) emptyMap() else Json.decodeFromString(value)
    }

    // JsonObject <-> String
    @TypeConverter
    @JvmStatic
    fun fromJsonObject(obj: JsonObject?): String {
        return obj?.toString() ?: "{}"
    }

    @TypeConverter
    @JvmStatic
    fun toJsonObject(str: String?): JsonObject {
        return if (str.isNullOrEmpty()) JsonObject(mapOf()) else Json.parseToJsonElement(str).jsonObject
    }

    // Boolean <-> Int
    @TypeConverter
    @JvmStatic
    fun fromBoolean(value: Boolean): Int = if (value) 1 else 0

    @TypeConverter
    @JvmStatic
    fun toBoolean(value: Int): Boolean = value == 1
} 