package com.claudeauto.car

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Dictation from the car's microphone, for the Talk button on the car screen.
 *
 * Templated car apps must record through [CarAudioRecord], which hands over the car mic's
 * 16 kHz mono PCM. That stream is piped into the phone's speech recognizer
 * ([RecognizerIntent.EXTRA_AUDIO_SOURCE], Android 13+). Callbacks arrive on the main thread.
 */
class CarDictation(private val carContext: CarContext) {

    interface Callback {
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var record: CarAudioRecord? = null
    private var focus: AudioFocusRequest? = null
    private var source: ParcelFileDescriptor? = null
    @Volatile private var recording = false

    val isSupported
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            carContext.carAppApiLevel >= 5 &&
            SpeechRecognizer.isRecognitionAvailable(carContext)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(callback: Callback) {
        cancel()
        val (source, sink) = ParcelFileDescriptor.createPipe()
        this.source = source

        val audio = carContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
            .also { audio.requestAudioFocus(it) }

        val mic = CarAudioRecord.create(carContext)
        record = mic
        mic.startRecording()
        recording = true
        thread(name = "car-mic", isDaemon = true) {
            val buffer = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
            val started = System.currentTimeMillis()
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out ->
                    while (recording && System.currentTimeMillis() - started < MAX_RECORDING_MS) {
                        val read = mic.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        if (read > 0) out.write(buffer, 0, read)
                    }
                }
            } catch (_: IOException) {
                // The recognizer closed its end; it has what it needs.
            } finally {
                runCatching { mic.stopRecording() }
            }
        }

        val sr = SpeechRecognizer.createSpeechRecognizer(carContext)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                recording = false
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.best()?.let(callback::onPartial)
            }

            override fun onResults(results: Bundle?) {
                val text = results.best()
                cancel()
                if (text.isNullOrBlank()) callback.onError("I didn't catch that.") else callback.onResult(text)
            }

            override fun onError(error: Int) {
                cancel()
                callback.onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection."
                        else -> "Speech recognition failed ($error). Tap a conversation to reply instead."
                    },
                )
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE)
        sr.startListening(intent)
    }

    /** Stops recording; the recognizer then finishes with what it heard. */
    fun finish() {
        recording = false
        recognizer?.stopListening()
    }

    fun cancel() {
        recording = false
        recognizer?.destroy()
        recognizer = null
        record = null
        runCatching { source?.close() }
        source = null
        focus?.let { (carContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun Bundle?.best(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    companion object {
        private const val MAX_RECORDING_MS = 60_000L
    }
}
