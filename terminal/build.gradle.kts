import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.luftaquila.hometty.terminal"
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    ndkVersion = "28.2.13676358"
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") } }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":resources"))
    implementation(project(":core"))
    implementation(libs.coroutines)
}

// The script keeps its source and object caches next to jniLibs, so it builds into a fixed
// directory; each variant then gets a copy as a generated jniLibs source directory.
val busyBoxLibs = layout.buildDirectory.dir("busybox/jniLibs")
val buildBusyBox =
    tasks.register<Exec>("buildBusyBox") {
        inputs.files(
            rootProject.file("busybox/build.py"),
            rootProject.file("busybox/sources.json"),
            rootProject.file("busybox/hometty.patch"),
            rootProject.file("busybox/config.fragment"),
            rootProject.file("busybox/Android.mk"),
            rootProject.file("busybox/Application.mk"),
            rootProject.file("busybox/README.md"),
        )
        inputs.property("ndkVersion", android.ndkVersion)
        outputs.dir(busyBoxLibs)
        outputs.file(layout.buildDirectory.file("busybox/source.tar.gz"))
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        commandLine(
            "python3",
            rootProject.file("busybox/build.py"),
            "--ndk",
            "$sdk/ndk/28.2.13676358",
            "--output",
            busyBoxLibs.get().asFile.parentFile,
        )
    }

abstract class CopyBusyBoxLibs : DefaultTask() {
    @get:InputDirectory abstract val built: DirectoryProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @get:Inject abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(built)
            into(output)
        }
    }
}

androidComponents.onVariants { variant ->
    val copy =
        tasks.register<CopyBusyBoxLibs>("copy${variant.name.replaceFirstChar(Char::uppercase)}BusyBox") {
            built.set(busyBoxLibs)
            dependsOn(buildBusyBox)
        }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(copy, CopyBusyBoxLibs::output)
}
