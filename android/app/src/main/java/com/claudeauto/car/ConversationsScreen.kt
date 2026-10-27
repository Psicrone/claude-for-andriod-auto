package com.claudeauto.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.messaging.model.CarMessage
import androidx.car.app.messaging.model.ConversationCallback
import androidx.car.app.messaging.model.ConversationItem
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.claudeauto.ClaudeAutoApp
import com.claudeauto.Conversation
import com.claudeauto.R
import com.claudeauto.backend.TurnState
import kotlinx.coroutines.launch

/**
 * The whole in-car experience: one templated messaging list of Claude Code conversations.
 * Android Auto reads messages aloud when a conversation is tapped and takes dictated
 * replies, so the driver never has to read or type.
 */
class ConversationsScreen(carContext: CarContext) : Screen(carContext) {

    private val app = carContext.applicationContext as ClaudeAutoApp
    private var loaded = false

    private val self = Person.Builder().setName(carContext.getString(R.string.you)).setKey("self").build()
    private val claude = Person.Builder()
        .setName(carContext.getString(R.string.claude))
        .setKey("claude")
        .setBot(true)
        .setIcon(IconCompat.createWithResource(carContext, R.drawable.ic_claude))
        .build()
    private val claudeIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_claude))
        .setTint(androidx.car.app.model.CarColor.createCustom(0xFFD97757.toInt(), 0xFFD97757.toInt()))
        .build()

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                lifecycleScope.launch {
                    app.store.state.collect { invalidate() }
                }
                refresh()
            }
        })
    }

    override fun onGetTemplate(): Template {
        if (!app.settings.isConfigured) {
            return MessageTemplate.Builder("Open Claude Auto on your phone and set it up first.")
                .setTitle("Claude Code")
                .setHeaderAction(Action.APP_ICON)
                .addAction(Action.Builder().setTitle("Try again").setOnClickListener { refresh() }.build())
                .build()
        }

        val state = app.store.state.value
        if (!loaded) {
            return ListTemplate.Builder().setTitle("Claude Code").setHeaderAction(Action.APP_ICON).setLoading(true).build()
        }

        val items = ItemList.Builder().setNoItemsMessage(state.status)
        state.conversations.take(MAX_ITEMS).forEach { items.addItem(conversationItem(it)) }

        return ListTemplate.Builder()
            .setTitle(if (state.connected) "Claude Code" else "Claude Code · offline")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items.build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle("Refresh").setOnClickListener { refresh() }.build())
                    .build(),
            )
            .build()
    }

    private fun conversationItem(conversation: Conversation): ConversationItem {
        // Android Auto only offers a dictated reply after reading at least one unread message
        // from the other side; with none it says "no new messages" and stops. So Claude's latest
        // message always counts as unread here: tapping a conversation replays it, then asks for
        // your reply. A conversation with nothing from Claude yet gets a spoken prompt instead.
        val recent = conversation.messages.takeLast(MAX_MESSAGES)
        val lastFromClaude = recent.indexOfLast { it.fromClaude }
        val messages = recent.mapIndexed { index, message ->
            CarMessage.Builder()
                .setSender(if (message.fromClaude) claude else self)
                .setBody(CarText.create(message.text))
                .setReceivedTimeEpochMillis(message.time)
                .setRead(message.read && index != lastFromClaude)
                .build()
        }.toMutableList()
        if (lastFromClaude < 0) {
            val prompt = when {
                conversation.turn?.isActive == true -> "Claude is still working on it. Reply to add something, or say stop."
                conversation.isNew -> "Reply to start a new Claude Code session in ${conversation.project}."
                else -> "Reply to continue: ${conversation.title}."
            }
            messages += CarMessage.Builder()
                .setSender(claude)
                .setBody(CarText.create(prompt))
                .setReceivedTimeEpochMillis(maxOf(conversation.lastActivity, recent.lastOrNull()?.time ?: 0L).takeIf { it > 0 } ?: System.currentTimeMillis())
                .setRead(false)
                .build()
        }

        val title = buildString {
            append(conversation.title.take(48))
            when (conversation.turn?.state) {
                TurnState.RUNNING -> append(" · working")
                TurnState.AWAITING_APPROVAL -> append(" · needs your OK")
                else -> Unit
            }
        }

        return ConversationItem.Builder(
            conversation.key,
            CarText.create(title),
            self,
            messages,
            object : ConversationCallback {
                override fun onMarkAsRead() {
                    app.store.markRead(conversation.key)
                    app.notifier.cancel(conversation.key)
                }

                override fun onTextReply(replyText: String) {
                    app.store.markRead(conversation.key)
                    app.notifier.cancel(conversation.key)
                    lifecycleScope.launch { app.store.send(conversation.key, replyText) }
                }
            },
        )
            .setIcon(claudeIcon)
            .build()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val result = app.store.refresh()
            loaded = true
            if (result.isFailure) {
                CarToast.makeText(carContext, "Can't reach your computer", CarToast.LENGTH_LONG).show()
            }
            invalidate()
        }
    }

    companion object {
        private const val MAX_ITEMS = 6
        private const val MAX_MESSAGES = 10
    }
}
