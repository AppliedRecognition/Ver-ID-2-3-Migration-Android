package com.appliedrec.veridv2v3migration

import com.appliedrec.facerecognition.dlib.FaceTemplateDlib
import com.appliedrec.facerecognition.dlib.FaceTemplateVersionV16
import com.appliedrec.verid3.common.FaceTemplate
import com.appliedrec.verid3.common.FaceTemplateVersion
import com.appliedrec.verid3.facerecognition.arcface.core.FaceTemplateArcFace
import com.appliedrec.verid3.facerecognition.arcface.core.FaceTemplateVersionV24
import kotlinx.serialization.json.Json
import java.util.zip.Inflater
import kotlin.math.sqrt
import kotlin.math.max

// --- Errors ---

sealed class FaceTemplateMigrationError(message: String) : Exception(message) {
    class UnsupportedFaceTemplateVersion(val version: Int)
        : FaceTemplateMigrationError("Unsupported face template version $version")
    object EmptyTemplateData
        : FaceTemplateMigrationError("Face template data is empty")
    object DecompressionFailed
        : FaceTemplateMigrationError("Face template data decompression failed")
    object VectorDeserializationFailed
        : FaceTemplateMigrationError("Failed to deserialize vector")
    object Base64DecodingFailure
        : FaceTemplateMigrationError("Failed to decode base64 string")
    class FaceTemplateVersionMismatch(val expected: Int, val actual: Int)
        : FaceTemplateMigrationError("Expected version $expected template but got version $actual")
}

// --- Port of FaceTemplateMigration ---

class FaceTemplateMigration() {

    companion object {
        @JvmStatic
        val default: FaceTemplateMigration by lazy { FaceTemplateMigration() }
    }

    private val fpvcDecoder = FPVCDecoder()
    private val json = Json { ignoreUnknownKeys = true }

    @Throws(FaceTemplateMigrationError::class)
    fun convertFaceTemplates(legacyFaceTemplates: List<ByteArray>): List<FaceTemplate<FaceTemplateVersion<FloatArray>, FloatArray>> {
        val out = ArrayList<FaceTemplate<FaceTemplateVersion<FloatArray>, FloatArray>>(legacyFaceTemplates.size)
        for (template in legacyFaceTemplates) {
            try {
                val v16: FaceTemplate<FaceTemplateVersion<FloatArray>, FloatArray> = convertFaceTemplate(template,
                    FaceTemplateVersionV16)
                out.add(v16)
            } catch (_: Exception) {
                // fall back to V24
                val v24: FaceTemplate<FaceTemplateVersion<FloatArray>, FloatArray> = convertFaceTemplate(template,
                    FaceTemplateVersionV24)
                out.add(v24)
            }
        }
        return out
    }

    @Throws(FaceTemplateMigrationError::class)
    fun <T : FaceTemplateVersion<FloatArray>> convertFaceTemplates(
        legacyFaceTemplates: List<ByteArray>,
        version: T
    ): List<FaceTemplate<T, FloatArray>> {
        return legacyFaceTemplates.mapNotNull { template ->
            val rawBytes = rawBytesFromTemplate(template)
            if ((rawBytes[0].toInt() and 0xFF) != version.id) {
                null
            } else {
                convertRawTemplateBytes(rawBytes, version)
            }
        }
    }

    @Throws(FaceTemplateMigrationError::class)
    fun <V : FaceTemplateVersion<FloatArray>> convertFaceTemplate(input: ByteArray, version: V): FaceTemplate<V, FloatArray> {
        val data = rawBytesFromTemplate(input)
        return convertRawTemplateBytes(data, version)
    }

    @Throws(FaceTemplateMigrationError::class)
    fun versionOfLegacyFaceTemplate(faceTemplate: ByteArray): Int {
        val data = rawBytesFromTemplate(faceTemplate)
        return data[0].toInt() and 0xFF
    }

    // --- Private helpers ---

