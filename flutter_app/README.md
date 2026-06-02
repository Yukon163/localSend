# LocalSend Flutter

Flutter frontend for the LocalSend Rust core in `../locsd_lib`.

## Features

- LAN device discovery through the Rust core.
- Receive server with configurable save directory.
- Send files to a discovered device or a manually typed target IP.
- Separate send and receive progress from native FFI events.

## Build

Install Flutter, Rust, and the Android NDK first.

```powershell
rustup target add aarch64-linux-android
flutter pub get
```

Android:

```powershell
flutter build apk --debug
```

Windows:

```powershell
flutter build windows
```

Both Android and Windows builds compile `../locsd_lib` automatically before
packaging. Android only packages `arm64-v8a`.
