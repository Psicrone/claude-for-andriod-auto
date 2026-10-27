package com.claudeauto.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.claudeauto.ChatMessage
import com.claudeauto.R

/** Chat bubbles: Claude on the left, you on the right. */
class MessageAdapter : ListAdapter<ChatMessage, MessageAdapter.Holder>(Diff) {

    class Holder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.text)
    }

    override fun getItemViewType(position: Int) = if (getItem(position).fromClaude) CLAUDE else USER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (viewType == CLAUDE) R.layout.item_message_claude else R.layout.item_message_user
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.text.text = getItem(position).text
    }

    private object Diff : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(old: ChatMessage, new: ChatMessage) = old.id == new.id
        override fun areContentsTheSame(old: ChatMessage, new: ChatMessage) = old.text == new.text && old.fromClaude == new.fromClaude
    }

    private companion object {
        const val CLAUDE = 1
        const val USER = 2
    }
}
