# Ver-ID 2 to 3 Migration

Utility that helps migrating from Ver-ID SDK version 2.* to Ver-ID SDK version 3+.

## Background

Ver-ID SDK version 2 serializes face templates in a custom format. Ver-ID SDK version 3 no longer stores the face templates and it leaves the face template serialization to the SDK consumer.

This utility helps decoding the version 2 face templates to the data structures used in the Ver-ID SDK 3 face recognition classes.

## Installation

Add the following dependency in your build file.

```kotlin
implementation("com.appliedrec:verid-2-3-migration:2.0.0")
```

## Usage

### Converting v16 (Dlib) and v24 (ArcFace) in one call

```kotlin
// Get templates from Ver-ID 2
val templates: List<ByteArray> = verID.userManagement.getFaces().map { 
    it.recognitionData 
}

// Convert all templates
val converted: List<FaceTemplate<FaceTemplateVersion<FloatArray>, FloatArray>> = 
    FaceTemplateMigration.default.convertFaceTemplates(templates)

// Filter templates by version
val v16Templates = converted.filter { it.version == FaceTemplateVersionV16 }
val v24Templates = converted.filter { it.version == FaceTemplateVersionV24 }
```

### Converting face templates by version

```kotlin
// Get v24 templates from Ver-ID 2
val templates: List<IRecognizable> = verID.userManagement
    .faces(VerIDFaceTemplateVersion.V24).map { 
        it.recognitionData
    }

// Convert v24 templates
// Note that face templates with other versions will be ignored
val v24Templates: List<FaceTemplate<FaceTemplateVersionV24, FloatArray>> = 
    FaceTemplateMigration.default.convertFaceTemplates(templates, FaceTemplateVersion24)
```

### Converting a single face template

```kotlin
val template: IRecognizable // Your Ver-ID 2 template
val v2TemplateVersion = VerIDFaceTemplateVersion.fromSerialNumber(template.version)

if (v2TemplateVersion == VerIDFaceTemplateVersion.V16) {
    val v16Template: FaceTemplate<FaceTemplateVersionV16, FloatArray> = 
        FaceTemplateMigration.default.convertFaceTemplate(
            template.recognitionData, 
            FaceTemplateVersionV16
        )
} else if (v2TemplateVersion == VerIDFaceTemplateVersion.V24) {
    val v24Template: FaceTemplate<FaceTemplateVersionV24, FloatArray> =
        FaceTemplateMigration.default.convertFaceTemplate(
            template.recognitionData,
            FaceTemplateVersionV24
        )
}
```

### Getting face template version

```kotlin
val templateData: ByteArray // Ver-ID 2 template data
val version: Int = FaceTemplateMigration.default.versionOfLegacyFaceTemplate(templateData)
// version will be either 16 or 24
```