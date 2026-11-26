plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("com.humanblackbox.scripts.MainKt")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(kotlin("stdlib"))
}
