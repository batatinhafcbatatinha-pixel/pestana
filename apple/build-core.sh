#!/bin/sh
# Builds the Rust core as static libraries for the Apple targets:
#   build/ios/libpestana.a    (iPhone/iPad, arm64)
#   build/macos/libpestana.a  (Mac, universal arm64 + x86_64)
set -eu
here=$(cd "$(dirname "$0")" && pwd)
cd "$here/../core"
export IPHONEOS_DEPLOYMENT_TARGET=16.0
export MACOSX_DEPLOYMENT_TARGET=13.0
for t in aarch64-apple-ios aarch64-apple-darwin x86_64-apple-darwin; do
  cargo build --release --lib --target "$t"
done
mkdir -p "$here/build/ios" "$here/build/macos"
cp target/aarch64-apple-ios/release/libpestana.a "$here/build/ios/"
lipo -create \
  target/aarch64-apple-darwin/release/libpestana.a \
  target/x86_64-apple-darwin/release/libpestana.a \
  -output "$here/build/macos/libpestana.a"
