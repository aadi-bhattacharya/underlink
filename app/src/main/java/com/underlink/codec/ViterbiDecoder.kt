package com.underlink.codec

class ViterbiDecoder {

    // 8 possible states (3-bit shift register = 2^3 = 8 states)
    private val NUM_STATES = 8

    // For each state, what are the next states and output bits
    // when input is 0 or 1?
    // Format: transitions[state][input] = Pair(nextState, outputBits)
    // outputBits is an IntArray of [out1, out2]
    private val transitions: Array<Array<Pair<Int, IntArray>>>

    init {
        transitions = Array(NUM_STATES) { state ->
            Array(2) { input ->
                // Same logic as ConvolutionalEncoder
                // reg = [bit2, bit1, bit0] of state
                val r0 = (state shr 2) and 1
                val r1 = (state shr 1) and 1
                val r2 = state and 1

                val out1 = input xor r1 xor r2
                val out2 = input xor r0 xor r2

                // Next state: shift register
                // new reg = [input, r0, r1]
                val nextState = ((input shl 2) or (r0 shl 1) or r1)

                Pair(nextState, intArrayOf(out1, out2))
            }
        }
    }

    // Main decode function
    // received: array of soft values (0.0 to 1.0 per bit)
    // returns: decoded bits (half the length of received)
    fun decode(received: FloatArray): IntArray {

        val numBits = received.size / 2  // each symbol is 2 bits

        // scores[state] = best score to reach this state so far
        // lower score = better path (fewer corrections needed)
        val scores = FloatArray(NUM_STATES) { Float.MAX_VALUE }
        scores[0] = 0f  // start at state 0

        // backtrack[step][state] = which previous state led here
        val backtrack = Array(numBits) { IntArray(NUM_STATES) { -1 } }

        // inputDecisions[step][state] = what input bit caused this transition
        val inputDecisions = Array(numBits) { IntArray(NUM_STATES) { 0 } }

        // Forward pass — fill in scores step by step
        for (step in 0 until numBits) {

            val newScores = FloatArray(NUM_STATES) { Float.MAX_VALUE }

            for (state in 0 until NUM_STATES) {
                if (scores[state] == Float.MAX_VALUE) continue  // unreachable state

                for (input in 0..1) {
                    val (nextState, outputBits) = transitions[state][input]

                    // How wrong are these output bits compared to what we received?
                    // received[step*2] and received[step*2+1] are soft values
                    // soft value near 1.0 means "probably a 1"
                    // soft value near 0.0 means "probably a 0"
                    val expected0 = outputBits[0].toFloat()
                    val expected1 = outputBits[1].toFloat()

                    val soft0 = received[step * 2]
                    val soft1 = received[step * 2 + 1]

                    // Branch metric: how far off are we?
                    // If expected=1 and soft=0.9, error is small (0.1)
                    // If expected=0 and soft=0.9, error is large (0.9)
                    var branchMetric = Math.abs(expected0 - soft0) +
                            Math.abs(expected1 - soft1)

                    // Burst awareness from proposal:
                    // consecutive errors are more likely underwater
                    // so penalise isolated errors more than burst errors
                    if (step > 0 && backtrack[step-1][state] != -1) {
                        val prevWasError = scores[state] > 0.5f
                        if (prevWasError) branchMetric *= 0.6f
                    }

                    val newScore = scores[state] + branchMetric

                    if (newScore < newScores[nextState]) {
                        newScores[nextState] = newScore
                        backtrack[step][nextState] = state
                        inputDecisions[step][nextState] = input
                    }
                }
            }

            for (i in 0 until NUM_STATES) scores[i] = newScores[i]
        }

        // Backward pass — trace back the best path
        // Find the state with the best final score
        var bestState = 0
        for (s in 1 until NUM_STATES) {
            if (scores[s] < scores[bestState]) bestState = s
        }

        // Walk backwards through backtrack table
        val decoded = IntArray(numBits)
        var currentState = bestState
        for (step in numBits - 1 downTo 0) {
            decoded[step] = inputDecisions[step][currentState]
            currentState = backtrack[step][currentState]
            if (currentState == -1) break
        }

        return decoded
    }

    // Helper: convert hard bits (0/1 int array) to soft values
    // Used when you don't have proper soft values yet
    fun hardToSoft(bits: IntArray): FloatArray {
        return FloatArray(bits.size) { bits[it].toFloat() }
    }

    // Helper: convert decoded bits back to text
    fun bitsToText(bits: IntArray): String {
        val sb = StringBuilder()
        for (i in 0 until bits.size / 8) {
            var byte = 0
            for (bit in 0..7) {
                byte = (byte shl 1) or bits[i * 8 + bit]
            }
            if (byte > 0) sb.append(byte.toChar())
        }
        return sb.toString()
    }
}