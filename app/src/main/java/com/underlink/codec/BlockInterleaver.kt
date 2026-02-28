package com.underlink.codec

class BlockInterleaver(private val depth: Int = 20) {

    // INTERLEAVE: write row-by-row, read column-by-column
    // Input:  [a, b, c, d, e, f] with depth=2
    // Matrix: [a, b, c]   (row 0)
    //         [d, e, f]   (row 1)
    // Output: [a, d, b, e, c, f]  (read column by column)
    fun interleave(bits: IntArray): IntArray {
        // Figure out how wide each row needs to be
        val width = Math.ceil(bits.size.toDouble() / depth).toInt()

        // Create the matrix, padded with zeros if needed
        val matrix = Array(depth) { IntArray(width) { 0 } }

        // Fill row by row
        for (i in bits.indices) {
            matrix[i / width][i % width] = bits[i]
        }

        // Read column by column
        val output = mutableListOf<Int>()
        for (col in 0 until width) {
            for (row in 0 until depth) {
                output.add(matrix[row][col])
            }
        }

        return output.toIntArray()
    }

    // DEINTERLEAVE: exact reverse — write column-by-column, read row-by-row
    fun deinterleave(bits: IntArray): IntArray {
        val width = Math.ceil(bits.size.toDouble() / depth).toInt()
        val matrix = Array(depth) { IntArray(width) { 0 } }

        // Fill column by column (reverse of interleave)
        var idx = 0
        for (col in 0 until width) {
            for (row in 0 until depth) {
                if (idx < bits.size) matrix[row][col] = bits[idx++]
            }
        }

        // Read row by row
        val output = mutableListOf<Int>()
        for (row in 0 until depth) {
            for (col in 0 until width) {
                output.add(matrix[row][col])
            }
        }

        return output.toIntArray()
    }
}