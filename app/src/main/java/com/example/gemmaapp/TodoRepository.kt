package com.example.gemmaapp

import android.content.Context
import kotlinx.coroutines.flow.Flow

object TodoRepository {

    private lateinit var dao: TodoDao

    fun init(context: Context) {
        dao = TodoDatabase.getInstance(context).todoDao()
    }

    // Observed by the ViewModel for live UI updates
    val todos: Flow<List<TodoEntity>> get() = dao.observeAll()

    // ── CRUD operations (called from AgentOrchestrator) ───────────────────────

    suspend fun add(text: String) {
        dao.insert(TodoEntity(text = text.trim()))
    }

    // Position is 1-based (as Gemma references items by number in conversation)
    suspend fun markDone(oneBased: Int) {
        resolveId(oneBased)?.let { dao.markDone(it) }
    }

    suspend fun markUndone(oneBased: Int) {
        resolveId(oneBased)?.let { dao.markUndone(it) }
    }

    suspend fun delete(oneBased: Int) {
        resolveId(oneBased)?.let { dao.deleteById(it) }
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }

    suspend fun getAll(): List<TodoEntity> = dao.getAll()

    // Direct ID-based operations used by the TodosActivity UI
    suspend fun markDoneById(id: String)   = dao.markDone(id)
    suspend fun markUndoneById(id: String) = dao.markUndone(id)
    suspend fun deleteById(id: String)     = dao.deleteById(id)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun resolveId(oneBased: Int): String? {
        val list = dao.getAll()
        val idx  = oneBased - 1
        return if (idx in list.indices) list[idx].id else null
    }
}
