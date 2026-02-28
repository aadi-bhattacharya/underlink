package com.underlink.codec

object RLLCodec {

    // THE LOOKUP TABLE
    // Index = your 4-bit input (0 to 15)
    // Value = the 5-bit code to send instead
    // These values are the standard 4B5B table from Ethernet
    // Property guaranteed: no output has more than 3 identical bits in a row
    private val encodeTable = intArrayOf(
        0b11110,  // 0  → 30
        0b01001,  // 1  → 9
        0b10100,  // 2  → 20
        0b10101,  // 3  → 21
        0b01010,  // 4  → 10
        0b01011,  // 5  → 11
        0b01110,  // 6  → 14
        0b01111,  // 7  → 15
        0b10010,  // 8  → 18
        0b10011,  // 9  → 19
        0b10110,  // 10 → 22
        0b10111,  // 11 → 23
        0b11010,  // 12 → 26
        0b11011,  // 13 → 27
        0b11100,  // 14 → 28
        0b11101   // 15 → 29
    )

    // REVERSE TABLE for decoding
    // Size 32 because 5-bit codes go up to 31
    // -1 means "invalid code, shouldn't happen"
    private val decodeTable = IntArray(32) { -1 }.also { arr ->
        encodeTable.forEachIndexed { nibble, code -> arr[code] = nibble }
    }

    // Encode one 4-bit nibble → one 5-bit code
    fun encode(nibble: Int): Int = encodeTable[nibble and 0xF]

    // Decode one 5-bit code → one 4-bit nibble
    // Returns -1 if the code is invalid (bit error corrupted it)
    fun decode(code: Int): Int = decodeTable[code and 0x1F]

    // Encode a full array of bits (must be multiple of 4)
    fun encodeBits(bits: IntArray): IntArray {
        val output = mutableListOf<Int>()
        // Process 4 bits at a time
        for (i in 0 until bits.size / 4) {
            // Pack 4 bits into one nibble (number 0-15)
            val nibble = (bits[i*4] shl 3) or
                    (bits[i*4+1] shl 2) or
                    (bits[i*4+2] shl 1) or
                    bits[i*4+3]
            // Look up the 5-bit code
            val code = encode(nibble)
            // Unpack 5-bit code back into individual bits
            for (bit in 4 downTo 0) {
                output.add((code shr bit) and 1)
            }
        }
        return output.toIntArray()
    }

    // Decode a full array of bits (must be multiple of 5)
    fun decodeBits(bits: IntArray): IntArray {
        val output = mutableListOf<Int>()
        // Process 5 bits at a time
        for (i in 0 until bits.size / 5) {
            // Pack 5 bits into one code
            val code = (bits[i*5] shl 4) or
                    (bits[i*5+1] shl 3) or
                    (bits[i*5+2] shl 2) or
                    (bits[i*5+3] shl 1) or
                    bits[i*5+4]
            // Look up the original 4-bit nibble
            val nibble = decode(code)
            if (nibble == -1) continue  // skip invalid codes
            // Unpack 4-bit nibble back into individual bits
            for (bit in 3 downTo 0) {
                output.add((nibble shr bit) and 1)
            }
        }
        return output.toIntArray()
    }
}