package com.claudeauto.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.claudeauto.Conversation
import com.claudeauto.R
import com.claudeauto.ui.MainActivity

/**
 * Posts Claude's replies as MessagingStyle notifications. Android Auto announces these,
 * reads them aloud on request, and lets the driver answer by voice through the
 * Reply action, all without touching the phone.
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)
    private val claude = Person.Builder()
        .setName(context.getString(R.string.claude))
        .setKey("claude")
        .setBot(true)
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_claude))
        .build()
    private val self = Person.Builder().setName(context.getString(R.string.you)).setKey("self").build()

    init {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_replies), NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(conversation: Conversation) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val unread = conversation.messages.takeLast(8)
        if (unread.none { it.fromClaude && !it.read }) return

        val style = NotificationCompat.MessagingStyle(self)
            .setConversationTitle(conversation.title)
            .setGroupConversation(false)
        unread.forEach { message ->
            style.addMessage(message.text, message.time, if (message.fromClaude) claude else null)
        }

        val id = notificationId(conversation.key)
        val reply = NotificationCompat.Action.Builder(
            R.drawable.ic_mic,
            context.getString(R.string.reply),
            actionIntent(ReplyReceiver.ACTION_REPLY, conversation.key, id, mutable = true),
        )
            .addRemoteInput(RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel(context.getString(R.string.reply)).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val markRead = NotificationCompat.Action.Builder(
            R.drawable.ic_claude,
            context.getString(R.string.mark_read),
            actionIntent(ReplyReceiver.ACTION_MARK_READ, conversation.key, id, mutable = false),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CONVERSATION, conversation.key)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_claude)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .addAction(reply)
            .addInvisibleAction(markRead)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        manager.notify(id, notification)
    }

    fun cancel(conversationKey: String) = manager.cancel(notificationId(conversationKey))

    private fun actionIntent(action: String, key: String, id: Int, mutable: Boolean): PendingIntent {
        val intent = Intent(context, ReplyReceiver::class.java)
            .setAction(action)
            .putExtra(ReplyReceiver.EXTRA_CONVERSATION, key)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, id * 2 + if (mutable) 0 else 1, intent, flags)
    }

    private fun notificationId(key: String) = key.hashCode() and 0x3fffffff

    companion object {
        const val CHANNEL = "claude_replies"
    }
}
