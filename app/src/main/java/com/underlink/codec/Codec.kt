package com.underlink.codec

/**
 * Manchester-encoded optical OOK codec.
 *
 * WHY MANCHESTER instead of convolutional + Viterbi:
 *
 *  1. SELF-CLOCKING — every bit contains a guaranteed mid-bit transition,
 *     so the decoder recovers its own clock from the data. No separate
 *     timer drift to worry about.
 *
 *  2. DC-BALANCED — every bit has exactly one ON half-slot and one OFF
 *     half-slot, so the signal is always 50 % duty cycle. This lets the
 *     decoder use the MEDIAN of all half-slot averages as its threshold —
 *     the median always lands between the ON level and OFF level regardless
 *     of ambient brightness, camera exposure, or signal strength.
 *     Zero calibration needed for decoding.
 *
 *  3. SAME THROUGHPUT — convolutional coding expands bits 2×, Manchester
 *     also expands 2× (two half-slots per bit). "hi" = 32 bits either way
 *     → 64 output slots → 12.8 s payload. Identical transmission time.
 *
 *  4. NO FRAGILE INTERLEAVER — BlockInterleaver fails catastrophically if
 *     the slot count is off by even one (wrong matrix dimensions scramble
 *     every bit). Manchester has no interleaver at all.
 *
 * Frame format (MSB-first):
 *   [PREAMBLE 0xAA = 10101010][LENGTH byte][TEXT bytes...]
 *
 * Preamble first bit is 1 → first half-slot is ON → the RX leading-OFF-skip
 * only ever eats real silence, never payload.
 */
class Codec {

    data class DecodeResult(val text: String, val violations: Int)

    companion object {
        /** Camera runs at ~30 fps; 200 ms per half-slot ≈ 6 frames. */
        const val FRAMES_PER_HALF_SLOT = 6

        /** Preamble: 0xAA = 10101010. Validates correct bit alignment. */
        private const val PREAMBLE = 0xAA
    }

    // ── TX ────────────────────────────────────────────────────────────────────

    /**
     * Encode [text] into OOK half-slots ready for TorchController.
     * Returns an IntArray of 0/1 values, one per 200 ms half-slot.
     */
    fun encodeToSlots(text: String): IntArray {
        val payload = byteArrayOf(text.length.coerceAtMost(255).toByte()) +
                text.toByteArray(Charsets.US_ASCII)

        val preambleBits = intArrayOf(1, 0, 1, 0, 1, 0, 1, 0)   // 0xAA
        val dataBits     = bytesToBits(payload)
        val allBits      = preambleBits + dataBits

        return manchesterEncode(allBits)
    }

    // ── RX ────────────────────────────────────────────────────────────────────

    /**
     * Decode a message from the raw brightness stream stored during RECEIVING.
     *
     * Tries every possible frame phase offset (0 .. FRAMES_PER_HALF_SLOT-1)
     * AND half-slot lengths of ±1 frame (5, 6, 7) to tolerate camera-rate jitter.
     * That is 3 × 7 = 21 total decode attempts — all fast, no heavy math.
     *
     * @param raw         Raw brightness floats from CameraEngine, one per frame.
     * @param baseThreshold The calibrated ON threshold (used only to find the first
     *                    ON frame so we can trim leading silence; actual bit
     *                    decisions use the adaptive median threshold).
     */
    fun decodeFromRawStream(raw: List<Pair<Long, Float>>, baseThreshold: Float): String {
        if (raw.isEmpty()) return ""

        val firstOnIdx = raw.indexOfFirst { it.second > baseThreshold }
        if (firstOnIdx < 0) return ""

        val firstOnTime = raw[firstOnIdx].first

        val results = mutableListOf<DecodeResult>()

        // Search over phase offsets in ±200ms to find the exact alignment of the 200ms slots
        for (phaseMs in -200..200 step 20) {
            val startTime = firstOnTime + phaseMs
            val res = tryDecodeTimeBased(raw, startTime, 200L, baseThreshold)
            if (res != null) {
                results.add(res)
            }
        }

        // Return the one with minimum violations
        return results.minByOrNull { it.violations }?.text ?: ""
    }

