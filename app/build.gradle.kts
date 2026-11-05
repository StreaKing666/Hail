import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    val signingProps = file("../signing.properties")
    val signingProperties = Properties().apply {
        if (signingProps.exists()) signingProps.inputStream().use { load(it) }
    }
    val releaseStoreFile = System.getenv("HAIL_SIGNING_KEYSTORE_PATH")
        ?: signingProperties.getProperty("storeFile")
    val releaseStorePassword = System.getenv("HAIL_SIGNING_STORE_PASSWORD")
        ?: signingProperties.getProperty("storePassword")
    val releaseKeyAlias = System.getenv("HAIL_SIGNING_KEY_ALIAS")
        ?: signingProperties.getProperty("keyAlias")
    val releaseKeyPassword = System.getenv("HAIL_SIGNING_KEY_PASSWORD")
        ?: signingProperties.getProperty("keyPassword")
    val releaseSigningReady = listOf(
        releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
    ).all { !it.isNullOrBlank() }
    if (System.getenv("HAIL_REQUIRE_RELEASE_SIGNING") == "true" && !releaseSigningReady) {
        throw GradleException(
            "Release signing is required. Configure HAIL_SIGNING_* environment variables or signing.properties."
        )
    }

    val commitHash = providers.exec {
        workingDir = rootDir
        commandLine = "git rev-parse --short HEAD".split(" ")
    }.standardOutput.asText.get().trim()
    val commitSubject = providers.exec {
        workingDir = rootDir
        commandLine = "git log -1 --pretty=%s".split(" ")
    }.standardOutput.asText.get().trim()

    val originalPackage = providers.gradleProperty("hailOriginalPackage").getOrElse("false") == "true"

    namespace = "com.aistra.hail"
    compileSdk = 36

    defaultConfig {
        applicationId = if (originalPackage) "com.aistra.hail" else "com.aistra.hail.enhanced"
        minSdk = 23
        targetSdk = 36
        versionCode = 111014
        versionName = "1.11.0-enhanced.14" + if (originalPackage) "-original-package" else ""
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-g$commitHash"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (!commitSubject.startsWith("[release]")) versionNameSuffix = "-g$commitHash"
            signingConfig = if (releaseSigningReady) {
                signingConfigs.create("release") {
                    storeFile = file(releaseStoreFile!!)
                    storePassword = releaseStorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }
            } else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }
    androidResources {
        generateLocaleConfig = true
        // Do not compress the dex files, so the apk can be imported as a privileged app
        noCompress += "dex"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach {
            if (it is com.android.build.api.variant.impl.VariantOutputImpl)
                it.outputFileName = "Hail-Enhanced-v${it.versionName.get()}.apk"
        }
    }
}
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.pinyin4j)
    implementation(libs.material)
    implementation(libs.insetter)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.dhizuku.api)
    implementation(libs.appiconloader)
    implementation(libs.compose.preference)
    implementation(libs.commons.text)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hiddenapibypass)
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
