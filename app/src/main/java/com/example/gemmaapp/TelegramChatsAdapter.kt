package com.example.gemmaapp

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class TelegramChatsAdapter(
    private val onClick: (TelegramMessage) -> Unit
) : ListAdapter<TelegramMessage, TelegramChatsAdapter.VH>(DIFF) {

    var currentFilter: InboxFilter = InboxFilter.EMERGENCY

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TelegramMessage>() {
            override fun areItemsTheSame(a: TelegramMessage, b: TelegramMessage) = a.chatId == b.chatId
            override fun areContentsTheSame(a: TelegramMessage, b: TelegramMessage) = a == b
        }
        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())

        // Avatar colors derived from chat name hash
        private val AVATAR_COLORS = listOf(
            "#B8FF5E", "#F5B450", "#FF7A6B", "#7DD3FC", "#B794F4",
            "#9BE84A", "#F97316", "#22D3EE"
        )

        fun avatarColorForName(name: String): Int {
            val idx = abs(name.hashCode()) % AVATAR_COLORS.size
            return Color.parseColor(AVATAR_COLORS[idx])
        }

        fun initialsForName(name: String): String {
            return name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")
        }
    }

    inner class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_telegram_chat_preview, parent, false)
    ) {
        val rowRoot: LinearLayout = itemView.findViewById(R.id.chatRowRoot)
        val avatar: FrameLayout = itemView.findViewById(R.id.ivAvatar)
        val tvInitials: TextView = itemView.findViewById(R.id.tvAvatarInitials)
        val tvName: TextView = itemView.findViewById(R.id.tvName)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        val tvGemmaBadge: TextView = itemView.findViewById(R.id.tvGemmaBadge)
        val tvPreview: TextView = itemView.findViewById(R.id.tvLastMessage)
        val tvUnread: TextView = itemView.findViewById(R.id.tvUnreadBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val msg = getItem(pos)
        val ctx = holder.itemView.context

        holder.tvName.text = msg.chatName
        holder.tvTime.text = formatTime(msg.timestamp)
        holder.tvPreview.text = msg.text.take(60)

        // Avatar
        val color = avatarColorForName(msg.chatName)
        holder.avatar.setBackgroundColor(color)
        // Make circle
        holder.avatar.background = ContextCompat.getDrawable(ctx, R.drawable.bg_circle_lime)
        holder.avatar.background?.setTint(color)
        holder.tvInitials.text = initialsForName(msg.chatName)

        // GEMMA badge for outgoing
        if (msg.isOutgoing) {
            holder.tvGemmaBadge.visibility = View.VISIBLE
        } else {
            holder.tvGemmaBadge.visibility = View.GONE
        }

        // Unread badge (not tracked per chat in TelegramMessage; hide for now)
        holder.tvUnread.visibility = View.GONE

        // Visibility based on filter
        val isVisible = passesFilter(msg)
        holder.rowRoot.alpha = if (isVisible) 1.0f else 0.28f

        holder.itemView.setOnClickListener { onClick(msg) }
    }

    private fun passesFilter(msg: TelegramMessage): Boolean {
        return when (currentFilter) {
            InboxFilter.ALL -> true
            InboxFilter.IMPORTANT -> true  // treat all real chats as at least important
            InboxFilter.EMERGENCY -> {
                // Only show chats where sender name contains "Mom" or similar
                msg.senderName.contains("Mom", ignoreCase = true) ||
                msg.senderName.contains("Emergency", ignoreCase = true) ||
                msg.senderName.contains("Alert", ignoreCase = true)
            }
        }
    }

    private fun formatTime(ts: Long): String {
        if (ts == 0L) return ""
        val now = System.currentTimeMillis()
        val date = Date(ts)
        return if (now - ts < 24 * 60 * 60 * 1000L) timeFmt.format(date) else dateFmt.format(date)
    }
}