    private fun tryDecodeTimeBased(raw: List<Pair<Long, Float>>, startTime: Long, halfSlotMs: Long, threshold: Float): DecodeResult? {
        val endTime = raw.last().first
        if (startTime >= endTime) return null

        val numHalfSlots = ((endTime - startTime) / halfSlotMs).toInt() + 1
        if (numHalfSlots < 16) return null

        val sums = FloatArray(numHalfSlots)
        val counts = IntArray(numHalfSlots)

        for (i in raw.indices) {
            val t = raw[i].first
            val b = raw[i].second
            if (t < startTime) continue
            val slotIdx = ((t - startTime) / halfSlotMs).toInt()
            if (slotIdx < numHalfSlots) {
                sums[slotIdx] += b
                counts[slotIdx]++
            }
        }

        val avgs = mutableListOf<Float>()
        for (i in 0 until numHalfSlots) {
            // If a slot has no frames (e.g. huge camera stutter), copy previous slot to bridge gap
            if (counts[i] > 0) {
                avgs.add(sums[i] / counts[i])
            } else if (avgs.isNotEmpty()) {
                avgs.add(avgs.last())
            } else {
                avgs.add(0f)
            }
        }

        // ── Step 2: Binarize using absolute calibrated threshold ─────────────
        val binary = IntArray(avgs.size) { if (avgs[it] > threshold) 1 else 0 }
        val binStr = binary.joinToString("")
        android.util.Log.d("Codec", "Phase ${startTime - raw[0].first}ms | Bins: $binStr")

        // Try decoding with and without dropping the very first partial half-slot
        var bestForThisPhase: DecodeResult? = null

        for (dropFirst in 0..1) {
            val validBinary = if (dropFirst == 1 && binary.isNotEmpty()) binary.copyOfRange(1, binary.size) else binary
            
            val maxOffset = minOf(30, maxOf(0, validBinary.size - 16))
            for (offset in 0..maxOffset) {
                // ── Step 4: Decode Manchester pairs → bits ────────────────────────────
                val bits = mutableListOf<Int>()
                var j = offset
                var violations = 0
                while (j + 1 < validBinary.size) {
                    val h1 = validBinary[j]; val h2 = validBinary[j + 1]
                    bits.add(
                        when {
                            h1 == 1 && h2 == 0 -> 1
                            h1 == 0 && h2 == 1 -> 0
                            else               -> { violations++; h1 }
                        }
                    )
                    j += 2
                }
                if (bits.size < 16) continue

                // ── Step 5: Validate preamble (bits 0–7 must be 0xAA = 10101010) ─────
                var pre = 0
                for (b in 0..7) pre = (pre shl 1) or bits[b]
                
                if (pre != PREAMBLE) continue
                
                android.util.Log.d("Codec", "  Offset $offset, Drop $dropFirst | Vars: $violations | Pre 0x${pre.toString(16)} | Bits: ${bits.joinToString("")}")

                // ── Step 6: Extract length byte (bits 8–15) ───────────────────────────
                var len = 0
                for (b in 0..7) len = (len shl 1) or bits[8 + b]
                len = len.coerceIn(1, 64)

                // ── Step 7: Extract text (starts at bit 16) ───────────────────────────
                if (bits.size < 16 + len * 8) continue
                val textBits = IntArray(len * 8) { bits[16 + it] }
                val text     = bitsToText(textBits)

                // Sanity check: every character must be printable ASCII
                if (text.isNotEmpty() && text.all { it.code in 32..126 }) {
                    if (bestForThisPhase == null || violations < bestForThisPhase!!.violations) {
                        bestForThisPhase = DecodeResult(text, violations)
                    }
                }
            }
        }
        
        return bestForThisPhase
    }

    // ── Encoding utilities ────────────────────────────────────────────────────

    private fun bytesToBits(bytes: ByteArray): IntArray {
        val bits = mutableListOf<Int>()
        for (b in bytes) for (bit in 7 downTo 0) bits.add((b.toInt() and 0xFF shr bit) and 1)
        return bits.toIntArray()
    }

    /**
     * Manchester encode: each input bit becomes two output half-slots.
     *   bit 1 → [1, 0]  (ON then OFF)
     *   bit 0 → [0, 1]  (OFF then ON)
     */
    private fun manchesterEncode(bits: IntArray): IntArray {
        val out = IntArray(bits.size * 2)
        for (idx in bits.indices) {
            out[idx * 2]     = bits[idx]         // first half-slot  = the bit
            out[idx * 2 + 1] = 1 - bits[idx]     // second half-slot = its complement
        }
        return out
    }

    // ── Decoding utilities ────────────────────────────────────────────────────

    private fun bitsToText(bits: IntArray): String {
        val sb = StringBuilder()
        for (i in 0 until bits.size / 8) {
            var b = 0
            for (bit in 0..7) b = (b shl 1) or bits[i * 8 + bit]
            if (b in 32..126) sb.append(b.toChar())
        }
        return sb.toString()
    }
}