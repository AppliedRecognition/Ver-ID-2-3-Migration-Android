package com.appliedrec.veridv2v3migration

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appliedrec.facerecognition.dlib.FaceRecognitionDlib
import com.appliedrec.facerecognition.dlib.FaceTemplateVersionV16
import com.appliedrec.verid.core2.FaceRecognition
import com.appliedrec.verid.core2.IRecognizable
import com.appliedrec.verid.core2.Image
import com.appliedrec.verid.core2.VerID
import com.appliedrec.verid.core2.VerIDFaceTemplateVersion
import com.appliedrec.verid.core2.VerIDFactory
import com.appliedrec.verid3.common.FaceTemplate
import com.appliedrec.verid3.common.use
import com.appliedrec.verid3.facerecognition.arcface.cloud.FaceRecognitionArcFace
import com.appliedrec.verid3.facerecognition.arcface.core.FaceTemplateVersionV24
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class FaceTemplateMigrationTest {

    val password = "58F6623F-C2BD-4154-B29E-A7E625179B8F"
    val arcFaceAPIKey = "BK1aJ1vpb08InLtOsdmhL6ZiRDJtq0H89nPqVuzB"
    val arcFaceURL = "https://itnlej3459.execute-api.us-east-1.amazonaws.com/Prod/extract_face_templates"


    @Test
    fun testLoadVerID(): Unit = runBlocking {
        val verID = loadVerID()
        Assert.assertNotNull(verID)
    }

    @Test
    fun testMigrateTemplates(): Unit = runBlocking {
        val verID = loadVerIDWithFaceTemplates()
        val converted = FaceTemplateMigration(verID).getConvertedFaceTemplates()
        Assert.assertEquals(verID.userManagement.faces.size, converted.size)
        Assert.assertTrue(converted.any { it.version == FaceTemplateVersionV16 })
        Assert.assertTrue(converted.any { it.version == FaceTemplateVersionV24 })
        for (template in converted) {
            val norm = sqrt(template.data.map { it * it }.sum())
            Assert.assertEquals(1f, norm, 0.001f)
        }
    }

    @Test
    fun testMigrateTemplatesByVersion(): Unit = runBlocking {
        val verID = loadVerIDWithFaceTemplates()
        val convertedV16 = FaceTemplateMigration(verID).getConvertedFaceTemplates(FaceTemplateVersionV16)
        Assert.assertEquals(verID.userManagement.getFaces(VerIDFaceTemplateVersion.V16).size, convertedV16.size)
        val convertedV24 = FaceTemplateMigration(verID).getConvertedFaceTemplates(FaceTemplateVersionV24)
        Assert.assertEquals(verID.userManagement.getFaces(VerIDFaceTemplateVersion.V24).size, convertedV24.size)
    }

    @Test
    fun testCompareMigratedTemplates(): Unit = runBlocking {
        val verID = loadVerIDWithFaceTemplates()
        val v2FaceRec = verID.faceRecognition as FaceRecognition
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val v2Templates = mutableMapOf<VerIDFaceTemplateVersion, MutableList<Pair<String, IRecognizable>>>()
        for (user in verID.userManagement.users) {
            for (face in verID.userManagement.getFacesOfUser(user)) {
                val version = VerIDFaceTemplateVersion.fromSerialNumber(face.version)
                v2Templates.getOrPut(version) { mutableListOf() }.add(user to face)
            }
        }
        FaceRecognitionDlib.create(context).use { faceRecognitionDlib ->
            FaceRecognitionArcFace(arcFaceAPIKey, arcFaceURL).use { faceRecognitionArcFace ->
                val migration = FaceTemplateMigration(verID)
                val comparisonResults = mutableListOf<ComparisonResult>()
                for ((version, templates) in v2Templates) {
                    for (i in 0 until templates.size) {
                        for (j in 1 until templates.size - 1) {
                            val template1 = templates[i].second
                            val template2 = templates[j].second
                            if (template1 == template2) {
                                continue
                            }
                            val subject1 = templates[i].first
                            val subject2 = templates[j].first
                            val v2Score = v2FaceRec.compareSubjectFacesToFaces(
                                arrayOf(template1),
                                arrayOf(template2)
                            )
                            val v2Passed = v2Score >= v2FaceRec.getAuthenticationThreshold(version)
                            val (v3Score, v3Threshold) = when (version) {
                                VerIDFaceTemplateVersion.V16 -> {
                                    val v3Templates: List<FaceTemplate<FaceTemplateVersionV16, FloatArray>> =
                                        migration.convertFaceTemplates(
                                            listOf(
                                                template1,
                                                template2
                                            ),
                                            FaceTemplateVersionV16
                                        )
                                    faceRecognitionDlib.compareFaceRecognitionTemplates(
                                        listOf(
                                            v3Templates[0]
                                        ), v3Templates[1]
                                    ).first() to faceRecognitionDlib.defaultThreshold
                                }
                                VerIDFaceTemplateVersion.V24 -> {
                                    val v3Templates: List<FaceTemplate<FaceTemplateVersionV24, FloatArray>> =
                                        migration.convertFaceTemplates(
                                            listOf(
                                                template1,
                                                template2
                                            ),
                                            FaceTemplateVersionV24
                                        )
                                    faceRecognitionArcFace.compareFaceRecognitionTemplates(
                                        listOf(
                                            v3Templates[0]
                                        ), v3Templates[1]
                                    ).first() to faceRecognitionArcFace.defaultThreshold
                                }
                                else -> {
                                    throw IllegalArgumentException("Unsupported version")
                                }
                            }
                            val v3Passed = v3Score >= v3Threshold
                            comparisonResults.add(
                                ComparisonResult(
                                    subject1,
                                    subject2,
                                    v2Score,
                                    v2Passed,
                                    v3Score,
                                    v3Passed,
                                    if (version == VerIDFaceTemplateVersion.V16) 16 else 24
                                )
                            )
                        }
                    }
                }
                for (result in comparisonResults) {
                    Assert.assertEquals(result.v2Passed, result.v3Passed)
                    if (result.subject1 == result.subject2) {
                        Assert.assertTrue(result.v2Passed)
                        Assert.assertTrue(result.v3Passed)
                    } else {
                        Assert.assertFalse(result.v2Passed)
                        Assert.assertFalse(result.v3Passed)
                    }
                }
            }
        }
    }

    private suspend fun loadVerID(): VerID {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return VerIDFactory(context, password).createVerIDSync()
    }

    private fun loadImage(name: String): Image {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bitmap = context.assets.open("${name}.jpg").use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        }
        return Image(bitmap)
    }

    private suspend fun loadVerIDWithFaceTemplates(): VerID {
        val verID = loadVerID()
        verID.userManagement.deleteAllFaces()
        val names = listOf("subject1" to "-01", "subject1" to "-02", "subject2" to "-01")
        for ((name, suffix) in names) {
            val templates = createV2FaceTemplates(name + suffix, verID).values
            verID.userManagement.assignFacesToUser(templates.toTypedArray(), name)
        }
        return verID
    }

    private fun createV2FaceTemplates(name: String, verID: VerID): Map<VerIDFaceTemplateVersion,IRecognizable> {
        val image = loadImage(name)
        val rec = verID.faceRecognition as FaceRecognition
        val face = verID.faceDetection.detectFacesInImage(image, 1, 0).first()
        val map = mutableMapOf<VerIDFaceTemplateVersion,IRecognizable>()
        for (version in setOf(VerIDFaceTemplateVersion.V16, VerIDFaceTemplateVersion.V24)) {
            map.put(version, rec.createRecognizableFacesFromFaces(arrayOf(face), image, version).first())
        }
        return map.toMap()
    }
}

private enum class VerIDVersion {
    V2, V3
}

private data class ComparisonResult(
    val subject1: String,
    val subject2: String,
    val v2Score: Float,
    val v2Passed: Boolean,
    val v3Score: Float,
    val v3Passed: Boolean,
    val faceTemplateVersion: Int
)