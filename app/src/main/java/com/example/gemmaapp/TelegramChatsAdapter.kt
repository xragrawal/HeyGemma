package com.example.gemmaapp

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TelegramChatsAdapter(
    private val onClick: (TelegramMessage) -> Unit
) : ListAdapter<TelegramMessage, TelegramChatsAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TelegramMessage>() {
            override fun areItemsTheSame(a: TelegramMessage, b: TelegramMessage) = a.chatId == b.chatId
            override fun areContentsTheSame(a: TelegramMessage, b: TelegramMessage) = a == b
        }
        private val timeFmt  = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val dateFmt  = SimpleDateFormat("MMM d", Locale.getDefault())
    }

    inner class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_telegram_chat_preview, parent, false)
    ) {
        val avatar: ImageView = itemView.findViewById(R.id.ivAvatar)
        val name: TextView    = itemView.findViewById(R.id.tvName)
        val preview: TextView = itemView.findViewById(R.id.tvLastMessage)
        val time: TextView    = itemView.findViewById(R.id.tvTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val msg = getItem(pos)
        holder.name.text    = msg.chatName
        holder.preview.text = if (msg.isOutgoing) "You: ${msg.text}" else msg.text
        holder.time.text    = formatTime(msg.timestamp)
        holder.avatar.setImageResource(android.R.drawable.ic_menu_myplaces)
        holder.itemView.setOnClickListener { onClick(msg) }
    }

    private fun formatTime(ts: Long): String {
        if (ts == 0L) return ""
        val now = System.currentTimeMillis()
        val date = Date(ts)
        return if (now - ts < 24 * 60 * 60 * 1000L) timeFmt.format(date) else dateFmt.format(date)
    }
}
