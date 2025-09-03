package com.appliedrec.veridv2v3migration

data class FP16Vec(
    var coeff: Float = 0f,
    var values: ShortArray = shortArrayOf()
) {
    val count: Int get() = values.size
    val isEmpty: Boolean get() = values.isEmpty()
}

data class FPVCVector(
    var coeff: Float = 0f,
    var codes: ByteArray = byteArrayOf()
) {
    val isEmpty: Boolean get() = codes.isEmpty()
}

class FPVCDecoder {

    @Throws(FaceTemplateMigrationError::class)
    fun deserializeFPVC(data: ByteArray): FP16Vec {
        var remaining = data.size
        if (remaining < 4) throw FaceTemplateMigrationError.VectorDeserializationFailed

        val u8 = data

        // u8[0] is version (unused)
        var nelsHead = (u8[1].toInt() and 0xFF)

        // Special short format: 1 vector, 128 x int8 + BF16 coeff
        if (nelsHead == 1 && remaining >= 132) {
            val v = FP16Vec()
            v.values = ShortArray(128)
            // 128 int8 at byte 4
            for (i in 0 until 128) {
                v.values[i] = u8[4 + i].toInt().toShort() // keep signed int8 -> short
            }
            // coeff is BF16 at bytes 2..3 (LE), expanded by << 16
            val bf16 = readLEUInt16(u8, 2)
            v.coeff = bfloat16ToFloat(bf16)
            if (!(v.coeff >= 0f)) throw FaceTemplateMigrationError.VectorDeserializationFailed
            return v
        }

        val type = (u8[2].toInt() and 0xFF)
        val nvecs = (u8[3].toInt() and 0xFF)
        if (nvecs != 1) throw FaceTemplateMigrationError.VectorDeserializationFailed

        var offset = 4
        remaining -= 4
        var result = FP16Vec()

        var nels = nelsHead
        if (nels == 0) {
            if (remaining < 4) throw FaceTemplateMigrationError.VectorDeserializationFailed
            val nels32 = readLEUInt32(u8, offset)
            if (nels32 == 0) throw FaceTemplateMigrationError.VectorDeserializationFailed
            nels = nels32
            offset += 4
            remaining -= 4
        }

        var nBytes = 0
        when (type) {
            0x10 -> {
                nBytes = fpvcVectorSerializeSize(nels)
                if (remaining < nBytes) throw FaceTemplateMigrationError.VectorDeserializationFailed
                val slice = u8.copyOfRange(offset, offset + nBytes)
                val fpvc = fpvcVectorDeserialize(slice, nels)
                val fp16 = toFP16Vec(fpvc)
                result = fp16
            }
            0x11 -> {
                nBytes = fp16vec12Bytes(nels)
                if (remaining < nBytes) throw FaceTemplateMigrationError.VectorDeserializationFailed
                val slice = u8.copyOfRange(offset, offset + nBytes)
                val fp16 = deserializeFp16vec12(slice, nels)
                result = fp16
            }
            0x12 -> {
                nBytes = fp16vec16Bytes(nels)
                if (remaining < nBytes) throw FaceTemplateMigrationError.VectorDeserializationFailed
                val slice = u8.copyOfRange(offset, offset + nBytes)
                val fp16 = deserializeFp16vec16(slice, nels)
                result = fp16
            }
            else -> throw FaceTemplateMigrationError.VectorDeserializationFailed
        }

        check((nBytes and 3) == 0)
        offset += nBytes
        remaining -= nBytes
        check(result.count == nels)

        // If bytes remain, C++ only logs a warning; we ignore (no throw)
        return result
    }

    private fun fpvcVectorSerializeSize(nels: Int): Int {
        val padded = (nels + 3) and -4
        return 4 + padded
    }

