package com.example.gemmaapp

import android.graphics.Color
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
        private const val TYPE_NOTE  = 2

        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a === b
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }
    }

    inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView        = view.findViewById(R.id.tvMessage)
        val cursor: View          = view.findViewById(R.id.vCursor)
        val agentBadge: TextView? = view.findViewById(R.id.tvAgentBadge)
    }

    override fun getItemViewType(pos: Int) = when {
        getItem(pos).isAgentNote -> TYPE_NOTE
        getItem(pos).isUser      -> TYPE_USER
        else                     -> TYPE_MODEL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = when (viewType) {
            TYPE_USER -> R.layout.item_message_user
            TYPE_NOTE -> R.layout.item_agent_note
            else      -> R.layout.item_message_model
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val msg = getItem(pos)
        holder.text.text = msg.text.ifEmpty { "▌" }
        holder.cursor.visibility = if (msg.isStreaming) View.VISIBLE else View.GONE

        holder.agentBadge?.apply {
            if (!msg.isUser && msg.agentType != null) {
                text = msg.agentType.label
                visibility = View.VISIBLE
                setTextColor(if (msg.agentType == AgentType.TODO) Color.parseColor("#6750A4") else Color.parseColor("#1A73E8"))
            } else {
                visibility = View.GONE
            }
        }
    }
}
