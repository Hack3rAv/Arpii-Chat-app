import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.systemutility.app"
    compileSdk = 35

    val localProps = Properties().apply {
        val propsFile = rootProject.file("local.properties")
        if (propsFile.exists()) {
            propsFile.inputStream().use(::load)
        }
    }
    val sdkPath = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: localProps.getProperty("sdk.dir")
    val detectedNdkVersion = sdkPath
        ?.let { file("$it/ndk") }
        ?.takeIf { it.exists() }
        ?.listFiles()
        ?.filter { it.isDirectory && file("${it.absolutePath}/toolchains/llvm").exists() }
        ?.map { it.name }
        ?.sorted()
        ?.let { versions -> versions.lastOrNull { it.startsWith("27.") } ?: versions.lastOrNull() }
    if (!detectedNdkVersion.isNullOrBlank()) {
        ndkVersion = detectedNdkVersion
    }

    defaultConfig {
        applicationId = "com.systemutility.app"
        minSdk = 26
        targetSdk = 35
        resourceConfigurations += listOf("en")
        val propVersionCode = (project.findProperty("SYSTEM_UTILITY_VERSION_CODE")?.toString()
            ?: System.getenv("SYSTEM_UTILITY_VERSION_CODE"))?.toIntOrNull() ?: 10000
        val propVersionName = (project.findProperty("SYSTEM_UTILITY_VERSION_NAME")?.toString()
            ?: System.getenv("SYSTEM_UTILITY_VERSION_NAME"))?.takeIf { it.isNotBlank() } ?: "1.0.0"
        versionCode = propVersionCode
        versionName = propVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseKeystorePath = project.findProperty("SYSTEM_UTILITY_KEYSTORE_PATH")?.toString()
        ?: System.getenv("SYSTEM_UTILITY_KEYSTORE_PATH")
    val releaseKeystorePassword = project.findProperty("SYSTEM_UTILITY_KEYSTORE_PASSWORD")?.toString()
        ?: System.getenv("SYSTEM_UTILITY_KEYSTORE_PASSWORD")
    val releaseKeyAlias = project.findProperty("SYSTEM_UTILITY_KEY_ALIAS")?.toString()
        ?: System.getenv("SYSTEM_UTILITY_KEY_ALIAS")
    val releaseKeyPassword = project.findProperty("SYSTEM_UTILITY_KEY_PASSWORD")?.toString()
        ?: System.getenv("SYSTEM_UTILITY_KEY_PASSWORD")
    val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank() &&
        file(releaseKeystorePath).exists() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    val appModelsFile = rootProject.file("core/src/main/java/com/systemutility/core/model/AppModels.kt")
    val callsEnabledInCode = if (appModelsFile.exists()) {
        Regex("""const\s+val\s+CALLS_ENABLED\s*=\s*(true|false)""")
            .find(appModelsFile.readText())
            ?.groupValues
            ?.get(1)
            ?.toBooleanStrictOrNull() ?: false
    } else {
        false
    }
    val includeWebRtcNative = (project.findProperty("SYSTEM_UTILITY_INCLUDE_WEBRTC_NATIVE")?.toString()
        ?: System.getenv("SYSTEM_UTILITY_INCLUDE_WEBRTC_NATIVE"))?.toBooleanStrictOrNull() ?: callsEnabledInCode

    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "**/libsignal_jni_testing.so"
            excludes += "**/x86/*.so"
            if (!includeWebRtcNative) {
                excludes += "**/libjingle_peerconnection_so.so"
            }
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/*.version"
            excludes += "/META-INF/androidx/**/LICENSE.txt"
            excludes += "/META-INF/**/*.kotlin_module"
            excludes += "DebugProbesKt.bin"
            excludes += "kotlin-tooling-metadata.json"
            excludes += "assets/acknowledgments/libsignal-testing.md"
            excludes += "**/*.dylib"
            excludes += "**/*.dll"
            excludes += "*signal_jni_testing*"
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core"))
    implementation(project(":core-ui"))
    implementation(project(":core-database"))
    implementation(project(":core-network"))
    implementation(project(":core-security"))

    implementation(project(":feature-auth"))
    implementation(project(":feature-chat"))
    implementation(project(":feature-media"))
    implementation(project(":feature-voice"))
    implementation(project(":feature-calls"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-backup"))
    implementation(project(":feature-profile"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.biometric)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
