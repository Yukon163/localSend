import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val locsdLibDir = rootProject.layout.projectDirectory.dir("locsd_lib").asFile
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile

data class RustAndroidTarget(
    val abi: String,
    val triple: String,
    val linkerName: String,
    val linkerEnv: String,
)

val rustAndroidTargets = listOf(
    RustAndroidTarget(
        abi = "arm64-v8a",
        triple = "aarch64-linux-android",
        linkerName = "aarch64-linux-android24-clang",
        linkerEnv = "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER",
    )
)

fun localProperties(): Properties {
    val properties = Properties()
    val file = rootProject.layout.projectDirectory.file("local.properties").asFile
    if (file.exists()) {
        file.inputStream().use(properties::load)
    }
    return properties
}

fun androidNdkDir(): File {
    val properties = localProperties()
    properties.getProperty("ndk.dir")?.let { return File(it) }

    val sdkDir = properties.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Android SDK not found. Set sdk.dir in local.properties or ANDROID_HOME.")

    val ndkRoot = File(sdkDir, "ndk")
    return ndkRoot.listFiles(File::isDirectory)
        ?.maxByOrNull { it.name }
        ?: error("Android NDK not found under $ndkRoot.")
}

fun clangExecutable(target: RustAndroidTarget): String {
    val osName = System.getProperty("os.name")
    val hostTag = when {
        osName.contains("Windows", ignoreCase = true) -> "windows-x86_64"
        osName.contains("Mac", ignoreCase = true) -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val suffix = if (hostTag.startsWith("windows")) ".cmd" else ""
    return File(androidNdkDir(), "toolchains/llvm/prebuilt/$hostTag/bin/${target.linkerName}$suffix").absolutePath
}

fun llvmArExecutable(): String {
    val osName = System.getProperty("os.name")
    val hostTag = when {
        osName.contains("Windows", ignoreCase = true) -> "windows-x86_64"
        osName.contains("Mac", ignoreCase = true) -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val suffix = if (hostTag.startsWith("windows")) ".exe" else ""
    return File(androidNdkDir(), "toolchains/llvm/prebuilt/$hostTag/bin/llvm-ar$suffix").absolutePath
}

android {
    namespace = "com.yukon.localsend"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.yukon.localsend"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".codex"
            versionNameSuffix = "-codex-pipeline"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }


}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val copyLocsdLibTasks = rustAndroidTargets.map { target ->
    val capitalizedAbi = target.abi
        .split("-", "_")
        .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    val cargoBuildTask = tasks.register<Exec>("cargoBuildLocsdLib$capitalizedAbi") {
        group = "build"
        description = "Build locsd_lib for ${target.abi}."
        workingDir = locsdLibDir
        commandLine(
            "cargo",
            "build",
            "--release",
            "--features",
            "lib",
            "--target",
            target.triple,
            "--lib",
        )
        environment(target.linkerEnv, clangExecutable(target))
        environment("CC_${target.triple.replace('-', '_')}", clangExecutable(target))
        environment("AR_${target.triple.replace('-', '_')}", llvmArExecutable())
        inputs.files(fileTree(locsdLibDir.resolve("src")))
        inputs.file(locsdLibDir.resolve("Cargo.toml"))
        inputs.file(locsdLibDir.resolve("Cargo.lock"))
        outputs.file(locsdLibDir.resolve("target/${target.triple}/release/liblocalsend_core.so"))
    }

    tasks.register<Copy>("copyLocsdLib$capitalizedAbi") {
        group = "build"
        description = "Copy locsd_lib ${target.abi} shared library into app jniLibs."
        dependsOn(cargoBuildTask)
        from(locsdLibDir.resolve("target/${target.triple}/release/liblocalsend_core.so"))
        into(File(jniLibsDir, target.abi))
    }
}

tasks.register("buildLocsdLibAndroid") {
    group = "build"
    description = "Build locsd_lib Android shared libraries for all packaged ABIs."
    dependsOn(copyLocsdLibTasks)
}

tasks.matching { it.name.matches(Regex("merge.*JniLibFolders")) }.configureEach {
    dependsOn("buildLocsdLibAndroid")
}

dependencies {
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
