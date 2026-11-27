plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.luftaquila.hometty"
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        minSdk = 29
        applicationId = "io.luftaquila.hometty"
        targetSdk = 37
        versionCode = 11
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Play upload key, read from Gradle properties (~/.gradle/gradle.properties), never from the
    // project. Without them the release build is unsigned.
    val uploadStoreFile = providers.gradleProperty("homettyUploadStoreFile").orNull
    signingConfigs {
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = file(uploadStoreFile)
                storePassword = providers.gradleProperty("homettyUploadStorePassword").get()
                keyAlias = providers.gradleProperty("homettyUploadKeyAlias").get()
                keyPassword = providers.gradleProperty("homettyUploadKeyPassword").get()
            }
        }
    }
    buildTypes {
        // The debug build installs beside the release one; its name tells them apart.
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "hometty (debug)"
        }
        release {
            manifestPlaceholders["appLabel"] = "hometty"
            isMinifyEnabled = true
            isShrinkResources = true
            if (uploadStoreFile != null) signingConfig = signingConfigs.getByName("upload")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    packaging { jniLibs.useLegacyPackaging = true }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        checkDependencies = true
        // MainActivity handles only keyboard configuration changes; every change that affects
        // resources recreates it, so strings read through LocalContext cannot go stale.
        warning += "LocalContextGetResourceValueCall"
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":resources"))
    implementation(project(":core"))
    implementation(libs.coroutines)
    implementation(libs.android.core)
    implementation(project(":data"))
    implementation(project(":platform"))
    implementation(project(":terminal"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material)
    implementation(libs.activity)
    implementation(libs.lifecycle)
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.android.test)
    androidTestImplementation(libs.android.runner)
    // SettingsSerializer, read by a test, is a DataStore serializer.
    androidTestImplementation(libs.datastore)
    testImplementation(libs.junit)
}
