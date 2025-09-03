package com.appliedrec.veridv2v3migration

internal class Amf3V2PrototypeReader(private val buf: ByteArray) {

    private var p = 0

    // Reference tables per AMF3 spec
    private val stringRefs = mutableListOf<String>()
    private val byteArrayRefs = mutableListOf<ByteArray>()
    private val traitRefs = mutableListOf<Traits>()
    private val objectRefs = mutableListOf<MutableMap<String, Any?>>()

    private data class Traits(
        val className: String,
        val sealedNames: List<String>,
        val dynamic: Boolean,
        val externalizable: Boolean
    )

    fun read(): V2Prototype {
        val marker = readU8()
        return when (marker) {
            0x0C -> { // ByteArray root
                val bytes = readByteArrayInlineOrRef()
                V2Prototype(bytes)
            }
            0x06 -> { // String root -> base64
                val b64 = readStringInlineOrRef()
                V2Prototype(base64Decode(b64))
            }
            0x0A -> { // Object root -> look for "proto"
                val obj = readObject()
                val v = obj["proto"]
                    ?: throw IllegalArgumentException("AMF3 object missing 'proto'")
                when (v) {
                    is ByteArray -> V2Prototype(v)
                    is String    -> V2Prototype(base64Decode(v))
                    else         -> throw IllegalArgumentException("'proto' must be ByteArray or base64 String")
                }
            }
            else -> throw IllegalArgumentException("Unsupported AMF3 root marker: 0x${marker.toString(16)}")
        }
    }

    // --------- Core readers ---------

    private fun readObject(): MutableMap<String, Any?> {
        // U29 traits-info
        val u29 = readU29()
        if ((u29 and 1) == 0) {
            // object reference
            val idx = u29 shr 1
            if (idx !in objectRefs.indices) throw IllegalArgumentException("Bad AMF3 object ref: $idx")
            return objectRefs[idx]
        }

        val inlineTraits = true
        val externalizable = (u29 and 4) != 0
        val dynamic = (u29 and 8) != 0
        val sealedCount = u29 shr 4

        val className = readStringInlineOrRef()
        if (externalizable) {
            // We don't support custom externalizable types here.
            throw IllegalArgumentException("AMF3 externalizable objects not supported")
        }

        val sealedNames = ArrayList<String>(sealedCount)
        repeat(sealedCount) { sealedNames.add(readStringInlineOrRef()) }
        val traits = Traits(className, sealedNames, dynamic, externalizable)
        traitRefs.add(traits)

        val obj = linkedMapOf<String, Any?>()
        objectRefs.add(obj) // add early to allow self-refs

        // sealed values
        for (name in traits.sealedNames) {
            obj[name] = readAny()
        }

        // dynamic values until empty-string name
        if (traits.dynamic) {
            while (true) {
                val name = readStringInlineOrRef()
                if (name.isEmpty()) break
                obj[name] = readAny()
            }
        }

        return obj
    }

    private fun readAny(): Any? {
        return when (val marker = readU8()) {
            0x00 -> null            // undefined
            0x01 -> null            // null
            0x02 -> false
            0x03 -> true
            0x04 -> readAmf3Integer()
            0x05 -> readDouble64()
            0x06 -> readStringInlineOrRef()
            0x08 -> { skipDate(); null } // date, skip
            0x09 -> { skipArray(); null } // array, skip
            0x0A -> readObject()
            0x0B -> { skipXml(); null }   // xml, skip
            0x0C -> readByteArrayInlineOrRef()
            0x0D, 0x0E, 0x0F, 0x10, 0x11 -> { skipVectorOrDict(); null } // skip vectors/dict
            else -> throw IllegalArgumentException("Unsupported AMF3 marker 0x${marker.toString(16)} at pos $p")
        }
    }

    // ---- ByteArray ----
    private fun readByteArrayInlineOrRef(): ByteArray {
        val u29 = readU29()
        if ((u29 and 1) == 0) {
            val idx = u29 shr 1
            if (idx !in byteArrayRefs.indices) throw IllegalArgumentException("Bad AMF3 bytearray ref: $idx")
            return byteArrayRefs[idx]
        }
        val len = u29 shr 1
        val bytes = readBytes(len)
        byteArrayRefs.add(bytes)
        return bytes
    }

    // ---- String ----
    private fun readStringInlineOrRef(): String {
        val u29 = readU29()
        if ((u29 and 1) == 0) {
            val idx = u29 shr 1
            if (idx !in stringRefs.indices) throw IllegalArgumentException("Bad AMF3 string ref: $idx")
            return stringRefs[idx]
        }
        val len = u29 shr 1
        if (len == 0) {
            // Empty string is always interned as well
            stringRefs.add("")
            return ""
        }
        val s = readBytes(len).toString(Charsets.UTF_8)
        stringRefs.add(s)
        return s
    }

