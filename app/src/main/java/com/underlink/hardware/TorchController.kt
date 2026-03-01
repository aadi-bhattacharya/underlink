package com.underlink.hardware

import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log

class TorchController(
    private val cameraManager: CameraManager,
    private val cameraId: String
) {
    private val TAG = "TorchController"

    val SLOT_MS        = 200L    // one data slot duration
    val BEACON_MS      = 3000L   // solid ON beacon before message
    val POST_BEACON_GAP_MS = 1500L  // silence after beacon before payload starts

    private var thread:  HandlerThread? = null
    private var handler: Handler?       = null

    fun start() {
        thread = HandlerThread("TorchThread", Process.THREAD_PRIORITY_URGENT_AUDIO).also {
            it.start()
            handler = Handler(it.looper)
        }
    }

    fun stop() {
        runCatching { cameraManager.setTorchMode(cameraId, false) }
        thread?.quitSafely()
        thread = null; handler = null
    }

    private fun torch(on: Boolean) {
        runCatching { cameraManager.setTorchMode(cameraId, on) }
    }

    // TX sequence:
    //   BEACON: 3 seconds solid ON  (receiver detects this)
    //   GAP:    1.5 seconds OFF     (receiver waits for this to end then starts collecting)
    //   PAYLOAD: each slot is SLOT_MS of ON or OFF
    //   END:    torch off — receiver detects end by silence
    fun transmitMessage(payloadSlots: IntArray, onDone: (() -> Unit)? = null) {
        val h = handler ?: run { onDone?.invoke(); return }
        h.post {
            try {
                // 3 second solid beacon
                torch(true)
                Thread.sleep(BEACON_MS)

                // 1.5 second silence gap
                torch(false)
                Thread.sleep(POST_BEACON_GAP_MS)

                // Payload
                val payloadStartTime = System.currentTimeMillis()
                for (i in payloadSlots.indices) {
                    val slot = payloadSlots[i]
                    torch(slot == 1)
                    
                    val targetTime = payloadStartTime + (i + 1) * SLOT_MS
                    var now = System.currentTimeMillis()
                    while (now < targetTime) {
                        val sleepMs = targetTime - now
                        if (sleepMs > 5) {
                            Thread.sleep(sleepMs - 2) // Sleep slightly less, then busy-spin for perfection
                        }
                        now = System.currentTimeMillis()
                    }
                }

                // End — silence is the terminator
                torch(false)

            } catch (e: Exception) {
                Log.e(TAG, "Transmit error: ${e.message}")
            } finally {
                onDone?.invoke()
            }
        }
    }
}