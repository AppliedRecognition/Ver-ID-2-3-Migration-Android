package com.appliedrec.veridv2v3migration

import com.appliedrec.facerecognition.dlib.FaceTemplateDlib
import com.appliedrec.facerecognition.dlib.FaceTemplateVersionV16
import com.appliedrec.verid.core2.FaceRecognition
import com.appliedrec.verid.core2.IRecognizable
import com.appliedrec.verid.core2.VerID
import com.appliedrec.verid.core2.VerIDFaceTemplateVersion
import com.appliedrec.verid3.common.FaceTemplate
import com.appliedrec.verid3.common.FaceTemplateVersion
import com.appliedrec.verid3.facerecognition.arcface.core.FaceTemplateArcFace
import com.appliedrec.verid3.facerecognition.arcface.core.FaceTemplateVersionV24
import kotlin.math.sqrt

/**
 * Converts face templates from Ver-ID 2 to Ver-ID 3 types
 *
 * @property verID Ver-ID 2 instance
 */
class FaceTemplateMigration(val verID: VerID) {

    /**
     * Get face templates from Ver-ID 2 converted to Ver-ID 3 types
     *
     * @return List of converted face templates
     */
    suspend fun getConvertedFaceTemplates(): List<FaceTemplate<FaceTemplateVersion<FloatArray>, FloatArray>> {
        return convertFaceTemplates(verID.userManagement.faces.toList())
    }

    /**
     * Get face templates from Ver-ID 2 converted to Ver-ID 3 types filtered by version
     *
     * @param T Ver-ID 3 face template version
     * @param version Face template version by which to filter the templates
     * @return List of converted face templates
     */
    suspend fun <T: FaceTemplateVersion<FloatArray>>getConvertedFaceTemplates(version: T): List<FaceTemplate<T, FloatArray>> {
        return convertFaceTemplates(verID.userManagement.faces.toList(), version)
    }

    /**
     * Convert face templates from Ver-ID 2 to Ver-ID 3 types
     *
     * @param faceTemplates Face templates to convert
     * @return List of converted face templates
     */
    suspend fun convertFaceTemplates(faceTemplates: List<IRecognizable>): List<FaceTemplate<FaceTemplateVersion<FloatArray>, FloatArray>> {
        val v2FaceRec = verID.faceRecognition as FaceRecognition
        return faceTemplates.map { faceTemplate ->
            v3FaceTemplateFromV2(faceTemplate, v2FaceRec)
        }
    }

    /**
     * Convert face templates from Ver-ID 2 to Ver-ID 3 types filtered by version
     *
     * @param T Ver-ID 3 face template version
     * @param faceTemplates Face templates to convert
     * @param version Face template version by which to filter the templates
     * @return List of converted face templates
     */
    suspend fun <T: FaceTemplateVersion<FloatArray>>convertFaceTemplates(faceTemplates: List<IRecognizable>, version: T): List<FaceTemplate<T, FloatArray>> {
        val v2FaceRec = verID.faceRecognition as FaceRecognition
        val v2Version = when (version.id) {
            FaceTemplateVersionV16.id -> VerIDFaceTemplateVersion.V16.serialNumber(false)
            FaceTemplateVersionV24.id -> VerIDFaceTemplateVersion.V24.serialNumber(false)
            else -> throw IllegalArgumentException("Unsupported face template version")
        }
        return faceTemplates.mapNotNull { faceTemplate ->
            if (faceTemplate.version == v2Version) {
                v3FaceTemplateFromV2(faceTemplate, v2FaceRec) as FaceTemplate<T, FloatArray>
            } else {
                null
            }
        }
    }

    private suspend fun v3FaceTemplateFromV2(faceTemplate: IRecognizable, faceRecognition: FaceRecognition): FaceTemplate<FaceTemplateVersion<FloatArray>, FloatArray> {
        val rawTemplate = faceRecognition.getRawFaceTemplate(faceTemplate).also {
            normalize(it)
        }
        return when (VerIDFaceTemplateVersion.fromSerialNumber(faceTemplate.version)) {
            VerIDFaceTemplateVersion.V24 -> {
                FaceTemplateArcFace(rawTemplate) as FaceTemplate<FaceTemplateVersion<FloatArray>, FloatArray>
            }
            VerIDFaceTemplateVersion.V16 -> {
                FaceTemplateDlib(rawTemplate) as FaceTemplate<FaceTemplateVersion<FloatArray>, FloatArray>
            }
            else -> throw IllegalArgumentException("Unsupported face template version")
        }
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.map { it * it }.sum())
        return vector.map { it / norm }.toFloatArray()
    }
}