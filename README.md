# LocalSend Flutter

局域网文件传输 Flutter 前端，传输核心来自 `locsd_lib/` Git submodule。

这个分支的仓库根目录就是 Flutter 工程，可以直接在根目录运行 Flutter 命令。
旧的 Kotlin/Compose Android app 已从此分支移除。

## 克隆

```powershell
git clone --recurse-submodules <repo-url>
```

如果已经克隆过主仓：

```powershell
git submodule update --init --recursive
```

## 运行

准备 Flutter、Rust、Android SDK/NDK 后：

```powershell
cd D:\code\localSend
rustup target add aarch64-linux-android
flutter pub get
flutter run
```

指定 Android 真机：

```powershell
flutter run -d 461QYGDP22FXS
```

指定 Windows 桌面：

```powershell
flutter run -d windows
```

## 构建

Android debug 包：

```powershell
flutter build apk --debug
```

Windows exe：

```powershell
flutter build windows
```

Android 和 Windows 构建都会自动先编译 `locsd_lib`，不需要手动先构建子仓。
Android 目前只打包 `arm64-v8a`，不做 `armeabi-v7a` 适配。

## 旧 PC 程序

旧的 `localsend_app.exe` 来自 `locsd_lib` 的 Rust `bin` target：

```powershell
cd locsd_lib
cargo build --release --features bin --bin localsend_app
```

产物位于 `locsd_lib\target\release\localsend_app.exe`。
