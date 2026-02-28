package com.underlink.codec

object Framer {

    val PREAMBLE: IntArray = IntArray(8) { 1 }   // 8 solid ON slots

    private const val MAX_PREAMBLE_ERRORS = 2
    private const val LEN_BITS = 8

    fun wrap(payload: IntArray): IntArray {
        val len = payload.size.coerceAtMost(255)
        val lenField = IntArray(LEN_BITS) { (len shr (7 - it)) and 1 }
        return PREAMBLE + lenField + payload.copyOf(len)
    }

    fun tryUnwrap(bits: IntArray): Pair<IntArray, IntArray>? {
        val pLen = PREAMBLE.size
        val minNeeded = pLen + LEN_BITS
        if (bits.size < minNeeded) return null

        var bestStart  = -1
        var bestErrors = MAX_PREAMBLE_ERRORS + 1

        for (start in 0..bits.size - minNeeded) {
            var errors = 0
            for (i in PREAMBLE.indices) {
                if (bits[start + i] != PREAMBLE[i]) errors++
            }
            if (errors < bestErrors) {
                bestErrors = errors
                bestStart  = start
                if (errors == 0) break
            }
        }

        if (bestStart < 0 || bestErrors > MAX_PREAMBLE_ERRORS) return null

        val lenStart = bestStart + pLen
        var len = 0
        for (i in 0 until LEN_BITS) len = (len shl 1) or bits[lenStart + i]

        val payloadStart = lenStart + LEN_BITS
        if (len == 0 || payloadStart + len > bits.size) return null

        return Pair(
            bits.copyOfRange(payloadStart, payloadStart + len),
            bits.copyOfRange(payloadStart + len, bits.size)
        )
    }
}