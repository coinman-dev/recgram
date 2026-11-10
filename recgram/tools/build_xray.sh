#!/usr/bin/env bash
# RecGram: собирает Xray-core (VLESS/Reality/xhttp) как исполняемый файл libxray.so для каждого ABI.
# Файл кладётся в TMessagesProj/src/main/jniLibs/<abi>/ (git-ignored), Android распаковывает его
# в nativeLibraryDir, откуда RecGram запускает его отдельным процессом (org.recgram.bypass.Vless).
#
#   recgram/tools/build_xray.sh [abi,abi,...]     по умолчанию arm64-v8a,armeabi-v7a,x86,x86_64
#
# Нужен Go (версия — из go.mod Xray-core; GOTOOLCHAIN=auto скачает нужную). Исходники — тег XRAY_TAG.
set -euo pipefail

XRAY_TAG="${XRAY_TAG:-v26.11.9}"
ABIS="${1:-arm64-v8a,armeabi-v7a,x86,x86_64}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/TMessagesProj/src/main/jniLibs"
SRC="${XRAY_SRC:-${XDG_CACHE_HOME:-$HOME/.cache}/recgram-xray/Xray-core-$XRAY_TAG}"

if ! command -v go > /dev/null; then
  [ -x /usr/local/go/bin/go ] && export PATH="/usr/local/go/bin:$PATH" || { echo "Нужен Go" >&2; exit 1; }
fi

if [ ! -f "$SRC/go.mod" ]; then
  rm -rf "$SRC"
  mkdir -p "$(dirname "$SRC")"
  git -c advice.detachedHead=false clone --depth 1 --branch "$XRAY_TAG" https://github.com/XTLS/Xray-core.git "$SRC"
fi

# -checklinkname=0: github.com/wlynxg/anet (интерфейсы сети на Android) ссылается на внутренности net через linkname
COMMIT="$(git -C "$SRC" rev-parse --short HEAD)"
LDFLAGS="-s -w -buildid= -checklinkname=0 -X github.com/xtls/xray-core/core.build=$COMMIT"

IFS=',' read -ra LIST <<< "$ABIS"
for abi in "${LIST[@]}"; do
  case "$abi" in
    arm64-v8a)   goos=android; goarch=arm64; goarm= ;;
    x86_64)      goos=linux;   goarch=amd64; goarm= ;;
    # android/arm, android/386 и android/amd64 без cgo не собираются; статический linux-файл на Android запускается так же
    armeabi-v7a) goos=linux;   goarch=arm;   goarm=7 ;;
    x86)         goos=linux;   goarch=386;   goarm= ;;
    *) echo "Неизвестный ABI: $abi" >&2; exit 1 ;;
  esac
  mkdir -p "$OUT/$abi"
  echo "Xray $XRAY_TAG ($COMMIT) → $abi"
  (cd "$SRC" && CGO_ENABLED=0 GOOS=$goos GOARCH=$goarch GOARM=$goarm \
    go build -trimpath -ldflags "$LDFLAGS" -o "$OUT/$abi/libxray.so" ./main)
  ls -l "$OUT/$abi/libxray.so"
done
