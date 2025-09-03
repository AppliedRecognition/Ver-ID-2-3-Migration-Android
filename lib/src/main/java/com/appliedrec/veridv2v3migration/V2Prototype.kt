package com.appliedrec.veridv2v3migration

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject


@Serializable(with = V2PrototypeSerializer::class)
internal data class V2Prototype(val proto: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is V2Prototype) return false

        if (!proto.contentEquals(other.proto)) return false

        return true
    }

    override fun hashCode(): Int {
        return proto.contentHashCode()
    }
}

internal class V2PrototypeSerializer : KSerializer<V2Prototype> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("V2Prototype") {
            element("proto", PrimitiveSerialDescriptor("Base64", PrimitiveKind.STRING))
        }

    override fun deserialize(decoder: Decoder): V2Prototype {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("V2PrototypeSerializer supports JSON only")

        return when (val node = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!node.isString) throw SerializationException("Expected JSON string or object")
                V2Prototype(decodeBase64(node.content))
            }
            is JsonObject -> {
                val protoEl = node["proto"] ?: throw SerializationException("Missing 'proto'")
                V2Prototype(decodeProtoField(protoEl))
            }
            else -> throw SerializationException("Expected JSON string or object")
        }
    }

    override fun serialize(encoder: Encoder, value: V2Prototype) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("V2PrototypeSerializer supports JSON only")

        // Default shape: {"proto":"<base64>"}
        val obj = buildJsonObject {
            put("proto", JsonPrimitive(encodeBase64(value.proto)))
        }
        jsonEncoder.encodeJsonElement(obj)
    }

    // --- helpers ---

    private fun decodeProtoField(el: JsonElement): ByteArray = when (el) {
        is JsonPrimitive -> {
            if (!el.isString) throw SerializationException("'proto' must be base64 string or byte array")
            decodeBase64(el.content)
        }
        is JsonArray -> parseByteArray(el)
        else -> throw SerializationException("'proto' must be base64 string or byte array")
    }

    private fun parseByteArray(arr: JsonArray): ByteArray {
        val out = ByteArray(arr.size)
        var i = 0
        for (e in arr) {
            val p = e as? JsonPrimitive
                ?: throw SerializationException("Byte array must contain numbers")
            val s = p.content
            val n = s.toIntOrNull()
                ?: throw SerializationException("Invalid byte value: $s")
            if (n !in 0..255) throw SerializationException("Byte value out of range: $n")
            out[i++] = n.toByte()
        }
        return out
    }

    // JVM Base64; swap for a multiplatform impl if needed.
    private fun decodeBase64(s: String): ByteArray =
        try {
            java.util.Base64.getDecoder().decode(s.trim())
        } catch (e: IllegalArgumentException) {
            throw SerializationException("Invalid base64", e)
        }

    private fun encodeBase64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)
}