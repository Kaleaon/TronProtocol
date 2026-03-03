package com.tronprotocol.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.tronprotocol.app.data.dao.ChunkDao
import com.tronprotocol.app.data.dao.TelemetryDao
import com.tronprotocol.app.data.entity.ChunkEntity
import com.tronprotocol.app.data.entity.ChunkFts
import com.tronprotocol.app.data.entity.TelemetryEntity

/**
 * Room database for TronProtocol structured persistence.
 *
 * Replaces file-based storage (JSON in SecureStorage, JSONL telemetry files)
 * with a proper relational database providing:
 * - FTS4 full-text search over chunk content
 * - SQL aggregation for telemetry analytics
 * - Transactional guarantees for data integrity
 * - Indexed queries for fast lookup
 */
@Database(
    entities = [
        ChunkEntity::class,
        ChunkFts::class,
        TelemetryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TronDatabase : RoomDatabase() {

    abstract fun chunkDao(): ChunkDao
    abstract fun telemetryDao(): TelemetryDao

    companion object {
        private const val DATABASE_NAME = "tron_protocol.db"

        @Volatile
        private var instance: TronDatabase? = null

        fun getInstance(context: Context): TronDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }

        private fun buildDatabase(context: Context): TronDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                TronDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration()
                .build()

        /**
         * Create an in-memory database for testing.
         */
        fun createInMemory(context: Context): TronDatabase =
            Room.inMemoryDatabaseBuilder(context, TronDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
