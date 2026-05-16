package com.example.gemmaapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.VH>(DIFF) {

    companion object {
        private const val TYPE_USER  = 0
        private const val TYPE_MODEL = 1

        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a === b
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }
    }

    inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.tvMessage)
        val cursor: View   = view.findViewById(R.id.vCursor)
    }

    override fun getItemViewType(pos: Int) =
        if (getItem(pos).isUser) TYPE_USER else TYPE_MODEL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == TYPE_USER) R.layout.item_message_user
                     else                       R.layout.item_message_model
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val msg = getItem(pos)
        holder.text.text = msg.text.ifEmpty { "▌" }
        holder.cursor.visibility = if (msg.isStreaming) View.VISIBLE else View.GONE
    }
}
