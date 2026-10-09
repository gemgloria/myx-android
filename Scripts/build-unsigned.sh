#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v xcodebuild >/dev/null; then
  echo "需要安装 Xcode 26 或更高版本的 macOS。" >&2
  exit 1
fi
python3 Scripts/prepare-dependencies.py
xcodebuild -project ClearClass.xcodeproj -scheme ClearClass -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
mkdir -p build/ipa/Payload
ditto build/Build/Products/Release-iphoneos/ClearClass.app build/ipa/Payload/ClearClass.app
cd build/ipa
ditto -c -k --sequesterRsrc --keepParent Payload ../ClearClass-unsigned.ipa
python3 ../../Scripts/verify-ipa.py ../ClearClass-unsigned.ipa
echo "已生成 build/ClearClass-unsigned.ipa；必须完整签名后才能安装并使用小组件。"