    // C++: fpvc_vector_deserialize(const void* src, size_t vector_size)
    @Throws(FaceTemplateMigrationError::class)
    private fun fpvcVectorDeserialize(blob: ByteArray, nels: Int): FPVCVector {
        val r = FPVCVector()
        if (blob.size < 4 + nels) throw FaceTemplateMigrationError.VectorDeserializationFailed
        r.coeff = readFloatLE(blob, 0)
        if (!(r.coeff >= 0f)) throw FaceTemplateMigrationError.VectorDeserializationFailed
        // only the first `nels` code bytes are actual data; padding may follow
        r.codes = blob.copyOfRange(4, 4 + nels)
        return r
    }

    // C++: to_fp16vec(const fpvc_vector_type&)
    private fun toFP16Vec(v: FPVCVector): FP16Vec {
        val r = FP16Vec()
        r.coeff = v.coeff
        val out = ShortArray(v.codes.size)
        for (i in v.codes.indices) {
            val idx = v.codes[i].toInt() and 0xFF
            out[i] = fpvcS16DecompressTable[idx]
        }
        r.values = out
        return r
    }

    // C++: fp16vec_12_bytes(size_t nels)
    private fun fp16vec12Bytes(nels: Int): Int {
        // 4 bytes coeff + packed 12-bit ints, padded to multiple of 4.
        // pairs: 3 bytes per 2 values; odd tail: 2 bytes
        val pairs = nels / 2
        val tail = (nels and 1)
        val dataBytes = pairs * 3 + if (tail == 1) 2 else 0
        val padded = (dataBytes + 3) and -4
        return 4 + padded
    }

    // C++: deserialize_fp16vec_12(const void* src, size_t vector_size)
    @Throws(FaceTemplateMigrationError::class)
    private fun deserializeFp16vec12(blob: ByteArray, nels: Int): FP16Vec {
        val r = FP16Vec()
        if (blob.size < 4) throw FaceTemplateMigrationError.VectorDeserializationFailed
        r.coeff = readFloatLE(blob, 0)
        if (!(r.coeff >= 0f)) throw FaceTemplateMigrationError.VectorDeserializationFailed
        r.values = ShortArray(nels)

        var p = 4
        var i = 0
        while ((nels - i) >= 2) {
            // x0: ((p[1]<<12) + (p[0]<<4)) >> 4 (arith)
            val comb0 = ((blob[p + 1].toInt() and 0xFF) shl 12) or
                    ((blob[p + 0].toInt() and 0xFF) shl 4)
            r.values[i] = (comb0.toShort().toInt() shr 4).toShort()
            // x1: ((p[2]<<8) + p[1]) >> 4 (arith)
            val comb1 = ((blob[p + 2].toInt() and 0xFF) shl 8) or
                    (blob[p + 1].toInt() and 0xFF)
            r.values[i + 1] = (comb1.toShort().toInt() shr 4).toShort()

            p += 3
            i += 2
        }
        if (i < nels) {
            val comb0 = ((blob[p + 1].toInt() and 0xFF) shl 12) or
                    ((blob[p + 0].toInt() and 0xFF) shl 4)
            r.values[i] = (comb0.toShort().toInt() shr 4).toShort()
            // padding may follow; OK
        }
        return r
    }

    // C++: fp16vec_16_bytes(size_t nels)
    private fun fp16vec16Bytes(nels: Int): Int {
        // 4 bytes coeff + 2*nels + (if odd, 2 bytes pad)
        return 4 + 2 * nels + if ((nels and 1) == 1) 2 else 0
    }

    // C++: deserialize_fp16vec_16(const void* src, size_t vector_size)
    @Throws(FaceTemplateMigrationError::class)
    private fun deserializeFp16vec16(blob: ByteArray, nels: Int): FP16Vec {
        val r = FP16Vec()
        if (blob.size < 4 + 2 * nels) throw FaceTemplateMigrationError.VectorDeserializationFailed
        r.coeff = readFloatLE(blob, 0)
        if (!(r.coeff >= 0f)) throw FaceTemplateMigrationError.VectorDeserializationFailed
        val out = ShortArray(nels)
        var off = 4
        for (i in 0 until nels) {
            out[i] = readLEInt16(blob, off)
            off += 2
        }
        r.values = out
        return r
    }

