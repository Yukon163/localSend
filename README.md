# LocalSend Android

这是一个简单好用的局域网文件传输 App。它使用 **Kotlin** 和 **Jetpack Compose** 开发界面，核心传输功能由 **Rust** 强力驱动。

## 主要功能

- **自动发现**：自动找到同一网络下的其他设备。
- **文件传输**：快速发送和接收文件。
- **界面简洁**：现代化的设计，简单易懂。

## 技术概览

- **开发语言**：Kotlin
- **界面框架**：Jetpack Compose (Material3)
- **核心逻辑**：Rust (通过 JNI 调用)

## liblocalsend_core.so 源码

本项目包含一个预编译的 Rust 核心库 (`liblocalsend_core.so`)。
如果你想查看或修改这个核心库的源代码，请访问：
[https://github.com/Yukon163/locsd_lib](https://github.com/Yukon163/locsd_lib)

## 快速开始

1. 克隆本项目到本地
2. 使用 Android Studio 打开
3. 连接 Android 设备（需支持 arm64-v8a 架构）并运行

## 项目结构

```
d:\code\localSend\
├── app\
│   ├── src\
│   │   ├── main\
│   │   │   ├── java\com\yukon\localsend\  # Kotlin 源代码
│   │   │   ├── jniLibs\                   # 预编译的 Rust 库 (.so)
│   │   │   └── res\                       # 资源文件
│   └── build.gradle.kts                   # App 构建配置
├── gradle\                                # Gradle 相关文件
└── settings.gradle.kts                    # 项目设置
```
