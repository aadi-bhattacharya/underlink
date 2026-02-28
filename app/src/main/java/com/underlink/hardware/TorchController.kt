package com.underlink.hardware

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log

class TorchController(private val cameraManager: CameraManager, private val cameraId: String) {

    fun transmitBits(bits: IntArray, callback: (() -> Unit)? = null) {
        transmitBits(bits.map { it == 1 }.toBooleanArray(), callback)
    }

    private val tag = "TorchController"
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // 100 ms per slot = 10 slots/sec.
    // Camera at 30fps delivers ~3 frames per slot — sufficient for majority-vote detection.
    private val halfPeriodNs = 100_000_000L

    fun start() {
        if (handlerThread != null) return

        // REQUIRED: Must use THREAD_PRIORITY_URGENT_AUDIO to prevent OS preemption
        handlerThread = HandlerThread("TorchThread", Process.THREAD_PRIORITY_URGENT_AUDIO).apply {
            start()
            handler = Handler(looper)
        }
        Log.d(tag, "TorchThread started at URGENT_AUDIO priority")
    }

    fun stop() {
        handlerThread?.quitSafely()
        try {
            handlerThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.e(tag, "Interrupted while waiting for TorchThread to perish", e)
        }
        handlerThread = null
        handler = null
        Log.d(tag, "TorchThread stopped")
    }

    /**
     * Transmits an array of bits as a sequence of ON/OFF flashlight pulses.
     * REQUIRED: Must use a busy-wait spin loop with elapsedRealtimeNanos().
     * Thread.sleep() or scheduled Runnables have 1-2ms of unacceptable jitter.
     */
    fun transmitBits(bits: BooleanArray, callback: (() -> Unit)? = null) {
        val currentHandler = handler
        if (currentHandler == null) {
            Log.e(tag, "Cannot transmit, TorchThread is not running")
            callback?.invoke()
            return
        }

        currentHandler.post {
            try {
                var nextNs = SystemClock.elapsedRealtimeNanos()
                for (bit in bits) {
                    // 1. Set the torch state (true for ON, false for OFF)
                    cameraManager.setTorchMode(cameraId, bit)

                    // 2. Calculate the exact nanosecond time the Next state should be applied
                    nextNs += halfPeriodNs

                    // 3. Busy-wait spin loop to guarantee sub-millisecond precision
                    while (SystemClock.elapsedRealtimeNanos() < nextNs) {
                        /* spin to win */
                    }
                }

                // Ensure torch is turned off at the end of transmission
                cameraManager.setTorchMode(cameraId, false)
            } catch (e: CameraAccessException) {
                Log.e(tag, "Failed to access camera for torch mode", e)
            } finally {
                // Notify via callback that transmission is complete so calling thread isn't blocked
                callback?.invoke()
            }
        }
    }
}