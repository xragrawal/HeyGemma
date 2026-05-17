package com.example.gemmaapp

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class TodosAdapter(
    private val onToggle: (TodoEntity, Boolean) -> Unit,
    private val onDelete: (TodoEntity) -> Unit
) : ListAdapter<TodoEntity, TodosAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TodoEntity>() {
            override fun areItemsTheSame(a: TodoEntity, b: TodoEntity) = a.id == b.id
            override fun areContentsTheSame(a: TodoEntity, b: TodoEntity) = a == b
        }
    }

    inner class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_todo, parent, false)
    ) {
        val cb: CheckBox        = itemView.findViewById(R.id.cbDone)
        val tv: TextView        = itemView.findViewById(R.id.tvTodoText)
        val btnDelete: MaterialButton = itemView.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val item = getItem(pos)

        holder.tv.text = item.text
        if (item.isDone) {
            holder.tv.paintFlags = holder.tv.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            holder.tv.alpha = 0.5f
        } else {
            holder.tv.paintFlags = holder.tv.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            holder.tv.alpha = 1.0f
        }

        // Prevent the listener from firing during rebind
        holder.cb.setOnCheckedChangeListener(null)
        holder.cb.isChecked = item.isDone
        holder.cb.setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }

        holder.btnDelete.setOnClickListener { onDelete(item) }
    }
}
