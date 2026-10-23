import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Signing material is read from keystore.properties, which is gitignored and never
// committed. When the file is absent (e.g. a fresh clone without a key) the release
// build falls back to unsigned, so the project still builds for contributors.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasKeystore = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.qwer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qwer.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 810
        versionName = "0.8.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Build a single-ABI APK with: ./gradlew :app:assembleRelease -PabiFilter=arm64-v8a
        // Needed because hand-repacking a finished APK (removing lib/<abi> from the zip)
        // produces an unaligned package with a compressed resources.arsc, which Android
        // refuses to install. Gradle must do the packaging.
        (project.findProperty("abiFilter") as String?)?.let { abis ->
            ndk { abiFilters += abis.split(",").map { it.trim() } }
        }

        // Domain used for verified App Links. Empty by default (custom scheme only).
        buildConfigField("String", "APP_LINK_HOST", "\"\"")
    }

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
            // All three schemes on purpose: some OEM installers (realme/OPPO, Xiaomi)
            // reject packages that lack v1, and Play requires v2+. Having v1+v2+v3
            // means every installer path is happy.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
            // debuggable=true is a common install blocker on OEM ROMs, so the release
            // build must explicitly clear it.
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // A single universal APK: realme GT 6 is arm64-v8a, but shipping all ABIs keeps
    // one download working on any device the user hands us.
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
        jniLibs {
            // extractNativeLibs=true: the most compatible layout. Some OEM installers
            // (realme/OPPO ColorOS, MIUI) fail silently on APKs that keep .so files
            // uncompressed and expect the loader to map them from the APK.
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = false
    }
}

dependencies {
    // AmneziaWG engine, built locally from amneziawg-android :tunnel (Apache-2.0).
    // Parses plain WireGuard configs too: one engine serves both WG and AWG profiles,
    // which avoids the libwg-go.so / libwg.so packaging collision between the two AARs.
    implementation(files("libs/awg-tunnel.aar"))
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.collection:collection:1.4.5")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.kotlinx.coroutines.android)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
