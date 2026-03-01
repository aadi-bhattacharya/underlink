package com.underlink

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent

class HardwareButtonHandler(
    private val context: Context,
    private val onPttStart: () -> Unit,
    private val onPttStop: () -> Unit,
    private val onModeToggle: () -> Unit,
    private val onCancelTransmission: () -> Unit,
    private val onRetransmitLast: () -> Unit,
    private val onFlushTts: () -> Unit,
    private val onRecalibrate: () -> Unit,
    private val onForceDecodeNow: () -> Unit,
    private val onClearRxBuffer: () -> Unit,
    private val onReset: () -> Unit
) {
    private var isTransmitting = false
    private var isTxMode = true
    private var isListening = false

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val handler = Handler(Looper.getMainLooper())

    // Per-key down timestamps
    private var volUpDownTime = 0L
    private var volDownDownTime = 0L

    // Per-key held state
    private var isVolUpHeld = false
    private var isVolDownHeld = false

    // Chord
    private var chordRunnable: Runnable? = null
    private var chordFired = false

    // Double-tap tracking
    private var volUpLastUpTime = 0L
    private var volUpSingleRunnable: Runnable? = null
    private var volDownLastUpTime = 0L
    private var volDownSingleRunnable: Runnable? = null

    // REQ-9: debounce
    private var volUpLastActionTime = 0L
    private var volDownLastActionTime = 0L
    private val DEBOUNCE_MS = 300L

    // ── Public state setters ──────────────────────────────────────────────────

    fun setTransmitting(transmitting: Boolean) { isTransmitting = transmitting }
    fun setMode(isTx: Boolean) { isTxMode = isTx }
    fun setListening(listening: Boolean) { isListening = listening }

    // Public so MainActivity can fire from switchMode listener when blocked
    fun notifyBlocked() = vibrateBlocked()

    fun release() { handler.removeCallbacksAndMessages(null) }

    // ── Haptics ───────────────────────────────────────────────────────────────

    private fun vibrateShort()   = vibrator.vibrate(VibrationEffect.createOneShot(40, 255))
    private fun vibrateLong()    = vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 40, 40), -1))
    private fun vibrateDouble()  = vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 30, 30), -1))
    private fun vibrateChord()   = vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 30, 40, 30, 40), -1))
    private fun vibrateBlocked() = vibrator.vibrate(VibrationEffect.createOneShot(15, 80))

    // REQ-10: decode result haptic — called from MainActivity after each decode
    fun vibrateDecodeResult(success: Boolean) {
        if (success) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 20, 20, 60), -1))
        else         vibrator.vibrate(VibrationEffect.createOneShot(80, 120))
    }

    // ── Key event entry point — called from dispatchKeyEvent ──────────────────

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false

        val now = SystemClock.uptimeMillis()

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) return true  // suppress long-press key repeat

            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (now - volUpLastActionTime < DEBOUNCE_MS) return true
                isVolUpHeld = true
                volUpDownTime = now
            } else {
                if (now - volDownLastActionTime < DEBOUNCE_MS) return true
                isVolDownHeld = true
                volDownDownTime = now
            }

            // Start chord timer if both now held
            if (isVolUpHeld && isVolDownHeld) {
                chordFired = false
                val r = Runnable {
                    if (isVolUpHeld && isVolDownHeld) {
                        chordFired = true
                        vibrateChord()
                        onReset()
                        volUpLastActionTime = SystemClock.uptimeMillis()
                        volDownLastActionTime = SystemClock.uptimeMillis()
                    }
                }
                chordRunnable = r
                handler.postDelayed(r, 800)
            }
            return true

        } else if (event.action == KeyEvent.ACTION_UP) {
            val isUp = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
            val isDn = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

            if (isUp && !isVolUpHeld) return true
            if (isDn && !isVolDownHeld) return true

            if (isUp) isVolUpHeld = false
            if (isDn) isVolDownHeld = false

            // Cancel chord timer on any release
            chordRunnable?.let { handler.removeCallbacks(it) }
            chordRunnable = null

            // If chord already fired, eat this up event
            if (chordFired) {
                if (!isVolUpHeld && !isVolDownHeld) chordFired = false
                return true
            }

            if (isUp) {
                val hold = now - volUpDownTime
                if (hold >= 600) {
                    volUpLastActionTime = now
                    handleVolUpLong()
                } else {
                    if (now - volUpLastUpTime <= 400) {
                        volUpSingleRunnable?.let { handler.removeCallbacks(it) }
                        volUpSingleRunnable = null
                        volUpLastActionTime = now
                        handleVolUpDouble()
                    } else {
                        val r = Runnable {
                            volUpLastActionTime = SystemClock.uptimeMillis()
                            handleVolUpShort()
                        }
                        volUpSingleRunnable = r
                        handler.postDelayed(r, 410)
                    }
                }
                volUpLastUpTime = now

            } else {
                val hold = now - volDownDownTime
                if (hold >= 600) {
                    volDownLastActionTime = now
                    handleVolDownLong()
                } else {
                    if (now - volDownLastUpTime <= 400) {
                        volDownSingleRunnable?.let { handler.removeCallbacks(it) }
                        volDownSingleRunnable = null
                        volDownLastActionTime = now
                        handleVolDownDouble()
                    } else {
                        val r = Runnable {
                            volDownLastActionTime = SystemClock.uptimeMillis()
                            handleVolDownShort()
                        }
                        volDownSingleRunnable = r
                        handler.postDelayed(r, 410)
                    }
                }
                volDownLastUpTime = now
            }
            return true
        }

        return false
    }

    // ── Action handlers ───────────────────────────────────────────────────────

    private fun handleVolUpShort() {
        if (isTxMode) {
            if (isTransmitting) { vibrateBlocked(); return }
            if (isListening) {
                isListening = false
                vibrateShort()
                onPttStop()
            } else {
                isListening = true
                vibrateShort()
                onPttStart()
            }
        } else {
            // RX: recalibrate
            vibrateShort()
            onRecalibrate()
        }
    }

    private fun handleVolUpDouble() {
        vibrateDouble()
        onFlushTts()
    }

    private fun handleVolUpLong() {
        if (isTxMode) {
            vibrateLong()
            onCancelTransmission()
        } else {
            vibrateLong()
            onForceDecodeNow()
        }
    }

    // REQ-3: guard mode switch if mid-transmission
    private fun handleVolDownShort() {
        if (isTransmitting) { vibrateBlocked(); return }
        vibrateShort()
        onModeToggle()
    }

    private fun handleVolDownDouble() {
        vibrateBlocked()  // not mapped — let user know it registered
    }

    private fun handleVolDownLong() {
        if (isTxMode) {
            vibrateLong()
            onRetransmitLast()
        } else {
            vibrateLong()
            onClearRxBuffer()
        }
    }
}