# LocalSend iOS

这是 Android 版的原生 SwiftUI 复刻，界面使用 SwiftUI，文件传输继续复用 `locsd_lib` 的 Rust V3 协议，因此可以与仓库内的 Android/Windows 端互传。

## 已实现

- UDP 局域网设备发现与手动刷新
- 系统文件选择器、发送前应用沙盒缓存
- V3 文件接收确认、拒绝、取消和重试
- 分段进度、实时速度、并发 worker 状态
- 接收文件保存在 `Documents/Received`，可通过系统分享菜单导出
- iPhone/iPad 自适应 SwiftUI 界面

## 本地构建

需要 macOS、Xcode 16、Rust 和 XcodeGen：

```sh
brew install xcodegen
rustup target add aarch64-apple-ios aarch64-apple-ios-sim
cd ios
xcodegen generate
open LocalSendIOS.xcodeproj
```

在 Xcode 中选择自己的开发团队后即可真机签名运行。模拟器使用 Apple Silicon 时需要 `aarch64-apple-ios-sim` target。

## GitHub Actions 无签名包

`.github/workflows/ios-unsigned.yml` 会在 `codex/ios` 分支推送时自动构建，并上传：

- `LocalSend-iOS-unsigned.ipa`
- `LocalSend-iOS-unsigned.ipa.sha256`

无签名 IPA 不能通过常规方式直接安装到未越狱设备；需要由测试者自行签名，或使用其已有的测试安装环境。
