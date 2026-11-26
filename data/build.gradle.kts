plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.luftaquila.hometty.data"
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines)
    implementation(libs.datastore)
    implementation(libs.serialization)
    implementation(libs.room)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
