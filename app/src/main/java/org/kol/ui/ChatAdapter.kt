package com.voiceassistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.voiceassistant.R
import com.voiceassistant.databinding.ItemChatBubbleBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.ChatViewHolder>(Diff) {
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

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
            params.gravity = if (message.isUser) android.view.Gravity.START else android.view.Gravity.END
            binding.bubbleRoot.layoutParams = params

            val displayMetrics = binding.root.resources.displayMetrics
            val screenWidthPx = displayMetrics.widthPixels
            val bubbleMaxWidthPx = (screenWidthPx * 0.70f).toInt()
            val bubbleLayoutParams = binding.bubbleRoot.layoutParams
            bubbleLayoutParams.width = bubbleMaxWidthPx
            binding.bubbleRoot.layoutParams = bubbleLayoutParams
            binding.tvMessage.maxWidth = bubbleMaxWidthPx - (binding.root.resources.displayMetrics.density * 32f).toInt()

            val context = binding.root.context
            val isUser = message.isUser
            binding.bubbleRoot.setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (isUser) R.color.kol_blue_100 else R.color.kol_surface_alt
                )
            )
            binding.bubbleRoot.strokeColor = ContextCompat.getColor(
                context,
                R.color.kol_line
            )
            binding.tvMessage.text = message.text
            binding.tvMessage.alpha = if (message.isStreaming) 0.85f else 1f
            binding.tvRole.text = if (isUser) "🧍" else "🤖"
            binding.tvTimestamp.text = timeFormat.format(Date(message.timestampMs))
            val alignment = if (isUser) View.TEXT_ALIGNMENT_TEXT_START else View.TEXT_ALIGNMENT_TEXT_END
            val gravity = if (isUser) android.view.Gravity.START else android.view.Gravity.END
            binding.tvRole.textAlignment = alignment
            binding.tvTimestamp.textAlignment = alignment
            binding.tvMessage.textAlignment = alignment
            (binding.tvRole.layoutParams as LinearLayout.LayoutParams).gravity = gravity
            (binding.tvTimestamp.layoutParams as LinearLayout.LayoutParams).gravity = gravity
            (binding.tvMessage.layoutParams as LinearLayout.LayoutParams).gravity = gravity
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
