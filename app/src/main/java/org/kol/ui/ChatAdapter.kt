package com.voiceassistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.voiceassistant.R
import com.voiceassistant.databinding.ItemChatBubbleBinding

class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.ChatViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatBubbleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChatViewHolder(
        private val binding: ItemChatBubbleBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            val params = binding.bubbleRoot.layoutParams as FrameLayout.LayoutParams
            params.gravity = if (message.isUser) android.view.Gravity.END else android.view.Gravity.START
            binding.bubbleRoot.layoutParams = params

            val context = binding.root.context
            binding.bubbleRoot.setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (message.isUser) R.color.kol_blue_100 else R.color.kol_surface_alt
                )
            )
            binding.bubbleRoot.strokeColor = ContextCompat.getColor(
                context,
                R.color.kol_line
            )
            binding.tvMessage.text = message.text
            binding.tvMessage.alpha = if (message.isStreaming) 0.85f else 1f
            binding.tvTimestamp.text = if (message.isUser) "You" else "Kol"
            binding.tvTimestamp.textAlignment = if (message.isUser) View.TEXT_ALIGNMENT_TEXT_END else View.TEXT_ALIGNMENT_TEXT_START
        }
    }

    private object Diff : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}
