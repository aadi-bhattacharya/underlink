package com.underlink.codec

class ConvolutionalEncoder {

    private val reg = IntArray(3) { 0 }

    private fun encodeBit(bit: Int): IntArray {
        val out1 = bit xor reg[1] xor reg[2]
        val out2 = bit xor reg[0] xor reg[2]
        reg[2] = reg[1]
        reg[1] = reg[0]
        reg[0] = bit
        return intArrayOf(out1, out2)
    }

    fun encode(bits: IntArray): IntArray {
        reg[0] = 0; reg[1] = 0; reg[2] = 0
        val output = IntArray(bits.size * 2)
        for (i in bits.indices) {
            val pair = encodeBit(bits[i])
            output[i * 2]     = pair[0]
            output[i * 2 + 1] = pair[1]
        }
        return output
    }

    fun encodeText(text: String): IntArray {
        val bits = mutableListOf<Int>()
        for (char in text) {
            val byte = char.code
            for (bit in 7 downTo 0) {
                bits.add((byte shr bit) and 1)
            }
        }
        return encode(bits.toIntArray())
    }
}