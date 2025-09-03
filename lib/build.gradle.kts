import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.dokka.gradle.DokkaTaskPartial

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
    signing
}

version = "1.0.0"

android {
    namespace = "com.appliedrec.veridv2v3migration"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            enableAndroidTestCoverage = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugaring)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.verid2)
    implementation(platform(libs.verid.bom))
    implementation(libs.face.recognition.arcface)
    implementation(libs.face.recognition.dlib)
    implementation(libs.kotlinx.serialization)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

mavenPublishing {
    coordinates("com.appliedrec", "verid-2-3-migration")
    pom {
        name.set("Ver-ID 2 to 3 migration")
        description.set("Utility for migrating from Ver-ID 2 to Ver-ID 3 SDK")
        url.set("https://github.com/AppliedRecognition/Ver-ID-2-3-Migration-Android")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/AppliedRecognition/Ver-ID-2-3-Migration-Android.git")
            developerConnection.set("scm:git:ssh://github.com/AppliedRecognition/Ver-ID-2-3-Migration-Android.git")
            url.set("https://github.com/AppliedRecognition/Ver-ID-2-3-Migration-Android")
        }
        developers {
            developer {
                id.set("appliedrecognition")
                name.set("Applied Recognition Corp.")
                email.set("support@appliedrecognition.com")
            }
        }
    }
    publishToMavenCentral(automaticRelease = true)
}

signing {
    useGpgCmd()
    sign(publishing.publications)
}

dokka {
    dokkaPublications.html {
        outputDirectory.set(rootProject.file("docs"))
    }
}

tasks.withType<DokkaTaskPartial>().configureEach {
    moduleName.set("Ver-ID 2 to 3 migration")
    moduleVersion.set(project.version.toString())
}