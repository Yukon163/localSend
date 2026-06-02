# LocalSend

局域网文件传输项目。当前仓库包含两个前端：

- `app/`：原 Android Kotlin + Jetpack Compose 前端，通过 JNI 调用 Rust core。
- `flutter_app/`：Flutter Android/Windows 前端，通过 Dart FFI 调用 Rust core。

Rust 传输核心作为 Git submodule 放在 `locsd_lib/`。

## 克隆

```powershell
git clone --recurse-submodules <repo-url>
```

如果已经克隆过主仓：

```powershell
git submodule update --init --recursive
```

## Flutter 前端

准备 Flutter、Rust、Android SDK/NDK 后，在 `flutter_app/` 下构建。

```powershell
cd flutter_app
rustup target add aarch64-linux-android
flutter pub get
```

Android debug 包：

```powershell
flutter build apk --debug
```

Windows exe：

```powershell
flutter build windows
```

Flutter Android 和 Windows 构建都会自动先编译 `../locsd_lib`，不需要手动先构建子仓。
Android 目前只打包 `arm64-v8a`，不做 `armeabi-v7a` 适配。

## 原 Android 前端

用 Android Studio 打开主仓根目录并运行 `app`。Gradle 会在合并 JNI 库前自动构建
`locsd_lib` 的 Android `liblocalsend_core.so`，同样只构建 `arm64-v8a`。

也可以命令行构建：

```powershell
.\gradlew.bat :app:assembleDebug
```

## PC 旧版程序

旧的 `localsend_app.exe` 来自 `locsd_lib` 的 Rust `bin` target：

```powershell
cd locsd_lib
cargo build --release --features bin --bin localsend_app
```

产物位于 `locsd_lib\target\release\localsend_app.exe`。