    @Throws(FaceTemplateMigrationError::class)
    private fun rawBytesFromTemplate(faceTemplate: ByteArray): ByteArray {
        var data = faceTemplate
        while (isCompressed(data)) {
            data = removeCompression(data)
        }
        if (!isPrototype(data)) {
            val b0 = data.firstOrNull()
            if (b0 != null && (b0 == '{'.code.toByte() || b0 == '"'.code.toByte())) {
                val str = data.decodeToString()
                val v2Prototype = json.decodeFromString<V2Prototype>(str)
                return rawBytesFromTemplate(v2Prototype.proto)
            }
            if (b0 != null && (b0.toInt() and 0xFF) < 32) {
                val v2Prototype = Amf3V2PrototypeReader(data).read()
                return rawBytesFromTemplate(v2Prototype.proto)
            }
            throw FaceTemplateMigrationError.VectorDeserializationFailed
        }
        return data
    }

    @Throws(FaceTemplateMigrationError::class)
    private fun <V : FaceTemplateVersion<FloatArray>> convertRawTemplateBytes(
        data: ByteArray,
        version: V
    ): FaceTemplate<V, FloatArray> {
        if (data.isEmpty()) throw FaceTemplateMigrationError.EmptyTemplateData
        val actualVersion = data[0].toInt() and 0xFF
        if (actualVersion != version.id) {
            throw FaceTemplateMigrationError.FaceTemplateVersionMismatch(expected = version.id, actual = actualVersion)
        }
        val vec = fpvcDecoder.deserializeFPVC(data)
        if (vec.isEmpty) throw FaceTemplateMigrationError.VectorDeserializationFailed

        // scale: float(values[i]) * coeff
        val floats = FloatArray(vec.values.size) { i ->
            vec.values[i].toFloat() * vec.coeff
        }.toMutableList()

        normalize(floats)
        return when (version.id) {
            16 -> FaceTemplateDlib(data = floats.toFloatArray()) as FaceTemplate<V, FloatArray>
            24 -> FaceTemplateArcFace(data = floats.toFloatArray()) as FaceTemplate<V, FloatArray>
            else -> throw FaceTemplateMigrationError.UnsupportedFaceTemplateVersion(version.id)
        }
    }

    // Swift's prototype sniffing
    private fun isPrototype(src: ByteArray): Boolean {
        if (src.size < 4) return false
        val p0 = src[0]
        val p1 = src[1]
        val p2 = src[2]
        val p3 = src[3]
        if ((p0.toInt() and 0xFF) > 16 && (p0.toInt() and 0xFF) < 120 &&
            (p1.toInt() and 0xFF) == 1 && src.size == 132
        ) return true
        val mask: Int = 0xEC
        val p0nz = (p0.toInt() and 0xFF) != 0
        val p2nz = (p2.toInt() and 0xFF) != 0
        val p2maskOk = ((p2.toInt() and 0xFF) and mask) == 0
        val p3nz = (p3.toInt() and 0xFF) != 0
        val p3le2 = (p3.toInt() and 0xFF) <= 2
        return p0nz && p2nz && p2maskOk && p3nz && p3le2
    }

    // zlib header check (CMF/FLG): CM == 8 and (cmf*256 + flg) % 31 == 0
    private fun isCompressed(src: ByteArray): Boolean {
        if (src.size < 2) return false
        val b0 = src[0].toInt() and 0xFF
        val b1 = src[1].toInt() and 0xFF
        val cmfOk = (b0 and 0x0F) == 8
        val header = b0 * 256 + b1
        return cmfOk && (header % 31 == 0)
    }

    @Throws(FaceTemplateMigrationError::class)
    private fun removeCompression(src: ByteArray): ByteArray {
        if (src.isEmpty()) throw FaceTemplateMigrationError.EmptyTemplateData

        // zlib (RFC 1950) inflate (nowrap=false)
        val inflater = Inflater(false)
        try {
            inflater.setInput(src)
            val chunkSize = max(1, src.size)
            val out = ArrayList<Byte>()
            val buffer = ByteArray(chunkSize)
            while (!inflater.finished()) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (e: Exception) {
                    throw FaceTemplateMigrationError.DecompressionFailed
                }
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw FaceTemplateMigrationError.DecompressionFailed
                    }
                } else {
                    for (i in 0 until count) out.add(buffer[i])
                }
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    // --- Norm/normalize using plain Kotlin math ---

    private fun norm(template: List<Float>): Float {
        var acc = 0.0
        for (v in template) {
            acc += (v * v).toDouble()
        }
        return sqrt(acc).toFloat()
    }

    private fun normalize(x: MutableList<Float>) {
        val n = norm(x)
        if (n > 0f) {
            val inv = 1.0f / n
            for (i in x.indices) x[i] = x[i] * inv
        }
    }
}