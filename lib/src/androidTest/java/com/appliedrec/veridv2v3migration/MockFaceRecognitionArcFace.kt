package com.appliedrec.veridv2v3migration

import com.appliedrec.verid3.common.Face
import com.appliedrec.verid3.common.FaceTemplate
import com.appliedrec.verid3.common.IImage
import com.appliedrec.verid3.facerecognition.arcface.core.FaceRecognitionArcFaceCore
import com.appliedrec.verid3.facerecognition.arcface.core.FaceTemplateVersionV24

/**
 * Face recognition class that extends ArcFace Core. It can be used for face template
 * comparison but trying to extract face template from image will throw a runtime exception.
 */
class MockFaceRecognitionArcFace : FaceRecognitionArcFaceCore() {

    override suspend fun createFaceRecognitionTemplates(
        faces: List<Face>,
        image: IImage
    ): List<FaceTemplate<FaceTemplateVersionV24, FloatArray>> {
        throw RuntimeException("Method not implemented")
    }
}