package com.example.gemmaapp

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TelegramContactDao {

    @Query("SELECT * FROM telegram_contacts ORDER BY name ASC")
    fun observeAll(): Flow<List<TelegramContactEntity>>

    @Query("SELECT * FROM telegram_contacts ORDER BY name ASC")
    suspend fun getAll(): List<TelegramContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(contacts: List<TelegramContactEntity>)
}