    // ---- Primitives & Skips ----

    private fun readAmf3Integer(): Int {
        // AMF3 U29: up to 29-bit signed int (stored as zigzag? No, just 29-bit, sign if value >= 0x10000000 then negative?).
        // Spec: 1..3 bytes with 7 bits each, then final byte with 8 bits.
        var value = 0
        var b = readU8()
        if (b and 0x80 == 0) return b
        value = (b and 0x7F) shl 7
        b = readU8()
        if (b and 0x80 == 0) return value or b
        value = (value or (b and 0x7F)) shl 7
        b = readU8()
        if (b and 0x80 == 0) return value or b
        value = (value or (b and 0x7F)) shl 8
        b = readU8()
        value = value or b
        // sign extension for 29-bit
        if ((value and 0x10000000) != 0) value = value or -0x20000000
        return value
    }

    private fun readDouble64(): Double {
        // IEEE754 64-bit big-endian
        val b = readBytes(8)
        val v = ((b[0].toLong() and 0xFF) shl 56) or
                ((b[1].toLong() and 0xFF) shl 48) or
                ((b[2].toLong() and 0xFF) shl 40) or
                ((b[3].toLong() and 0xFF) shl 32) or
                ((b[4].toLong() and 0xFF) shl 24) or
                ((b[5].toLong() and 0xFF) shl 16) or
                ((b[6].toLong() and 0xFF) shl 8)  or
                (b[7].toLong() and 0xFF)
        return Double.fromBits(v)
    }

    private fun skipDate() {
        val u29 = readU29()
        if ((u29 and 1) == 0) return // ref
        /* millis */ readDouble64()
    }

    private fun skipArray() {
        val u29 = readU29()
        if ((u29 and 1) == 0) return // ref
        val denseLen = u29 shr 1
        // Associative part: string keys until empty string
        while (true) {
            val key = readStringInlineOrRef()
            if (key.isEmpty()) break
            skipAny() // value
        }
        // Dense part
        repeat(denseLen) { skipAny() }
    }

    private fun skipXml() {
        val u29 = readU29()
        if ((u29 and 1) == 0) return // ref
        val len = u29 shr 1
        skip(len)
    }

    private fun skipVectorOrDict() {
        // We won’t parse vectors/dict; consume roughly:
        when (val marker = buf[p - 1]) {
            0x11.toByte() -> { // dictionary
                val u29 = readU29() // length + inline flag
                if ((u29 and 1) == 0) return // ref
                val len = u29 shr 1
                /* weak keys */ readU8()
                repeat(len) { skipAny(); skipAny() }
            }
            else -> {
                // vector<int>, vector<uint>, vector<double>, vector<object>
                val u29 = readU29()
                if ((u29 and 1) == 0) return // ref
                val len = u29 shr 1
                /* fixed */ readU8()
                if (marker == 0x10.toByte()) {
                    // vector<object> has type name
                    readStringInlineOrRef()
                }
                repeat(len) { skipAny() }
            }
        }
    }

    private fun skipAny() { readAny(); /* result ignored */ }

    // ---- Byte ops ----

    private fun readU8(): Int {
        if (p >= buf.size) throw IllegalArgumentException("Unexpected end of AMF3")
        return buf[p++].toInt() and 0xFF
    }

    private fun readU29(): Int {
        var value = 0
        var b = readU8()
        if (b and 0x80 == 0) return b
        value = (b and 0x7F) shl 7

        b = readU8()
        if (b and 0x80 == 0) return value or b
        value = (value or (b and 0x7F)) shl 7

        b = readU8()
        if (b and 0x80 == 0) return value or b
        value = (value or (b and 0x7F)) shl 8

        b = readU8()
        return value or b
    }

    private fun readBytes(n: Int): ByteArray {
        if (n < 0 || p + n > buf.size) throw IllegalArgumentException("Truncated AMF3")
        val out = buf.copyOfRange(p, p + n)
        p += n
        return out
    }

    private fun skip(n: Int) {
        if (n < 0 || p + n > buf.size) throw IllegalArgumentException("Truncated AMF3")
        p += n
    }

    private fun base64Decode(s: String): ByteArray =
        try { java.util.Base64.getDecoder().decode(s.trim()) }
        catch (e: IllegalArgumentException) { throw IllegalArgumentException("Invalid base64 in AMF3 'proto'", e) }
}
