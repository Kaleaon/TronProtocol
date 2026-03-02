package com.tronprotocol.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.tronprotocol.app.ConversationTurn
import com.tronprotocol.app.R

class ConversationTurnAdapter(
    private val onCopyClicked: (ConversationTurn) -> Unit
) : ListAdapter<ConversationTurn, ConversationTurnAdapter.ChatRowViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatRowViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layoutRes = if (viewType == VIEW_TYPE_USER) {
            R.layout.item_chat_message_user
        } else {
            R.layout.item_chat_message_assistant
        }
        return ChatRowViewHolder(inflater.inflate(layoutRes, parent, false), onCopyClicked)
    }

    override fun onBindViewHolder(holder: ChatRowViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).role.equals(USER_ROLE, ignoreCase = true)) {
            VIEW_TYPE_USER
        } else {
            VIEW_TYPE_ASSISTANT
        }
    }

    class ChatRowViewHolder(
        itemView: View,
        private val onCopyClicked: (ConversationTurn) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val chatMessageRow: View = itemView.findViewById(R.id.chatMessageRow)
        private val chatRoleText: TextView = itemView.findViewById(R.id.chatRoleText)
        private val chatMessageText: TextView = itemView.findViewById(R.id.chatMessageText)
        private val chatCopyButton: MaterialButton = itemView.findViewById(R.id.chatCopyButton)

        fun bind(turn: ConversationTurn) {
            chatRoleText.text = turn.role
            chatMessageText.text = turn.message
            chatMessageRow.contentDescription = itemView.context.getString(
                R.string.chat_row_content_description,
                turn.role,
                turn.message
            )
            chatCopyButton.contentDescription = itemView.context.getString(
                R.string.chat_copy_content_description,
                turn.role
            )
            chatCopyButton.setOnClickListener { onCopyClicked(turn) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ConversationTurn>() {
        override fun areItemsTheSame(oldItem: ConversationTurn, newItem: ConversationTurn): Boolean {
            return oldItem.role == newItem.role && oldItem.message == newItem.message
        }

        override fun areContentsTheSame(oldItem: ConversationTurn, newItem: ConversationTurn): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_ASSISTANT = 1
        private const val USER_ROLE = "You"
    }
}
