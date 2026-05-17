package com.example.gemmaapp

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TelegramMessage::class, TelegramContactEntity::class], version = 2, exportSchema = false)
abstract class TelegramDatabase : RoomDatabase() {

    abstract fun messageDao(): TelegramMessageDao
    abstract fun contactDao(): TelegramContactDao

    companion object {
        @Volatile private var INSTANCE: TelegramDatabase? = null

        fun getInstance(context: Context): TelegramDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TelegramDatabase::class.java,
                    "telegram_db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
