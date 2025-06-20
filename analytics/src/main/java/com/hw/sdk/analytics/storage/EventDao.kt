package com.hw.sdk.analytics.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE uploaded = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getUnuploadedEvents(limit: Int): List<EventEntity>

    @Insert
    suspend fun insertEvent(event: EventEntity): Long

    @Insert
    suspend fun insertEvents(events: List<EventEntity>): List<Long>

    @Query("UPDATE events SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markAsUploaded(ids: List<Long>)

    @Query("UPDATE events SET retry_count = retry_count + 1 WHERE id IN (:ids)")
    suspend fun incrementRetryCount(ids: List<Long>)

    @Query("DELETE FROM events WHERE uploaded = 1 AND timestamp < :timestamp")
    suspend fun deleteOldUploadedEvents(timestamp: Long)

    @Query("DELETE FROM events WHERE retry_count >= 3 AND timestamp < :timestamp")
    suspend fun deleteFailedEvents(timestamp: Long)

    @Query("SELECT COUNT(*) FROM events WHERE uploaded = 0")
    suspend fun getUnuploadedCount(): Int
}