package com.claudeauto.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.claudeauto.ClaudeAutoApp
import com.claudeauto.Conversation
import com.claudeauto.StoreState
import com.claudeauto.bridge.BridgeClient
import com.claudeauto.bridge.TurnState
import com.claudeauto.databinding.ActivityMainBinding
import com.claudeauto.voice.Listener
import com.claudeauto.voice.Speaker
import kotlinx.coroutines.launch

/**
 * Phone screen: connect to the bridge, pick a Claude Code session and talk to it.
 * The same conversations show up in Android Auto.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as ClaudeAutoApp
    private lateinit var speaker: Speaker
    private lateinit var listener: Listener
    private var conversationKey: String? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        speaker = Speaker(this)
        listener = Listener(this)

        conversationKey = intent.getStringExtra(EXTRA_CONVERSATION) ?: app.settings.currentConversation
        binding.bridgeUrl.setText(app.settings.bridgeUrl)
        binding.token.setText(app.settings.token)
        binding.speakReplies.isChecked = app.settings.speakReplies
        binding.settingsPanel.visibility = if (app.settings.isConfigured) View.GONE else View.VISIBLE
        binding.title.setOnLongClickListener {
            binding.settingsPanel.visibility = View.VISIBLE
            true
        }

        binding.save.setOnClickListener { saveAndTest() }
        binding.pickSession.setOnClickListener { pickConversation() }
        binding.speakReplies.setOnCheckedChangeListener { _, checked ->
            app.settings.speakReplies = checked
            if (!checked) speaker.stop()
        }
        binding.send.setOnClickListener { sendTyped() }
        binding.input.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_SEND).also { if (it) sendTyped() }
        }
        binding.talk.setOnClickListener { talk() }
        binding.approve.setOnClickListener { answer(true) }
        binding.deny.setOnClickListener { answer(false) }

        requestPermissionsIfNeeded()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.store.state.collect(::render) }
                launch {
                    app.store.claudeSaid.collect { said ->
                        if (said.conversation.key != current(app.store.state.value)?.key) {
                            app.notifier.show(said.conversation)
                            return@collect
                        }
                        app.store.markRead(said.conversation.key)
                        if (app.settings.speakReplies) {
                            val wantsAnswer = said.conversation.turn?.state == TurnState.AWAITING_APPROVAL
                            speaker.speak(said.message.text) {
                                if (wantsAnswer) runOnUiThread { talk() }
                            }
                        }
                    }
                }
            }
        }

        if (app.settings.isConfigured) lifecycleScope.launch { app.store.refresh() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CONVERSATION)?.let { select(it) }
    }

    override fun onStart() {
        super.onStart()
        app.phoneScreenVisible = true
    }

    override fun onStop() {
        app.phoneScreenVisible = false
        listener.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }

    private fun render(state: StoreState) {
        binding.status.text = state.status
        val conversation = current(state)
        if (conversation != null && conversation.key != conversationKey) select(conversation.key)
        binding.pickSession.text = conversation?.title ?: getString(com.claudeauto.R.string.pick_session)

        binding.transcript.text = conversation?.messages?.joinToString("\n\n") {
            (if (it.fromClaude) "Claude: " else "You: ") + it.text
        }.orEmpty()
        binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }

        val turn = conversation?.turn
        binding.progress.text = if (turn?.isActive == true) turn.progress + "…" else ""
        binding.approvalPanel.visibility = if (turn?.state == TurnState.AWAITING_APPROVAL) View.VISIBLE else View.GONE
    }

    /** The selected conversation, falling back to the first one (usually "New chat in <project>"). */
    private fun current(state: StoreState): Conversation? {
        val key = conversationKey?.let(app.store::resolve)
        return state.conversations.find { it.key == key } ?: state.conversations.firstOrNull()
    }

    private fun select(key: String) {
        conversationKey = key
        app.settings.currentConversation = key
        app.store.markRead(key)
        app.notifier.cancel(key)
    }

    private fun saveAndTest() {
        app.settings.bridgeUrl = binding.bridgeUrl.text.toString()
        app.settings.token = binding.token.text.toString()
        binding.status.text = "Connecting…"
        lifecycleScope.launch {
            val version = runCatching { BridgeClient(app.settings.bridgeUrl, app.settings.token).health() }
            val loaded = app.store.refresh()
            binding.status.text = when {
                version.isFailure -> "Can't reach the bridge: ${version.exceptionOrNull()?.message}"
                loaded.isFailure -> "Bridge found, but: ${loaded.exceptionOrNull()?.message}"
                else -> "Connected to bridge ${version.getOrNull()}"
            }
            if (loaded.isSuccess) binding.settingsPanel.visibility = View.GONE
        }
    }

    private fun pickConversation() {
        lifecycleScope.launch {
            app.store.refresh()
            val conversations = app.store.state.value.conversations
            if (conversations.isEmpty()) return@launch
            AlertDialog.Builder(this@MainActivity)
                .setTitle(com.claudeauto.R.string.pick_session)
                .setItems(conversations.map { it.title }.toTypedArray()) { _, which ->
                    app.store.forgetMove(conversations[which].key)
                    select(conversations[which].key)
                }
                .show()
        }
    }

    private fun sendTyped() {
        val text = binding.input.text.toString()
        if (text.isBlank()) return
        binding.input.text.clear()
        send(text)
    }

    private fun send(text: String) {
        val key = current(app.store.state.value)?.key ?: return
        speaker.stop()
        lifecycleScope.launch { app.store.send(key, text) }
    }

    private fun answer(approve: Boolean) {
        val key = current(app.store.state.value)?.key ?: return
        speaker.stop()
        lifecycleScope.launch { app.store.answer(key, approve) }
    }

    private fun talk() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded()
            return
        }
        if (!listener.isAvailable) {
            binding.progress.text = "No speech recognizer on this phone."
            return
        }
        speaker.stop()
        binding.progress.text = "Listening…"
        listener.listen(object : Listener.Callback {
            override fun onPartial(text: String) {
                binding.progress.text = "$text…"
            }

            override fun onResult(text: String) {
                binding.progress.text = ""
                send(text)
            }

            override fun onError(message: String) {
                binding.progress.text = message
            }
        })
    }

    private fun requestPermissionsIfNeeded() {
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    companion object {
        const val EXTRA_CONVERSATION = "conversation"
    }
}
