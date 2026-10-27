package com.claudeauto

import android.app.Application
import com.claudeauto.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ClaudeAutoApp : Application() {

    /** Lives as long as the process, so turns keep polling while screens come and go. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settings: Settings
        private set
    lateinit var store: ConversationStore
        private set
    lateinit var notifier: Notifier
        private set

    /** Set while the phone screen is in the foreground; it speaks replies itself. */
    @Volatile
    var phoneScreenVisible = false

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        store = ConversationStore(settings, appScope)
        notifier = Notifier(this)

        appScope.launch {
            store.claudeSaid.collect { said ->
                if (!phoneScreenVisible) notifier.show(said.conversation)
            }
        }
    }
}
