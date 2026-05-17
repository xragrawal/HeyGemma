package com.example.gemmaapp

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class TodosAdapter(
    private val onToggle: (TodoEntity, Boolean) -> Unit,
    private val onDelete: (TodoEntity) -> Unit
) : ListAdapter<TodoEntity, TodosAdapter.VH>(DIFF) {

    var freshId: String? = null

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TodoEntity>() {
            override fun areItemsTheSame(a: TodoEntity, b: TodoEntity) = a.id == b.id
            override fun areContentsTheSame(a: TodoEntity, b: TodoEntity) = a == b
        }
    }

    inner class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_todo, parent, false)
    ) {
        val cardInner: ViewGroup = itemView.findViewById(R.id.cardInner)
        val btnCheck: FrameLayout = itemView.findViewById(R.id.btnCheck)
        val ivCheck: ImageView = itemView.findViewById(R.id.ivCheck)
        val tvText: TextView = itemView.findViewById(R.id.tvTodoText)
        val tvJustAdded: TextView = itemView.findViewById(R.id.tvJustAdded)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val item = getItem(pos)
        val ctx = holder.itemView.context

        holder.tvText.text = item.text

        // Done state
        if (item.isDone) {
            holder.tvText.paintFlags = holder.tvText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            holder.tvText.alpha = 0.45f
            holder.cardInner.background = ContextCompat.getDrawable(ctx, R.drawable.bg_todo_card)
            // Lime circle checkbox
            holder.btnCheck.background = ContextCompat.getDrawable(ctx, R.drawable.bg_circle_lime)
            holder.ivCheck.visibility = View.VISIBLE
        } else {
            holder.tvText.paintFlags = holder.tvText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            holder.tvText.alpha = 1.0f
            // Un-done: card bg is fresh or normal
            val isFresh = freshId != null && item.id == freshId
            holder.cardInner.background = ContextCompat.getDrawable(
                ctx,
                if (isFresh) R.drawable.bg_todo_card_fresh else R.drawable.bg_todo_card
            )
            // Empty circle checkbox
            holder.btnCheck.background = ContextCompat.getDrawable(ctx, R.drawable.bg_process_step_pending)
            holder.ivCheck.visibility = View.GONE
        }

        // "JUST ADDED" badge
        val isFresh = freshId != null && item.id == freshId
        holder.tvJustAdded.visibility = if (isFresh && !item.isDone) View.VISIBLE else View.GONE

        // Toggle click
        holder.btnCheck.setOnClickListener {
            onToggle(item, !item.isDone)
        }
        holder.itemView.setOnClickListener {
            onToggle(item, !item.isDone)
        }
    }
}
