package com.appliedrec.veridv2v3migration

sealed class FaceTemplateMigrationException(message: String) : Exception(message) {
    class UnsupportedFaceTemplateVersion(val version: Int)
        : FaceTemplateMigrationException("Unsupported face template version $version")
    object EmptyTemplateData
        : FaceTemplateMigrationException("Face template data is empty")
    object DecompressionFailed
        : FaceTemplateMigrationException("Face template data decompression failed")
    object VectorDeserializationFailed
        : FaceTemplateMigrationException("Failed to deserialize vector")
    class FaceTemplateVersionMismatch(val expected: Int, val actual: Int)
        : FaceTemplateMigrationException("Expected version $expected template but got version $actual")
}