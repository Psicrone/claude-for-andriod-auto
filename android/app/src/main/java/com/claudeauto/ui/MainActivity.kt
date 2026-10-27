package com.claudeauto.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import com.claudeauto.Settings
import com.claudeauto.StoreState
import com.claudeauto.bridge.BridgeClient
import com.claudeauto.bridge.PinnedTls
import com.claudeauto.backend.TurnState
import com.claudeauto.databinding.ActivityMainBinding
import com.claudeauto.voice.Listener
import com.claudeauto.voice.Speaker
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Phone screen: connect to a bridge or the cloud, pick a Claude Code session and talk to it.
 * The same conversations show up in Android Auto.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as ClaudeAutoApp
    private lateinit var speaker: Speaker
    private lateinit var listener: Listener
    private var conversationKey: String? = null
    private var shownMode = Settings.MODE_BRIDGE

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        speaker = Speaker(this)
        listener = Listener(this)

        conversationKey = intent.getStringExtra(EXTRA_CONVERSATION) ?: app.settings.currentConversation
        binding.speakReplies.isChecked = app.settings.speakReplies
        binding.settingsPanel.visibility = if (app.settings.isConfigured) View.GONE else View.VISIBLE
        binding.title.setOnLongClickListener {
            binding.settingsPanel.visibility = View.VISIBLE
            true
        }

        binding.pair.setOnClickListener { pair() }
        binding.saveCloud.setOnClickListener { saveCloud() }
        binding.apiKey.setText(app.settings.apiKey)
        binding.githubToken.setText(app.settings.githubToken)
        binding.repos.setText(app.settings.repos.joinToString("\n"))
        binding.modeGroup.check(
            when (app.settings.mode) {
                Settings.MODE_CLOUD -> com.claudeauto.R.id.modeCloud
                Settings.MODE_PHONE -> com.claudeauto.R.id.modePhone
                else -> com.claudeauto.R.id.modeComputer
            },
        )
        showModePanel(app.settings.mode)
        binding.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) showModePanel(
                when (id) {
                    com.claudeauto.R.id.modeCloud -> Settings.MODE_CLOUD
                    com.claudeauto.R.id.modePhone -> Settings.MODE_PHONE
                    else -> Settings.MODE_BRIDGE
                },
            )
        }
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

    /**
     * Pairs this phone with the bridge: read the bridge's certificate, have the user confirm
     * it matches what the computer printed, pin it, then trade the one-time code for a
     * device token that only this phone holds.
     */
    private fun pair() {
        val url = binding.bridgeUrl.text.toString().trim()
        val code = binding.pairingCode.text.toString().trim()
        if (code.isEmpty()) {
            binding.status.text = "Enter the pairing code the bridge shows."
            return
        }
        binding.status.text = "Checking the bridge's certificate…"
        lifecycleScope.launch {
            val probe = runCatching { BridgeClient.probe(url) }.getOrElse {
                binding.status.text = "Can't reach the bridge: ${it.message}"
                return@launch
            }
            val pin = if (probe.systemTrusted) null else probe.fingerprint
            if (pin != null && !confirmCertificate(PinnedTls.short(pin))) {
                binding.status.text = "Pairing cancelled. The certificate didn't match, so nothing was sent."
                return@launch
            }
            val token = runCatching { BridgeClient.pair(url, pin, code, Build.MODEL ?: "Phone") }.getOrElse {
                binding.status.text = "Pairing failed: ${it.message}"
                return@launch
            }
            app.settings.mode = shownMode
            app.settings.bridgeUrl = url
            app.settings.certFingerprint = pin.orEmpty()
            app.settings.token = token
            binding.pairingCode.text?.clear()
            val loaded = app.store.refresh()
            binding.status.text = if (loaded.isSuccess) {
                "Paired" + (pin?.let { ". Certificate ${PinnedTls.short(it)} pinned." } ?: ".")
            } else {
                "Paired, but: ${loaded.exceptionOrNull()?.message}"
            }
            if (loaded.isSuccess) binding.settingsPanel.visibility = View.GONE
        }
    }

    /** Shows the settings for [mode]; the mode is only saved once pairing or "Save" succeeds. */
    private fun showModePanel(mode: String) {
        shownMode = mode
        val cloud = mode == Settings.MODE_CLOUD
        binding.bridgePanel.visibility = if (cloud) View.GONE else View.VISIBLE
        binding.cloudPanel.visibility = if (cloud) View.VISIBLE else View.GONE
        if (!cloud) {
            val phone = mode == Settings.MODE_PHONE
            binding.bridgeHelp.setText(if (phone) com.claudeauto.R.string.help_phone else com.claudeauto.R.string.help_computer)
            binding.bridgeUrl.setText(app.settings.bridgeUrlFor(mode))
        }
    }

    /** Cloud mode: everything runs on Anthropic-hosted agents, no computer needed. */
    private fun saveCloud() {
        val apiKey = binding.apiKey.text.toString().trim()
        if (!apiKey.startsWith("sk-ant-")) {
            binding.status.text = "That doesn't look like an Anthropic API key (it starts with sk-ant-)."
            return
        }
        app.settings.apiKey = apiKey
        app.settings.githubToken = binding.githubToken.text.toString()
        app.settings.repos = binding.repos.text.toString().split('\n', ',')
        app.settings.mode = Settings.MODE_CLOUD
        binding.status.text = "Connecting to Anthropic… the first time takes a few seconds to set up your cloud agent."
        lifecycleScope.launch {
            val loaded = app.store.refresh()
            binding.status.text = if (loaded.isSuccess) {
                "Cloud mode ready" + if (app.settings.repos.isEmpty()) " (no repos: Claude works in an empty sandbox)." else "."
            } else {
                "Couldn't set up cloud mode: ${loaded.exceptionOrNull()?.message}"
            }
            if (loaded.isSuccess) binding.settingsPanel.visibility = View.GONE
        }
    }

    private suspend fun confirmCertificate(short: String): Boolean = suspendCancellableCoroutine { cont ->
        AlertDialog.Builder(this)
            .setTitle("Check the certificate")
            .setMessage("The bridge printed \"Certificate: …\" when it started. This bridge's certificate is $short. Do they match?")
            .setPositiveButton("They match") { _, _ -> if (cont.isActive) cont.resume(true) }
            .setNegativeButton("No") { _, _ -> if (cont.isActive) cont.resume(false) }
            .setOnCancelListener { if (cont.isActive) cont.resume(false) }
            .show()
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
