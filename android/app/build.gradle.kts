import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val locsdLibDir = rootProject.layout.projectDirectory.dir("../locsd_lib").asFile.absoluteFile
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
    return File(
        androidNdkDir(),
        "toolchains/llvm/prebuilt/$hostTag/bin/${target.linkerName}$suffix",
    ).absolutePath
}

android {
    namespace = "com.yukon.localsend.flutter"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.yukon.localsend.flutter"
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
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
        inputs.files(fileTree(locsdLibDir.resolve("src")))
        inputs.file(locsdLibDir.resolve("Cargo.toml"))
        inputs.file(locsdLibDir.resolve("Cargo.lock"))
        outputs.file(locsdLibDir.resolve("target/${target.triple}/release/liblocalsend_core.so"))
    }

    tasks.register<Copy>("copyLocsdLib$capitalizedAbi") {
        group = "build"
        description = "Copy locsd_lib ${target.abi} shared library into Flutter jniLibs."
        dependsOn(cargoBuildTask)
        from(locsdLibDir.resolve("target/${target.triple}/release/liblocalsend_core.so"))
        into(File(jniLibsDir, target.abi))
    }
}

tasks.register("buildLocsdLibAndroid") {
    group = "build"
    description = "Build locsd_lib Android shared libraries for packaged ABIs."
    dependsOn(copyLocsdLibTasks)
}

tasks.matching {
    it.name.matches(Regex("merge.*JniLibFolders")) ||
        it.name.matches(Regex("merge.*NativeLibs"))
}.configureEach {
    dependsOn("buildLocsdLibAndroid")
}
