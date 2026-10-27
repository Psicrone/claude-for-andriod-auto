package com.claudeauto.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.claudeauto.ClaudeAutoApp
import kotlinx.coroutines.launch

/** Handles voice replies and mark-as-read from Android Auto message notifications. */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ClaudeAutoApp
        val key = intent.getStringExtra(EXTRA_CONVERSATION) ?: return

        when (intent.action) {
            ACTION_MARK_READ -> {
                app.store.markRead(key)
                app.notifier.cancel(key)
            }
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString() ?: return
                app.store.markRead(key)
                app.notifier.cancel(key)
                val pending = goAsync()
                app.appScope.launch {
                    try {
                        app.store.send(key, text)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.claudeauto.REPLY"
        const val ACTION_MARK_READ = "com.claudeauto.MARK_READ"
        const val EXTRA_CONVERSATION = "conversation"
        const val KEY_TEXT = "reply_text"
    }
}
