package com.underlink.codec

object PPMCodec {

    // ENCODE: 2 bits → 4 slots, ONE slot is ON, rest are OFF
    // 00 → [1,0,0,0]
    // 01 → [0,1,0,0]
    // 10 → [0,0,1,0]
    // 11 → [0,0,0,1]
    fun encode(twoBits: Int): IntArray {
        val slots = IntArray(4) { 0 }  // all OFF
        slots[twoBits and 0x3] = 1     // turn ON the slot at index twoBits
        return slots
    }

    // DECODE: look at 4 soft values (brightness of each slot)
    // return the index of the brightest one → that's your 2 bits
    fun decode(softValues: FloatArray): Int {
        var maxIdx = 0
        for (i in 1..3) {
            if (softValues[i] > softValues[maxIdx]) maxIdx = i
        }
        return maxIdx
    }

    // Encode a full bit array (must be even length, 2 bits per symbol)
    fun encodeBits(bits: IntArray): IntArray {
        val output = mutableListOf<Int>()
        for (i in 0 until bits.size / 2) {
            val twoBits = (bits[i * 2] shl 1) or bits[i * 2 + 1]
            encode(twoBits).forEach { output.add(it) }
        }
        return output.toIntArray()
    }

    // Decode groups of 4 soft values back to bits
    fun decodeSoft(softValues: FloatArray): IntArray {
        val output = mutableListOf<Int>()
        for (i in 0 until softValues.size / 4) {
            val slot = FloatArray(4) { softValues[i * 4 + it] }
            val symbol = decode(slot)
            // convert symbol index back to 2 bits
            output.add((symbol shr 1) and 1)
            output.add(symbol and 1)
        }
        return output.toIntArray()
    }
}