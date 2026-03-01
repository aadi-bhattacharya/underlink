package com.underlink.codec

class ViterbiDecoder {
    private val NUM_STATES = 8
    private val transitions = Array(NUM_STATES) { state ->
        Array(2) { input ->
            val r0 = (state shr 2) and 1
            val r1 = (state shr 1) and 1
            val r2 = state and 1
            val out1 = input xor r1 xor r2
            val out2 = input xor r0 xor r2
            val next = (input shl 2) or (r0 shl 1) or r1
            Pair(next, intArrayOf(out1, out2))
        }
    }

    fun decode(received: FloatArray): IntArray {
        val n = received.size / 2
        val scores = FloatArray(NUM_STATES) { Float.MAX_VALUE }
        scores[0] = 0f
        val back = Array(n) { IntArray(NUM_STATES) { -1 } }
        val dec  = Array(n) { IntArray(NUM_STATES) { 0 } }

        for (step in 0 until n) {
            val ns = FloatArray(NUM_STATES) { Float.MAX_VALUE }
            for (s in 0 until NUM_STATES) {
                if (scores[s] == Float.MAX_VALUE) continue
                for (inp in 0..1) {
                    val (next, ob) = transitions[s][inp]
                    var bm = Math.abs(ob[0].toFloat() - received[step*2]) +
                            Math.abs(ob[1].toFloat() - received[step*2+1])
                    // burst weighting
                    if (step > 0 && back[step-1][s] != -1 && scores[s] > 0.5f) bm *= 0.6f
                    val sc = scores[s] + bm
                    if (sc < ns[next]) { ns[next] = sc; back[step][next] = s; dec[step][next] = inp }
                }
            }
            for (i in 0 until NUM_STATES) scores[i] = ns[i]
        }

        var best = 0
        for (s in 1 until NUM_STATES) if (scores[s] < scores[best]) best = s
        val out = IntArray(n)
        var cur = best
        for (step in n-1 downTo 0) {
            out[step] = dec[step][cur]
            val prev = back[step][cur]
            if (prev == -1) break
            cur = prev
        }
        return out
    }

    fun bitsToText(bits: IntArray): String {
        val sb = StringBuilder()
        for (i in 0 until bits.size / 8) {
            var b = 0
            for (bit in 0..7) b = (b shl 1) or bits[i*8+bit]
            if (b in 32..126) sb.append(b.toChar())
        }
        return sb.toString()
    }
}