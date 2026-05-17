package com.example.gemmaapp

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class TelegramContactsAdapter : ListAdapter<TelegramContact, TelegramContactsAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TelegramContact>() {
            override fun areItemsTheSame(a: TelegramContact, b: TelegramContact) = a.chatId == b.chatId
            override fun areContentsTheSame(a: TelegramContact, b: TelegramContact) = a == b
        }
    }

    inner class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_telegram_contact, parent, false)
    ) {
        val icon: ImageView = itemView.findViewById(R.id.ivContactIcon)
        val name: TextView  = itemView.findViewById(R.id.tvContactName)
        val type: TextView  = itemView.findViewById(R.id.tvContactType)
        val id: TextView    = itemView.findViewById(R.id.tvChatId)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val c = getItem(pos)
        holder.name.text = c.name
        holder.type.text = c.displayType
        holder.id.text   = c.chatId.toString()
        holder.icon.setImageResource(
            if (c.type == "private") android.R.drawable.ic_menu_myplaces
            else                     android.R.drawable.ic_menu_compass
        )
    }
}
