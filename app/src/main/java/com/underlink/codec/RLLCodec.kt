package com.underlink.codec

object RLLCodec {
    private val encodeTable = intArrayOf(
        0b11110, 0b01001, 0b10100, 0b10101,
        0b01010, 0b01011, 0b01110, 0b01111,
        0b10010, 0b10011, 0b10110, 0b10111,
        0b11010, 0b11011, 0b11100, 0b11101
    )
    private val decodeTable = IntArray(32) { -1 }.also { arr ->
        encodeTable.forEachIndexed { nibble, code -> arr[code] = nibble }
    }

    fun encodeBits(bits: IntArray): IntArray {
        val out = mutableListOf<Int>()
        for (i in 0 until bits.size / 4) {
            val nibble = (bits[i*4] shl 3) or (bits[i*4+1] shl 2) or (bits[i*4+2] shl 1) or bits[i*4+3]
            val code = encodeTable[nibble and 0xF]
            for (b in 4 downTo 0) out.add((code shr b) and 1)
        }
        return out.toIntArray()
    }

    fun decodeBits(bits: IntArray): IntArray {
        val out = mutableListOf<Int>()
        for (i in 0 until bits.size / 5) {
            val code = (bits[i*5] shl 4) or (bits[i*5+1] shl 3) or (bits[i*5+2] shl 2) or (bits[i*5+3] shl 1) or bits[i*5+4]
            val nibble = decodeTable[code and 0x1F]
            if (nibble == -1) { for (b in 3 downTo 0) out.add(0) } else {
                for (b in 3 downTo 0) out.add((nibble shr b) and 1)
            }
        }
        return out.toIntArray()
    }
}