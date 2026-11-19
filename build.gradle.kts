// AGP 9 compiles Kotlin itself (built-in Kotlin). AGP 9.5 bundles KGP 2.2.10;
// putting a newer KGP on the build classpath is the supported way to compile with
// the newest stable Kotlin, and the Compose/serialization compiler plugins in the
// version catalog must match it exactly.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.14")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
