pluginManagement {
    repositories {
        google {
            content {
                includeGroupBy("com.android.tools.build", "gradle")
                includeGroupBy("org.jetbrains.kotlin", "kotlin_gradle_plugin")
                includeGroupBy("org.graalvm", "gradle")
                includeGroupBy("io.ktor", "ktor")
                includeGroupBy("org.jetbrains.kotlinx", "kotlinx")
                includeGroupBy("com.google.firebase", "firebase")
                includeGroupBy("com.google.android.libraries", "ml")
                includeGroupBy("com.google.android.libraries", "tensorflowlite")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(org.gradle.api.initialization.resolve.RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupBy("com.android.tools.build", "gradle")
                includeGroupBy("org.jetbrains.kotlin", "kotlin_gradle_plugin")
                includeGroupBy("org.graalvm", "gradle")
                includeGroupBy("io.ktor", "ktor")
                includeGroupBy("org.jetbrains.kotlinx", "kotlinx")
                includeGroupBy("com.google.firebase", "firebase")
                includeGroupBy("com.google.android.libraries", "ml")
                includeGroupBy("com.google.android.libraries", "tensorflowlite")
                includeGroupBy("com.google.android.libraries", "tensorflowlite-runtime-kotlin")
                includeGroupBy("com.google.android.libraries", "tensorflow-lite")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.android.kotlin.parcel") version "8.5.2" apply false
    id("org.graalvm.buildtools.native") version "0.13.0" apply false
}

include(
    ":core",
    ":sensors",
    ":ml-audio",
    ":ml-motion",
    ":ml-vision",
    ":ml-fusion",
    ":data",
    ":cloud",
    ":testing",
    ":scripts",
    ":app",
)

enableFeaturePreview("ALL_CAPS")