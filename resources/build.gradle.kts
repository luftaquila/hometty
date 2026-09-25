plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "io.luftaquila.hometty.resources"
    compileSdk = 37
    compileSdkMinor = 2
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation(project(":core")) }
