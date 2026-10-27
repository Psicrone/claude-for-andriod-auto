package com.claudeauto.car

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.claudeauto.ClaudeAutoApp
import com.claudeauto.R
import kotlinx.coroutines.launch

/**
 * Shown while the driver dictates a new request from the car's Talk button: listens on the
 * car's microphone, then sends what was said to [conversationKey] and goes back to the list.
 */
class ListeningScreen(
    carContext: CarContext,
    private val conversationKey: String,
    private val target: String,
) : Screen(carContext) {

    private val app = carContext.applicationContext as ClaudeAutoApp
    private val dictation = CarDictation(carContext)
    private var heard = ""

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = listen()
            override fun onDestroy(owner: LifecycleOwner) = dictation.cancel()
        })
    }

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(if (heard.isEmpty()) "Listening… say what you want Claude to do." else "$heard…")
            .setTitle(target)
            .setHeaderAction(Action.BACK)
            .setIcon(
                CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_mic))
                    .setTint(CarColor.createCustom(0xFFD97757.toInt(), 0xFFD97757.toInt()))
                    .build(),
            )
            .addAction(Action.Builder().setTitle("Send").setOnClickListener { dictation.finish() }.build())
            .addAction(Action.Builder().setTitle("Cancel").setOnClickListener { screenManager.pop() }.build())
            .build()

    private fun listen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !dictation.isSupported) {
            CarToast.makeText(carContext, "Talk needs Android 13 or newer. Tap a conversation to reply.", CarToast.LENGTH_LONG).show()
            screenManager.pop()
            return
        }
        if (carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
                if (granted.isNotEmpty()) listen() else screenManager.pop()
            }
            return
        }
        dictation.start(object : CarDictation.Callback {
            override fun onPartial(text: String) {
                heard = text
                invalidate()
            }

            override fun onResult(text: String) {
                app.settings.currentConversation = conversationKey
                app.store.markRead(conversationKey)
                // The app scope, not this screen's: it closes as soon as the request is on its way.
                app.appScope.launch { app.store.send(conversationKey, text) }
                CarToast.makeText(carContext, "Sent to Claude: $text", CarToast.LENGTH_SHORT).show()
                screenManager.pop()
            }

            override fun onError(message: String) {
                CarToast.makeText(carContext, message, CarToast.LENGTH_LONG).show()
                screenManager.pop()
            }
        })
    }
}
