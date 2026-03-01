package com.underlink.codec

object PPMCodec {
    fun encodeBits(bits: IntArray): IntArray {
        val out = mutableListOf<Int>()
        for (i in 0 until bits.size / 2) {
            val sym = (bits[i*2] shl 1) or bits[i*2+1]
            val slots = IntArray(4) { 0 }
            slots[sym] = 1
            slots.forEach { out.add(it) }
        }
        return out.toIntArray()
    }

    // softValues: one float per slot, groups of 4
    fun decodeSoft(softValues: FloatArray): IntArray {
        val out = mutableListOf<Int>()
        for (i in 0 until softValues.size / 4) {
            var maxIdx = 0
            for (j in 1..3) if (softValues[i*4+j] > softValues[i*4+maxIdx]) maxIdx = j
            out.add((maxIdx shr 1) and 1)
            out.add(maxIdx and 1)
        }
        return out.toIntArray()
    }
}