# Ver-ID 2 to 3 Migration

Utility that helps migrating from Ver-ID SDK version 2 to Ver-ID SDK version 3.

## Installation

Add the following dependency in your build file.

```kotlin
implementation("com.appliedrec:verid-2-3-migration:1.0.0")
```

## Usage examples

Get all Ver-ID 2 templates

```kotlin
coroutineScope.launch(Dispatchers.Default) {
    // Load Ver-ID 2
    val verID = VerIDFactory(context).loadVerIDSync()
    
    // Create a FaceTemplateMigration instance
    val migration = FaceTemplateMigration(verID)
    
    // Get templates from Ver-ID 2
    val templates = migration.getConvertedFaceTemplates()
}
```

Get templates by version

```kotlin
coroutineScope.launch(Dispatchers.Default) {
    // Load Ver-ID 2
    val verID = VerIDFactory(context).loadVerIDSync()
    
    // Create a FaceTemplateMigration instance
    val migration = FaceTemplateMigration(verID)
    
    // Get V24 templates from Ver-ID 2
    val v24Templates = migration.getConvertedFaceTemplates(FaceTemplateVersion24)
    // You can use the templates with FaceRecognitionArcFace
    
    // Get V16 templates from Ver-ID 2
    val v16Templates = migration.getConvertedFaceTemplates(FaceTemplateVersion16)
    // You can use the templates with FaceRecognitionDlib
}
```