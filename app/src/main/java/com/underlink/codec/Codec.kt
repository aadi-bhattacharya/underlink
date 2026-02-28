package com.underlink.codec

class Codec {
    private val encoder = ConvolutionalEncoder()
    private val decoder = ViterbiDecoder()
    private val interleaver = BlockInterleaver(depth = 20)

    fun encode(text: String): IntArray {
        var bits = encoder.encodeText(text)
        bits = interleaver.interleave(bits)
        bits = RLLCodec.encodeBits(bits)
        bits = PPMCodec.encodeBits(bits)
        return bits
    }

    fun decode(softValues: FloatArray): String {
        val ppmDecoded = PPMCodec.decodeSoft(softValues)
        val rllDecoded = RLLCodec.decodeBits(ppmDecoded)
        val deinterleaved = interleaver.deinterleave(rllDecoded)
        val softForViterbi = decoder.hardToSoft(deinterleaved)
        val decoded = decoder.decode(softForViterbi)
        return decoder.bitsToText(decoded)
    }
}