    // ---------- Byte reading helpers (LE) + BF16 → Float ----------

    private fun readLEUInt16(data: ByteArray, offset: Int): Int {
        val lo = (data[offset].toInt() and 0xFF)
        val hi = (data[offset + 1].toInt() and 0xFF) shl 8
        return hi or lo
    }

    private fun readLEUInt32(data: ByteArray, offset: Int): Int {
        val b0 = (data[offset + 0].toInt() and 0xFF)
        val b1 = (data[offset + 1].toInt() and 0xFF) shl 8
        val b2 = (data[offset + 2].toInt() and 0xFF) shl 16
        val b3 = (data[offset + 3].toInt() and 0xFF) shl 24
        return b0 or b1 or b2 or b3
    }

    private fun readLEInt16(data: ByteArray, offset: Int): Short {
        val u = readLEUInt16(data, offset)
        return u.toShort()
    }

    private fun readFloatLE(data: ByteArray, offset: Int): Float {
        val bits = readLEUInt32(data, offset)
        return Float.fromBits(bits)
    }

    // BF16 (upper 16 bits of IEEE754 float32) → Float
    private fun bfloat16ToFloat(upper16: Int): Float {
        val bits = upper16 shl 16
        return Float.fromBits(bits)
    }

    private val fpvcS16DecompressTable: ShortArray = shortArrayOf(
        0, 1, 4, 8, 16, 24, 32, 40,
        48, 56, 64, 72, 80, 88, 96, 104,
        113, 122, 131, 140, 149, 158, 167, 176,
        185, 194, 204, 214, 224, 234, 244, 254,
        264, 274, 285, 296, 307, 318, 329, 340,
        352, 364, 376, 388, 400, 413, 426, 439,
        452, 466, 480, 494, 509, 524, 540, 556,
        572, 588, 604, 620, 636, 652, 668, 684,
        700, 716, 732, 748, 764, 780, 796, 812,
        828, 844, 860, 876, 892, 908, 924, 940,
        956, 972, 988, 1004, 1020, 1036, 1052, 1068,
        1084, 1100, 1116, 1132, 1148, 1164, 1180, 1196,
        1212, 1228, 1244, 1260, 1276, 1292, 1308, 1324,
        1340, 1356, 1372, 1388, 1404, 1420, 1436, 1452,
        1468, 1484, 1500, 1516, 1532, 1548, 1564, 1580,
        1596, 1612, 1628, 1644, 1660, 1676, 1692, 1708,
        -1708, -1692, -1676, -1660, -1644, -1628, -1612, -1596,
        -1580, -1564, -1548, -1532, -1516, -1500, -1484, -1468,
        -1452, -1436, -1420, -1404, -1388, -1372, -1356, -1340,
        -1324, -1308, -1292, -1276, -1260, -1244, -1228, -1212,
        -1196, -1180, -1164, -1148, -1132, -1116, -1100, -1084,
        -1068, -1052, -1036, -1020, -1004, -988, -972, -956,
        -940, -924, -908, -892, -876, -860, -844, -828,
        -812, -796, -780, -764, -748, -732, -716, -700,
        -684, -668, -652, -636, -620, -604, -588, -572,
        -556, -540, -524, -509, -494, -480, -466, -452,
        -439, -426, -413, -400, -388, -376, -364, -352,
        -340, -329, -318, -307, -296, -285, -274, -264,
        -254, -244, -234, -224, -214, -204, -194, -185,
        -176, -167, -158, -149, -140, -131, -122, -113,
        -104, -96, -88, -80, -72, -64, -56, -48,
        -40, -32, -24, -16, -8, -4, -1, 0
    )
}
