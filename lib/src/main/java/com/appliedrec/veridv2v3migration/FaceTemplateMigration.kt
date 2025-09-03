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

class FaceTemplateMigration() {

    companion object {
        @JvmStatic
        val default: FaceTemplateMigration by lazy { FaceTemplateMigration() }
    }

    private val fpvcDecoder = FPVCDecoder()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Convert face templates from legacy format to new format.
     *
     * @param legacyFaceTemplates List of legacy face template data, this can be obtained from
     * `IRecognizable`'s `recognitionData` property in Ver-ID 2.
     * @return List of face templates in new format.
     */
    @Throws(FaceTemplateMigrationException::class)
    fun convertFaceTemplates(
        legacyFaceTemplates: List<ByteArray>
    ): List<FaceTemplate<FaceTemplateVersion<FloatArray>, FloatArray>> {
        val out = mutableListOf<FaceTemplate<FaceTemplateVersion<FloatArray>, FloatArray>>()
        for (template in legacyFaceTemplates) {
            val rawBytes = rawBytesFromTemplate(template)
            val version = rawBytes[0].toInt() and 0xFF
            when (version) {
                16 -> {
                    val v16: FaceTemplate<FaceTemplateVersion<FloatArray>, FloatArray> =
                        convertRawTemplateBytes(rawBytes, FaceTemplateVersionV16)
                    out.add(v16)
                }
                24 -> {
                    val v24: FaceTemplate<FaceTemplateVersion<FloatArray>, FloatArray> =
                        convertRawTemplateBytes(rawBytes, FaceTemplateVersionV24)
                    out.add(v24)
                }
                else -> throw FaceTemplateMigrationException.UnsupportedFaceTemplateVersion(version)
            }
        }
        return out
    }

    /**
     * Convert face templates by version
     *
     * Note: Face templates with versions other than the specified version will be ignored.
     *
     * @param V Face template version
     * @param legacyFaceTemplates List of legacy face template data, this can be obtained from
     * `IRecognizable`'s `recognitionData` property in Ver-ID 2.
     * @param version Face template version
     * @return List of face templates in new format.
     */
    @Throws(FaceTemplateMigrationException::class)
    fun <V : FaceTemplateVersion<FloatArray>> convertFaceTemplates(
        legacyFaceTemplates: List<ByteArray>,
        version: V
    ): List<FaceTemplate<V, FloatArray>> {
        return legacyFaceTemplates.mapNotNull { template ->
            val rawBytes = rawBytesFromTemplate(template)
            if ((rawBytes[0].toInt() and 0xFF) != version.id) {
                null
            } else {
                convertRawTemplateBytes(rawBytes, version)
            }
        }
    }

    /**
     * Convert a single face template
     *
     * @param V Face template version
     * @param input Legacy face template data, which can be obtained from `IRecognizable`'s
     * `recognitionData` property in Ver-ID 2.
     * @param version Face template version
     * @return Face template in new format.
     */
    @Throws(FaceTemplateMigrationException::class)
    fun <V : FaceTemplateVersion<FloatArray>> convertFaceTemplate(
        input: ByteArray,
        version: V
    ): FaceTemplate<V, FloatArray> {
        val data = rawBytesFromTemplate(input)
        return convertRawTemplateBytes(data, version)
    }

    /**
     * Get the numeric version of a legacy face template.
     *
     * @param faceTemplate Legacy face template data, which can be obtained from `IRecognizable`'s
     * recognitionData` property in Ver-ID 2.
     * @return Face template version (either 16 or 24)
     */
    @Throws(FaceTemplateMigrationException::class)
    fun versionOfLegacyFaceTemplate(faceTemplate: ByteArray): Int {
        val data = rawBytesFromTemplate(faceTemplate)
        return data[0].toInt() and 0xFF
    }

    @Throws(FaceTemplateMigrationException::class)
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
            throw FaceTemplateMigrationException.VectorDeserializationFailed
        }
        return data
    }

    @Throws(FaceTemplateMigrationException::class)
    private fun <V : FaceTemplateVersion<FloatArray>> convertRawTemplateBytes(
        data: ByteArray,
        version: V
    ): FaceTemplate<V, FloatArray> {
        if (data.isEmpty()) throw FaceTemplateMigrationException.EmptyTemplateData
        val actualVersion = data[0].toInt() and 0xFF
        if (actualVersion != version.id) {
            throw FaceTemplateMigrationException.FaceTemplateVersionMismatch(expected = version.id, actual = actualVersion)
        }
        val vec = fpvcDecoder.deserializeFPVC(data)
        if (vec.isEmpty) throw FaceTemplateMigrationException.VectorDeserializationFailed
        val floats = FloatArray(vec.values.size) { i ->
            vec.values[i].toFloat() * vec.coeff
        }.toMutableList()

        normalize(floats)
        return when (version.id) {
            16 -> FaceTemplateDlib(data = floats.toFloatArray()) as FaceTemplate<V, FloatArray>
            24 -> FaceTemplateArcFace(data = floats.toFloatArray()) as FaceTemplate<V, FloatArray>
            else -> throw FaceTemplateMigrationException.UnsupportedFaceTemplateVersion(version.id)
        }
    }

    private fun isPrototype(src: ByteArray): Boolean {
        if (src.size < 4) return false
        val p0 = src[0]
        val p1 = src[1]
        val p2 = src[2]
        val p3 = src[3]
        if ((p0.toInt() and 0xFF) > 16 && (p0.toInt() and 0xFF) < 120 &&
            (p1.toInt() and 0xFF) == 1 && src.size == 132
        ) return true
        val mask = 0xEC
        val p0nz = (p0.toInt() and 0xFF) != 0
        val p2nz = (p2.toInt() and 0xFF) != 0
        val p2maskOk = ((p2.toInt() and 0xFF) and mask) == 0
        val p3nz = (p3.toInt() and 0xFF) != 0
        val p3le2 = (p3.toInt() and 0xFF) <= 2
        return p0nz && p2nz && p2maskOk && p3nz && p3le2
    }

    private fun isCompressed(src: ByteArray): Boolean {
        if (src.size < 2) return false
        val b0 = src[0].toInt() and 0xFF
        val b1 = src[1].toInt() and 0xFF
        val cmfOk = (b0 and 0x0F) == 8
        val header = b0 * 256 + b1
        return cmfOk && (header % 31 == 0)
    }

    @Throws(FaceTemplateMigrationException::class)
    private fun removeCompression(src: ByteArray): ByteArray {
        if (src.isEmpty()) throw FaceTemplateMigrationException.EmptyTemplateData
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
                    throw FaceTemplateMigrationException.DecompressionFailed
                }
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw FaceTemplateMigrationException.DecompressionFailed
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