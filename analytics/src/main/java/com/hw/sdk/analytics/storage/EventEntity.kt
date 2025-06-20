package com.hw.sdk.analytics.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "event_name")
    val eventName: String,

    @ColumnInfo(name = "properties")
    val properties: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "user_id")
    val userId: String?,

    @ColumnInfo(name = "device_info")
    val deviceInfo: String,

    @ColumnInfo(name = "user_properties")
    val userProperties: String = "{}",

    @ColumnInfo(name = "uploaded")
    val uploaded: Boolean = false,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0
)
