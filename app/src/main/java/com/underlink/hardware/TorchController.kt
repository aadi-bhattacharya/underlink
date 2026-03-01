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

    val SLOT_MS            = 200L
    val BEACON_MS          = 3000L
    val POST_BEACON_GAP_MS = 1500L

    private var thread:  HandlerThread? = null
    private var handler: Handler?       = null

    // REQ-1: cancellation flag
    @Volatile private var cancelRequested = false

    fun cancelTransmission() {
        cancelRequested = true
    }

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

    fun transmitMessage(payloadSlots: IntArray, onDone: (() -> Unit)? = null) {
        val h = handler ?: run { onDone?.invoke(); return }
        cancelRequested = false
        h.post {
            try {
                // 3 second solid beacon
                torch(true)
                val beaconEnd = System.currentTimeMillis() + BEACON_MS
                while (System.currentTimeMillis() < beaconEnd) {
                    if (cancelRequested) { torch(false); return@post }
                    Thread.sleep(50)
                }

                // 1.5 second silence gap
                torch(false)
                val gapEnd = System.currentTimeMillis() + POST_BEACON_GAP_MS
                while (System.currentTimeMillis() < gapEnd) {
                    if (cancelRequested) { return@post }
                    Thread.sleep(50)
                }

                // Payload
                val payloadStartTime = System.currentTimeMillis()
                for (i in payloadSlots.indices) {
                    if (cancelRequested) break
                    torch(payloadSlots[i] == 1)
                    val targetTime = payloadStartTime + (i + 1) * SLOT_MS
                    var now = System.currentTimeMillis()
                    while (now < targetTime) {
                        if (cancelRequested) break
                        val sleepMs = targetTime - now
                        if (sleepMs > 5) Thread.sleep(sleepMs - 2)
                        now = System.currentTimeMillis()
                    }
                }

                torch(false)
                cancelRequested = false

            } catch (e: Exception) {
                Log.e(TAG, "Transmit error: ${e.message}")
            } finally {
                onDone?.invoke()
            }
        }
    }
}