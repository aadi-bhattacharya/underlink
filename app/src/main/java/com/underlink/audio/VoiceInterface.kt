package com.underlink.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class VoiceInterface(private val ctx: Context) {

    private var stt: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val ttsQueue = mutableListOf<Pair<String, (() -> Unit)?>>()

    // REQ-13: isSpeaking flag
    var isSpeaking: Boolean = false
        private set

    private var sttFocusRequest: Any? = null
    private var ttsFocusRequest: Any? = null

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
        requestSttFocus()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle) {
                abandonSttFocus()
                val text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (text.isNotEmpty()) onResult(text) else onError()
            }
            override fun onError(err: Int) {
                abandonSttFocus()
                onError()
            }
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

    fun stopListening() {
        abandonSttFocus()
        stt?.stopListening()
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (ttsReady) speakNow(text, onDone) else ttsQueue.add(Pair(text, onDone))
    }

    // REQ-14: stopSpeaking flushes entire TTS queue
    fun stopSpeaking() {
        tts?.stop()
        isSpeaking = false
        ttsQueue.clear()
        abandonTtsFocus()
    }

    private fun speakNow(text: String, onDone: (() -> Unit)?) {
        val uid = "uflash_${System.currentTimeMillis()}"
        isSpeaking = true
        requestTtsFocus()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(u: String?) {}
            override fun onDone(u: String?) {
                if (u == uid) {
                    isSpeaking = false
                    abandonTtsFocus()
                    onDone?.invoke()
                }
            }
            override fun onError(u: String?) {
                if (u == uid) {
                    isSpeaking = false
                    abandonTtsFocus()
                    onDone?.invoke()
                }
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid)
    }

    // REQ-12: audio focus helpers
    private fun requestSttFocus() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).build()
            sttFocusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonSttFocus() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (sttFocusRequest as? AudioFocusRequest)?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        sttFocusRequest = null
    }

    private fun requestTtsFocus() {
        if (isSpeaking) return  // already held
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).build()
            ttsFocusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonTtsFocus() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (ttsFocusRequest as? AudioFocusRequest)?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        ttsFocusRequest = null
    }

    fun shutdown() { stt?.destroy(); tts?.shutdown() }
}