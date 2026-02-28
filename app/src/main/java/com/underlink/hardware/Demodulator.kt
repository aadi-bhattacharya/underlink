package com.underlink.hardware

import android.os.SystemClock

/**
 * Converts per-frame brightness into bit arrays.
 *
 * Slot rate: 100ms/slot (10 slots/sec).
 * At 30fps the camera delivers ~3 frames per slot — enough for a majority vote.
 *
 * Adaptive threshold: calibrates ambient baseline for 500ms then requires
 * brightness to exceed baseline + thresholdMargin to count as ON.
 * thresholdMargin = 70 makes it robust to typical indoor ambient light.
 */
class Demodulator(
    private val slotMs: Long = 100L,
    private val onTransmissionComplete: (bits: IntArray, linkQuality: Int) -> Unit
) {
    private val calibrationMs   = 500L
    private val thresholdMargin = 70f   // raised from 40 — rejects ambient interference
    private val silenceSlots    = 20    // 2 seconds of true silence = end of message

    private enum class State { CALIBRATING, IDLE, RECEIVING }
    private data class Sample(val timestampMs: Long, val brightness: Float)

    private var state         = State.CALIBRATING
    private var calibStart    = -1L
    private val calibSamples  = mutableListOf<Float>()
    var baseline              = 0f;  private set
    var threshold             = 128f; private set

    private val samples       = ArrayDeque<Sample>()
    private var firstSampleMs = -1L
    private var lastBrightMs  = -1L
    var lastLinkQuality       = 0; private set

    fun onFrame(rowMeans: FloatArray, timestampMs: Long = SystemClock.elapsedRealtime()) {
        val h = rowMeans.size
        var sum = 0f
        for (r in h / 3 until h * 2 / 3) sum += rowMeans[r]
        val brightness = sum / (h / 3)

        when (state) {
            State.CALIBRATING -> calibrate(brightness, timestampMs)
            State.IDLE        -> checkForStart(brightness, timestampMs)
            State.RECEIVING   -> collect(brightness, timestampMs)
        }
    }

    fun reset() {
        state         = State.CALIBRATING
        calibStart    = -1L
        calibSamples.clear()
        samples.clear()
        firstSampleMs = -1L
        lastBrightMs  = -1L
    }

    private fun calibrate(brightness: Float, timestampMs: Long) {
        if (calibStart < 0) calibStart = timestampMs
        calibSamples.add(brightness)
        if (timestampMs - calibStart >= calibrationMs) {
            baseline  = calibSamples.average().toFloat()
            threshold = baseline + thresholdMargin
            state     = State.IDLE
            calibSamples.clear()
        }
    }

    private fun checkForStart(brightness: Float, timestampMs: Long) {
        if (brightness > threshold) {
            state         = State.RECEIVING
            firstSampleMs = timestampMs
            lastBrightMs  = timestampMs
            samples.clear()
            samples.addLast(Sample(timestampMs, brightness))
        }
    }

    private fun collect(brightness: Float, timestampMs: Long) {
        samples.addLast(Sample(timestampMs, brightness))
        if (brightness > threshold) lastBrightMs = timestampMs

        if ((timestampMs - lastBrightMs) > silenceSlots * slotMs) {
            val (bits, quality) = samplesToBits()
            lastLinkQuality = quality
            reset()
            if (bits.isNotEmpty()) onTransmissionComplete(bits, quality)
        }
    }

    private fun samplesToBits(): Pair<IntArray, Int> {
        if (samples.isEmpty()) return Pair(IntArray(0), 0)

        val bits       = mutableListOf<Int>()
        val cleanZone  = thresholdMargin * 0.5f
        var cleanSlots = 0
        var slotStart  = firstSampleMs
        val lastTs     = samples.last().timestampMs

        while (slotStart <= lastTs) {
            val slotEnd = slotStart + slotMs
            var bucketSum   = 0f
            var bucketCount = 0
            for (s in samples) {
                if (s.timestampMs >= slotStart && s.timestampMs < slotEnd) {
                    bucketSum   += s.brightness
                    bucketCount++
                }
            }
            val mean = if (bucketCount == 0) 0f else bucketSum / bucketCount
            bits.add(if (mean > threshold) 1 else 0)
            if (mean > threshold + cleanZone || mean < baseline - cleanZone) cleanSlots++
            slotStart = slotEnd
        }

        val quality = if (bits.isEmpty()) 0 else (cleanSlots * 100 / bits.size)
        return Pair(bits.toIntArray(), quality)
    }
}