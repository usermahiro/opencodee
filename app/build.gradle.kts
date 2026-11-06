import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing: keystore/keystore.properties + keystore/release.jks live in this (private) repo.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "com.celoext"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.celoext"
        minSdk = 26
        // 28 on purpose: proot runs binaries from the app data directory,
        // and with targetSdk >= 29 Android forbids exec from app_data_file (same as Termux).
        // The APK is distributed as a file, not through Play.
        targetSdk = 28
        // gradle.properties holds the version; CI overrides both with -P flags (run number).
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 36
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.0.2 beta-1"
        ndk { abiFilters += "arm64-v8a" }
        // Repo whose GitHub releases the in-app updater checks. Set `updateRepo=owner/name` in gradle.properties.
        val updateRepo = (project.findProperty("updateRepo") as String?) ?: "usermahiro/opencodee"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file("keystore/" + keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // targetSdk 28: see above; not an error for an APK outside Play
    lint { disable += "ExpiredTargetSdkVersion" }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Own release key from keystore/ (falls back to the debug key only if keystore.properties is missing).
            signingConfig = if (keystoreProps.getProperty("storeFile") != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        // proot and its libraries ship as lib*.so and must be extracted to disk,
        // otherwise they can't be run as executables.
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/*.so"
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.gms.code.scanner)
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
}
