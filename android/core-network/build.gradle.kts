plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.systemutility.core.network"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        val defaultBaseUrl = project.findProperty("SYSTEM_UTILITY_DEFAULT_BASE_URL")?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: "https://system-utility-backend.avrodipff.workers.dev"
        val defaultGithubRepo = project.findProperty("SYSTEM_UTILITY_GITHUB_REPO")?.toString()
            ?.trim()
            .orEmpty()
        buildConfigField("String", "DEFAULT_BASE_URL", "\"${defaultBaseUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "DEFAULT_GITHUB_REPO", "\"${defaultGithubRepo.replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    api(project(":core"))
    implementation(project(":core-security"))

    api(libs.okhttp)
    implementation(libs.okhttp.logging)
    api(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
