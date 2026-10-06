#!/bin/sh
set -eu

REPOSITORY_ROOT="$(cd "$SRCROOT/.." && pwd)"
MANIFEST_PATH="$REPOSITORY_ROOT/locsd_lib/Cargo.toml"

ARCH="${CURRENT_ARCH:-}"
if [ -z "$ARCH" ] || [ "$ARCH" = "undefined_arch" ]; then
  ARCH="${NATIVE_ARCH_ACTUAL:-arm64}"
fi

case "${PLATFORM_NAME:-iphoneos}:$ARCH" in
  iphoneos:arm64)
    RUST_TARGET="aarch64-apple-ios"
    ;;
  iphonesimulator:arm64)
    RUST_TARGET="aarch64-apple-ios-sim"
    ;;
  iphonesimulator:x86_64)
    RUST_TARGET="x86_64-apple-ios"
    ;;
  *)
    echo "error: Unsupported Apple target ${PLATFORM_NAME:-unknown}:$ARCH"
    exit 1
    ;;
esac

if ! command -v cargo >/dev/null 2>&1; then
  echo "error: Rust is required. Install it from https://rustup.rs before building."
  exit 1
fi

if ! rustup target list --installed | grep -qx "$RUST_TARGET"; then
  echo "error: Missing Rust target $RUST_TARGET"
  echo "error: Run: rustup target add $RUST_TARGET"
  exit 1
fi

cargo build \
  --manifest-path "$MANIFEST_PATH" \
  --target "$RUST_TARGET" \
  --release \
  --features lib \
  --lib

mkdir -p "$BUILT_PRODUCTS_DIR"
cp \
  "$REPOSITORY_ROOT/locsd_lib/target/$RUST_TARGET/release/liblocalsend_core.a" \
  "$BUILT_PRODUCTS_DIR/liblocalsend_core.a"
