package com.underlink.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceInterface(private val ctx: Context) {

    private var stt: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val ttsQueue = mutableListOf<Pair<String, (() -> Unit)?>>()

    fun initialize() {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
                ttsQueue.forEach { (text, cb) -> speakNow(text, cb) }
                ttsQueue.clear()
            }
        }
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) {
            stt = SpeechRecognizer.createSpeechRecognizer(ctx)
        }
    }

    fun startListening(onResult: (String) -> Unit, onError: () -> Unit) {
        val recognizer = stt ?: run { onError(); return }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle) {
                val results = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = results?.firstOrNull() ?: ""
                if (text.isNotEmpty()) onResult(text) else onError()
            }
            override fun onError(err: Int) { onError() }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.startListening(intent)
    }

    fun stopListening() { stt?.stopListening() }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (ttsReady) speakNow(text, onDone) else ttsQueue.add(Pair(text, onDone))
    }

    private fun speakNow(text: String, onDone: (() -> Unit)?) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "uflash_${System.currentTimeMillis()}")
        onDone?.invoke()
    }

    fun shutdown() { stt?.destroy(); tts?.shutdown() }
}