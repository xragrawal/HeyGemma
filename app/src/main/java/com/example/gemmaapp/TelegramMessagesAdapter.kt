package com.example.gemmaapp

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TelegramMessagesAdapter : ListAdapter<TelegramMessage, TelegramMessagesAdapter.VH>(DIFF) {

    companion object {
        private const val TYPE_INCOMING = 0
        private const val TYPE_OUTGOING = 1
        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        private val DIFF = object : DiffUtil.ItemCallback<TelegramMessage>() {
            override fun areItemsTheSame(a: TelegramMessage, b: TelegramMessage) = a.id == b.id
            override fun areContentsTheSame(a: TelegramMessage, b: TelegramMessage) = a == b
        }
    }

    inner class VH(parent: ViewGroup, viewType: Int) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(
            if (viewType == TYPE_OUTGOING) R.layout.item_message_outgoing
            else                           R.layout.item_message_incoming,
            parent, false
        )
    ) {
        val tvText:   TextView  = itemView.findViewById(R.id.tvText)
        val tvTime:   TextView  = itemView.findViewById(R.id.tvTime)
        val tvSender: TextView? = itemView.findViewById(R.id.tvSender)
    }

    override fun getItemViewType(pos: Int) =
        if (getItem(pos).isOutgoing) TYPE_OUTGOING else TYPE_INCOMING

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent, viewType)

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val msg = getItem(pos)
        holder.tvText.text   = msg.text
        holder.tvTime.text   = if (msg.timestamp > 0) timeFmt.format(Date(msg.timestamp)) else ""
        holder.tvSender?.text = msg.senderName
    }
}
