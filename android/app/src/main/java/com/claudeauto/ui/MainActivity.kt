package com.claudeauto.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.TransitionManager
import com.claudeauto.ClaudeAutoApp
import com.claudeauto.Conversation
import com.claudeauto.R
import com.claudeauto.Settings
import com.claudeauto.StoreState
import com.claudeauto.backend.TurnState
import com.claudeauto.databinding.ActivityMainBinding
import com.claudeauto.databinding.ItemConversationBinding
import com.claudeauto.databinding.SheetConversationsBinding
import com.claudeauto.voice.Listener
import com.claudeauto.voice.Speaker
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch

/**
 * Phone screen: pick a Claude Code conversation and talk to it. The same conversations show up
 * in Android Auto. Setup and pairing live in [SetupActivity].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as ClaudeAutoApp
    private lateinit var speaker: Speaker
    private lateinit var listener: Listener
    private val adapter = MessageAdapter()
    private var conversationKey: String? = null
    private var pulse: ValueAnimator? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!app.settings.isConfigured) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        speaker = Speaker(this)
        listener = Listener(this)

        conversationKey = intent.getStringExtra(EXTRA_CONVERSATION) ?: app.settings.currentConversation

        binding.messages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messages.adapter = adapter

        binding.conversationButton.setOnClickListener { pickConversation() }
        binding.toolbar.menu.findItem(R.id.speak).isChecked = app.settings.speakReplies
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.speak -> {
                    item.isChecked = !item.isChecked
                    app.settings.speakReplies = item.isChecked
                    if (!item.isChecked) speaker.stop()
                }
                R.id.refresh -> lifecycleScope.launch { app.store.refresh() }
                R.id.settings -> startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_CHANGE, true))
            }
            true
        }

        binding.inputLayout.setEndIconOnClickListener { sendTyped() }
        binding.input.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_SEND).also { if (it) sendTyped() }
        }
        binding.talk.setOnClickListener { if (pulse != null) stopListening() else talk() }
        binding.approve.setOnClickListener { answer(true) }
        binding.deny.setOnClickListener { answer(false) }
        binding.stop.setOnClickListener { stopTurn() }

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

        lifecycleScope.launch { app.store.refresh() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CONVERSATION)?.let {
            select(it)
            if (::binding.isInitialized) render(app.store.state.value)
        }
    }

    override fun onStart() {
        super.onStart()
        app.phoneScreenVisible = true
    }

    override fun onStop() {
        app.phoneScreenVisible = false
        if (::listener.isInitialized) listener.cancel()
        stopPulse()
        super.onStop()
    }

    override fun onDestroy() {
        if (::speaker.isInitialized) speaker.shutdown()
        super.onDestroy()
    }

    private fun render(state: StoreState) {
        val conversation = current(state)
        if (conversation != null && conversation.key != conversationKey) select(conversation.key)

        binding.conversationTitle.text = conversation?.title ?: getString(R.string.pick_session)
        val mode = when (app.settings.mode) {
            Settings.MODE_CLOUD -> getString(R.string.mode_cloud)
            Settings.MODE_PHONE -> getString(R.string.mode_phone)
            else -> getString(R.string.mode_computer)
        }
        binding.status.text = "$mode · ${state.status}"
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(
            if (state.connected) ContextCompat.getColor(this, R.color.success)
            else MaterialColors.getColor(binding.statusDot, com.google.android.material.R.attr.colorError),
        )

        val messages = conversation?.messages.orEmpty()
        val grew = messages.size > adapter.itemCount
        adapter.submitList(messages) {
            if (grew && messages.isNotEmpty()) binding.messages.smoothScrollToPosition(messages.lastIndex)
        }
        binding.empty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE

        val turn = conversation?.turn
        val working = turn?.isActive == true
        val approving = turn?.state == TurnState.AWAITING_APPROVAL
        if ((binding.working.visibility == View.VISIBLE) != working || (binding.approvalCard.visibility == View.VISIBLE) != approving) {
            TransitionManager.beginDelayedTransition(binding.root)
        }
        if (pulse == null) {
            binding.working.visibility = if (working) View.VISIBLE else View.GONE
            binding.progress.text = turn?.progress?.let { "$it…" }.orEmpty()
        }
        binding.approvalCard.visibility = if (approving) View.VISIBLE else View.GONE
        binding.approvalText.text = turn?.approval?.summary.orEmpty()
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

    private fun pickConversation() {
        val sheet = BottomSheetDialog(this)
        val content = SheetConversationsBinding.inflate(layoutInflater)
        fun fill(conversations: List<Conversation>) {
            while (content.list.childCount > 1) content.list.removeViewAt(1)
            for (conversation in conversations) {
                val row = ItemConversationBinding.inflate(layoutInflater, content.list, false)
                row.icon.setImageResource(if (conversation.isNew) R.drawable.ic_add else R.drawable.ic_claude)
                row.title.text = conversation.title
                row.subtitle.text = conversation.project.orEmpty()
                row.subtitle.visibility = if (conversation.project.isNullOrEmpty() || conversation.isNew) View.GONE else View.VISIBLE
                row.root.setOnClickListener {
                    app.store.forgetMove(conversation.key)
                    select(conversation.key)
                    render(app.store.state.value)
                    sheet.dismiss()
                }
                content.list.addView(row.root)
            }
        }
        fill(app.store.state.value.conversations)
        sheet.setContentView(content.root)
        sheet.show()
        lifecycleScope.launch {
            app.store.refresh()
            if (sheet.isShowing) fill(app.store.state.value.conversations)
        }
    }

    private fun sendTyped() {
        val text = binding.input.text?.toString().orEmpty()
        if (text.isBlank()) return
        binding.input.text?.clear()
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

    private fun stopTurn() {
        val key = current(app.store.state.value)?.key ?: return
        speaker.stop()
        lifecycleScope.launch { app.store.stop(key) }
    }

    private fun talk() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded()
            return
        }
        if (!listener.isAvailable) {
            showHint("No speech recognizer on this phone.")
            return
        }
        speaker.stop()
        startPulse()
        showHint(getString(R.string.listening))
        listener.listen(object : Listener.Callback {
            override fun onPartial(text: String) {
                showHint("$text…")
            }

            override fun onResult(text: String) {
                stopPulse()
                send(text)
            }

            override fun onError(message: String) {
                stopPulse()
                showHint(message)
            }
        })
    }

    private fun stopListening() {
        listener.cancel()
        stopPulse()
    }

    /** Shows what the mic heard (or why it didn't) in the progress row. */
    private fun showHint(text: String) {
        binding.working.visibility = View.VISIBLE
        binding.progress.text = text
    }

    private fun startPulse() {
        stopPulse()
        binding.talk.setImageResource(R.drawable.ic_stop)
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            binding.micHalo,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.35f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.35f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.45f, 0f),
        ).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        if (!::binding.isInitialized) return
        binding.micHalo.alpha = 0f
        binding.talk.setImageResource(R.drawable.ic_mic)
        render(app.store.state.value)
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
