package com.rrlogger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Session::class, RrRecord::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun rrRecordDao(): RrRecordDao

    companion object {
        private const val DB_NAME = "rr_logger.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    // 服务崩溃后靠未结束会话恢复，不做破坏性迁移
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            // 每条 RR 独立事务写入，关闭 WAL 同步策略的隐式延迟
                            db.execSQL("PRAGMA synchronous = FULL")
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
