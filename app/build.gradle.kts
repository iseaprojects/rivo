plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Two extractor flavors, mirroring the two data backends described in the README:
 *
 *  - fast  : NewPipeExtractor only. Small APK, sub-second browse/search, YouTube-only.
 *  - ytdlp : NewPipeExtractor + youtubedl-android (CPython bundled). Non-YouTube sites,
 *            cookies, PO tokens, full yt-dlp option parity. ~30-50 MB larger APK and a
 *            multi-second interpreter warm-up on first use.
 *
 * Everything above the VideoExtractor interface is flavor-agnostic.
 */
val appVersion = "0.8.7-rivo.4"

android {
    namespace = "io.github.iseaprojects.rivo"

    // Android 17 (API 37). Compile against the newest platform the toolchain
    // supports: AGP 9.5 caps out at 37 and requires Build Tools 36+.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.iseaprojects.rivo"
        minSdk = 26
        targetSdk = 37
        versionCode = 807004
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "RIVO_VERSION", "\"$appVersion\"")
        buildConfigField("String", "UPSTREAM_VERSION", "\"0.8.7\"")
        buildConfigField("String", "UPSTREAM_REPO", "\"https://github.com/Benexl/yt-x\"")
        buildConfigField("String", "UPSTREAM_LICENSE", "\"MIT\"")
        buildConfigField("String", "UPDATE_MANIFEST", "\"https://api.github.com/repos/iseaprojects/rivo/releases\"")
        buildConfigField("String", "NEWPIPE_VERSION", "\"v0.27.1\"")
    }

    flavorDimensions += "extractor"
    productFlavors {
        create("fast") {
            dimension = "extractor"
            buildConfigField("String", "EXTRACTOR_FLAVOR", "\"fast\"")
            versionNameSuffix = "-fast"
            resValue("string", "extractor_flavor_label", "NewPipeExtractor")
        }
        create("ytdlp") {
            dimension = "extractor"
            buildConfigField("String", "EXTRACTOR_FLAVOR", "\"ytdlp\"")
            versionNameSuffix = "-ytdlp"
            resValue("string", "extractor_flavor_label", "yt-dlp")
        }
    }

    // Release signing is wired from environment variables so that no keystore ever
    // lands in the repository. Builds without them stay unsigned and installable
    // only after re-signing, which is exactly what the F-Droid/APK flow expects.
    val releaseSigning = System.getenv("RIVO_KEYSTORE")?.let { store ->
        signingConfigs.create("release") {
            storeFile = file(store)
            storePassword = System.getenv("RIVO_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RIVO_KEY_ALIAS")
            keyPassword = System.getenv("RIVO_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = releaseSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // Flavors expose `extractor_flavor_label` via resValue; AGP 9 defaults it off.
        resValues = true
    }

    packaging {
        // youtubedl-android opens `libpython.zip.so` from the app's native library
        // directory and unpacks CPython from it. With the modern (uncompressed,
        // page-aligned) packaging that file is only mapped from inside the APK, never
        // materialised as a file, so `init` fails with "failed to initialize".
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    androidResources {
        // Language packs and themes are read as JSON assets, never compiled.
        noCompress += "json"
        // `resourceConfigurations` was removed in AGP 9 in favour of localeFilters.
        localeFilters += listOf("en", "es", "pt-rBR")
    }

    lint {
        warningsAsErrors = false
        abortOnError = false
        checkReleaseBuilds = false
        // ObsoleteSdkInt: adaptive-icon never resolves when it lives in a plain
        // mipmap-anydpi folder, even with minSdk 26 -- AAPT2 drops it.
        // UnusedResources: the F5-F7 resources (tile icon, widget info, automotive
        // descriptor) are shipped with their phase and referenced from that phase on.
        disable += setOf(
            "ObsoleteSdkInt",
            "GradleDependency",
            "NewerVersionAvailable",
            "UnusedResources",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        // jvmTarget follows android.compileOptions.targetCompatibility (17) under
        // built-in Kotlin; only language opt-ins need to be spelled out here.
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    // NewPipeExtractor is present in both flavors and its backend lives in `main`, so it
    // is a plain implementation dependency rather than a per-flavor one.
    implementation(libs.newpipe.extractor)
    "ytdlpImplementation"(libs.youtubedl.android)
    "ytdlpImplementation"(libs.youtubedl.android.aria2)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
}
