package com.example.gemmaapp

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gemmaapp.databinding.ActivityTodosBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TodosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTodosBinding

    private val adapter = TodosAdapter(
        onToggle = { item, checked ->
            lifecycleScope.launch {
                if (checked) TodoRepository.markDoneById(item.id)
                else         TodoRepository.markUndoneById(item.id)
            }
        },
        onDelete = { item ->
            lifecycleScope.launch {
                TodoRepository.deleteById(item.id)
            }
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTodosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        TodoRepository.init(this)

        binding.rvTodos.layoutManager = LinearLayoutManager(this)
        binding.rvTodos.adapter = adapter

        lifecycleScope.launch {
            TodoRepository.todos.collectLatest { todos ->
                adapter.submitList(todos)
                binding.tvEmpty.visibility = if (todos.isEmpty()) View.VISIBLE else View.GONE
                binding.rvTodos.visibility = if (todos.isEmpty()) View.GONE  else View.VISIBLE
            }
        }
    }
}
