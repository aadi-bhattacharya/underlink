package com.underlink.codec

class BlockInterleaver(private val depth: Int = 20) {
    fun interleave(bits: IntArray): IntArray {
        val width = Math.ceil(bits.size.toDouble() / depth).toInt()
        val matrix = Array(depth) { IntArray(width) { 0 } }
        for (i in bits.indices) matrix[i / width][i % width] = bits[i]
        val out = mutableListOf<Int>()
        for (col in 0 until width) for (row in 0 until depth) out.add(matrix[row][col])
        return out.toIntArray()
    }

    fun deinterleave(bits: IntArray): IntArray {
        val width = Math.ceil(bits.size.toDouble() / depth).toInt()
        val matrix = Array(depth) { IntArray(width) { 0 } }
        var idx = 0
        for (col in 0 until width) for (row in 0 until depth) {
            if (idx < bits.size) matrix[row][col] = bits[idx++]
        }
        val out = mutableListOf<Int>()
        for (row in 0 until depth) for (col in 0 until width) out.add(matrix[row][col])
        return out.toIntArray()
    }
}