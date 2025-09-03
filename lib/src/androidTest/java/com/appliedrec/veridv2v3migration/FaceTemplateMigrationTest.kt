package com.appliedrec.veridv2v3migration

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appliedrec.facerecognition.dlib.FaceRecognitionDlib
import com.appliedrec.facerecognition.dlib.FaceTemplateVersionV16
import com.appliedrec.verid3.facerecognition.arcface.core.FaceTemplateVersionV24
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class FaceTemplateMigrationTest {

    val testFaceTemplates: Map<String,List<String>> = mapOf(
        "subject1" to listOf(
            "CgsBC3Byb3RvDIIZEAAQAYAAAABGabc4FUFGSdxP4USmRTktyNPeCTAhwSUZ3Mq/ASI7zj2nFLsx8dLPswk6otFAPeIQJ9lgJbInJVx/Lqgz79E2ssRBOrDF7kDSAVI5Js0dN7Q/vsdc0MzkL78G2bbRQzkv5fNSA+1MVw2t8ZgvPRFPxNcmTj3mfTSo+yzlG7wpq9StziYJdXVpZAwhgNp9OEzMcORFlGzYez1zrAE=",
            "CgsBC3Byb3RvDIIZEAAQAYAAAAAAfrE4HzAgNIBU2Q6lVkj7wBS/Eu42tx4rvR7M8B0xzGaBE6f1wcuwtdVBu+BYM8kpMuNMFsgcEXJKJc8lA+A7BvZLIsG+J0vlPD7vIeg/Cec0q70s17i7NrTy6aqpXFVA2yQ6vRZXPPq3PJY4K+9Czt4iLjrYbzWc1icBLBRDsMqhyzMJdXVpZAwhbQESeTuJUDEpsoeC66G+FwE=",
            "CgsBC3Byb3RvDIIZEAAQAYAAAABhxM44Thf7Ew9d6e0i+R1RzSj1CwoyxMIMB9Hx1Wbu9VK+wbQx/AjI1OVK7A9BT9Ub7QpGR+Aj+V5mLIDPLDVf480iVLvc4BHwI0Mw8OFEHPQ5yuQ36vHaIBcSIvb6PUgmJe5N1csbIgvJOq4FRyI4shoEaUneTBLB7RvvL8Q1vcC5LSMJdXVpZAwhSIUACdvEny4HeBjhUSgFgwE=",
            "CgsBC3Byb3RvDIIZGAAQAYAAAADZtMo616TpgEG6sg0/CYlZ4M85uQfJwS8HuMg7oOkE28wlLbkh9OBXBlsrYuEH4wm3luzVuk4cps3YtkjJUiTp6R8+OtI3G6E/FB0Lng/LKablO7TXFb3y7VAu/d0RYC3BLvy1XJ7W9foYz0/QuSzcSgU5kBQTwLYxaUCY3FDH0PJTBbYJdXVpZAwh3geQw7/S2nBtHvbCde5sFgE="
        ),
        "subject2" to listOf(
            "CgsBC3Byb3RvDIIZGAAQAYAAAAA1fqo6NsnjqZifFVPg0CKlmk0wtu1bJ9rQ/0mvX8AYjyo5KxRPOTpTfy3nlftLOKReMKkRxp0a0Cv7A0xYIzf80lLFIbcKKMj21FE+1cxLIjXOVzrBtOoiF7hn+eEyHN3URdkTja4xc6sTZiPUUrcTAu/m1MY0JNguLR8trS3VxTrWp2kJdXVpZAwhqs8iJmqSX5dTH7Qqk2t/oQE="
        )
    )

    @Test
    fun testConvertTemplates() {
        val templates = testFaceTemplates.flatMap { entry ->
            entry.value.map { Base64.decode(it, Base64.DEFAULT) }
        }
        val converted = FaceTemplateMigration.default.convertFaceTemplates(templates)
        converted.filter { it.version == FaceTemplateVersionV16 }
        Assert.assertEquals(templates.size, converted.size)
    }

    @Test
    fun testCompareConvertedFaceTemplates(): Unit = runBlocking {
        val templates = testFaceTemplates.flatMap { entry ->
            entry.value.map { entry.key to Base64.decode(it, Base64.DEFAULT) }
        }
        val v16Templates = templates.mapNotNull { (subject, template) ->
            if (FaceTemplateMigration.default.versionOfLegacyFaceTemplate(template) == 16) {
                subject to FaceTemplateMigration.default.convertFaceTemplate(template, FaceTemplateVersionV16)
            } else {
                null
            }
        }
        val v24Templates = templates.mapNotNull { (subject, template) ->
            if (FaceTemplateMigration.default.versionOfLegacyFaceTemplate(template) == 24) {
                subject to FaceTemplateMigration.default.convertFaceTemplate(template, FaceTemplateVersionV24)
            } else {
                null
            }
        }
        val faceRecognitionDlib = FaceRecognitionDlib.create(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        for (i in 0 until v16Templates.size - 1) {
            for (j in 1 until v16Templates.size) {
                if (v16Templates[i].second != v16Templates[j].second) {
                    val score = faceRecognitionDlib.compareFaceRecognitionTemplates(
                        listOf(v16Templates[i].second),
                        v16Templates[j].second
                    ).first()
                    if (v16Templates[i].first == v16Templates[j].first) {
                        Assert.assertTrue(score >= faceRecognitionDlib.defaultThreshold)
                    } else {
                        Assert.assertTrue(score < faceRecognitionDlib.defaultThreshold)
                    }
                }
            }
        }
        val faceRecognitionArcFace = MockFaceRecognitionArcFace()
        for (i in 0 until v24Templates.size - 1) {
            for (j in 1 until v24Templates.size) {
                if (v24Templates[i].second != v24Templates[j].second) {
                    val score = faceRecognitionArcFace.compareFaceRecognitionTemplates(
                        listOf(v24Templates[i].second),
                        v24Templates[j].second
                    ).first()
                    if (v24Templates[i].first == v24Templates[j].first) {
                        Assert.assertTrue(score >= faceRecognitionDlib.defaultThreshold)
                    } else {
                        Assert.assertTrue(score < faceRecognitionDlib.defaultThreshold)
                    }
                }
            }
        }
    }

    @Test
    fun testConvertFaceTemplatesByVersion() {
        val templates = testFaceTemplates.flatMap { entry ->
            entry.value.map { Base64.decode(it, Base64.DEFAULT) }
        }
        val v16s = FaceTemplateMigration.default.convertFaceTemplates(
            templates, FaceTemplateVersionV16)
        val v24s = FaceTemplateMigration.default.convertFaceTemplates(
            templates, FaceTemplateVersionV24)
        Assert.assertEquals(templates.size, v16s.size + v24s.size)
    }
}