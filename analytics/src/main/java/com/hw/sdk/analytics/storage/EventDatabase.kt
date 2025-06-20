package com.hw.sdk.analytics.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

@Database(
    entities = [EventEntity::class],
    version = 1,
    exportSchema = false
)

@TypeConverters(Converters::class)
abstract class EventDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var INSTANCE: EventDatabase? = null

        fun getInstance(context: Context): EventDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EventDatabase::class.java,
                    "analytics_database"
                ).apply {
                    setJournalMode(RoomDatabase.JournalMode.WAL) // 启用WAL模式
                    setQueryExecutor(Dispatchers.IO.asExecutor())
                }.build().also { INSTANCE = it }
            }
        }
    }
}