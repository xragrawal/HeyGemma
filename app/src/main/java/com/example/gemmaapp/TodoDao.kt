package com.example.gemmaapp

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {

    @Query("SELECT * FROM todos ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos ORDER BY createdAt ASC")
    suspend fun getAll(): List<TodoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(todo: TodoEntity)

    @Update
    suspend fun update(todo: TodoEntity)

    @Query("UPDATE todos SET isDone = 1 WHERE id = :id")
    suspend fun markDone(id: String)

    @Query("UPDATE todos SET isDone = 0 WHERE id = :id")
    suspend fun markUndone(id: String)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM todos")
    suspend fun deleteAll()
}
