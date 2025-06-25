package com.example.android

object Compressor {

    private val indexTable = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )

    private val stepSizeTable = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    fun encode(pcmBytes: ByteArray): ByteArray {
        val adpcm = ByteArray(pcmBytes.size / 4) // 2 samples -> 1 byte (4 bytes PCM -> 1 byte ADPCM)
        var predicted = 0
        var index = 0

        var outIndex = 0
        var i = 0
        while (i + 3 < pcmBytes.size) {
            val sample1 = ((pcmBytes[i + 1].toInt() shl 8) or (pcmBytes[i].toInt() and 0xFF)).toShort().toInt()
            val sample2 = ((pcmBytes[i + 3].toInt() shl 8) or (pcmBytes[i + 2].toInt() and 0xFF)).toShort().toInt()

            val (n1, p1, i1) = encodeSample(sample1, predicted, index)
            predicted = p1
            index = i1

            val (n2, p2, i2) = encodeSample(sample2, predicted, index)
            predicted = p2
            index = i2

            adpcm[outIndex++] = ((n2 shl 4) or n1).toByte()
            i += 4
        }

        return adpcm
    }

    fun decode(adpcmBytes: ByteArray): ByteArray {
        val pcmBytes = ByteArray(adpcmBytes.size * 4)
        var predicted = 0
        var index = 0

        var outIndex = 0
        for (b in adpcmBytes) {
            val nibble1 = b.toInt() and 0x0F
            val nibble2 = (b.toInt() shr 4) and 0x0F

            val (s1, p1, i1) = decodeNibble(nibble1, predicted, index)
            predicted = p1
            index = i1
            pcmBytes[outIndex++] = s1.toByte()
            pcmBytes[outIndex++] = (s1 shr 8).toByte()

            val (s2, p2, i2) = decodeNibble(nibble2, predicted, index)
            predicted = p2
            index = i2
            pcmBytes[outIndex++] = s2.toByte()
            pcmBytes[outIndex++] = (s2 shr 8).toByte()
        }

        return pcmBytes
    }

    private fun encodeSample(sample: Int, predicted: Int, index: Int): Triple<Int, Int, Int> {
        var delta = sample - predicted
        var sign = 0
        var step = stepSizeTable[index]

        if (delta < 0) {
            sign = 8
            delta = -delta
        }

        var diff = step shr 3
        var code = 0
        if (delta >= step) {
            code = 4
            delta -= step
            diff += step
        }
        step = step shr 1
        if (delta >= step) {
            code = code or 2
            delta -= step
            diff += step
        }
        step = step shr 1
        if (delta >= step) {
            code = code or 1
            diff += step
        }

        code = code or sign
        var newPredicted = predicted + if (sign != 0) -diff else diff
        newPredicted = newPredicted.coerceIn(-32768, 32767)

        var newIndex = index + indexTable[code]
        newIndex = newIndex.coerceIn(0, 88)

        return Triple(code, newPredicted, newIndex)
    }

    private fun decodeNibble(code: Int, predicted: Int, index: Int): Triple<Int, Int, Int> {
        val step = stepSizeTable[index]
        var diff = step shr 3
        if ((code and 4) != 0) diff += step
        if ((code and 2) != 0) diff += step shr 1
        if ((code and 1) != 0) diff += step shr 2

        val newPredicted = (predicted + if ((code and 8) != 0) -diff else diff).coerceIn(-32768, 32767)
        val newIndex = (index + indexTable[code]).coerceIn(0, 88)

        return Triple(newPredicted, newPredicted, newIndex)
    }
}
