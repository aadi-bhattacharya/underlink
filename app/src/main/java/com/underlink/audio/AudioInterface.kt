package com.underlink.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class VoiceInterface {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var appContext: Context? = null

    private var ttsReady = false
    private val pendingSpeakRequests = ArrayDeque<Pair<String, () -> Unit>>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize(context: Context) {
        appContext = context.applicationContext
        initializeStt(context)
        initializeTts(context)
    }

    fun startListening(onResult: (String) -> Unit, onError: () -> Unit) {
        val ctx = appContext ?: run { onError(); return }
        val recognizer = speechRecognizer ?: run { onError(); return }

        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onError()
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    mainHandler.post { onResult(text) }
                } else {
                    mainHandler.post { onError() }
                }
            }

            override fun onError(error: Int) {
                mainHandler.post { onError() }
            }
        })

        mainHandler.post { recognizer.startListening(intent) }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun speak(text: String, onDone: () -> Unit) {
        if (!ttsReady) {
            pendingSpeakRequests.addLast(Pair(text, onDone))
            return
        }
        executeSpeech(text, onDone)
    }

    fun shutdown() {
        speechRecognizer?.destroy()
        speechRecognizer = null

        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null

        ttsReady = false
        pendingSpeakRequests.clear()
        appContext = null
    }

    private fun initializeStt(context: Context) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
    }

    private fun initializeTts(context: Context) {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.getDefault()
                ttsReady = true
                flushPendingSpeakRequests()
            }
        }
    }

    private fun flushPendingSpeakRequests() {
        while (pendingSpeakRequests.isNotEmpty()) {
            val (text, onDone) = pendingSpeakRequests.removeFirst()
            executeSpeech(text, onDone)
        }
    }

    private fun executeSpeech(text: String, onDone: () -> Unit) {
        val utteranceId = UUID.randomUUID().toString()

        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(uid: String?) {}
            override fun onError(uid: String?) {
                if (uid == utteranceId) mainHandler.post { onDone() }
            }
            override fun onDone(uid: String?) {
                if (uid == utteranceId) mainHandler.post { onDone() }
            }
        })

        textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }
}