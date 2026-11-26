plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("org.graalvm.buildtools.native") version "0.13.0" apply false
}

// ---------------------------------------------------------------------------
// Human BlackBox v2 — root build.
//
// The root project is a Gradle multi-module Android build. It is deliberately
// NOT a monolithic app: every AI modality is a first-class library module that
// can be built, tested, quantised and shipped independently of the app.
//
//   :core      shared types, config, crypto, logging, work scheduling
//   :sensors   SensorManager wrapper, foreground service, wake locks
//   :ml-audio  Perch-style audio embedder + custom head
//   :ml-motion TCN + GRU + temporal attention motion model
//   :ml-vision YOLO11-nano + pose keypoints
//   :ml-fusion learned explainable multimodal temporal fusion
//   :data      Room/encrypted SQLite, DataStore, evidence store, uploads
//   :cloud     Firebase FCM + Firestore + Auth, Docker/Kubernetes only
//   :testing   unit + instrumented tests, benchmarking harness
//   :scripts   non-Android Kotlin helpers (feature extraction, augmentation,
//               training, TFLite conversion/quantisation)
//   :app       the Android application that wires the modules together
//
// `./gradlew` is the single entry point: assemble, unit-test, lint, and
// `:scripts:assemble` produces the TFLite artefacts.
// ---------------------------------------------------------------------------

tasks.register("printModules") {
    doLast {
        println("Human BlackBox v2 modules: core sensors ml-audio ml-motion ml-vision ml-fusion data cloud testing scripts app")
    }
}