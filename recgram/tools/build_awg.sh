#!/usr/bin/env bash
# RecGram: собирает клиент AmneziaWG (recgram/awg, на основе amneziawg-go) как исполняемый файл
# libawg.so для каждого ABI. Файл кладётся в TMessagesProj/src/main/jniLibs/<abi>/ (git-ignored),
# Android распаковывает его в nativeLibraryDir, RecGram запускает его отдельным процессом
# (org.recgram.bypass.Vless). Версия amneziawg-go закреплена в recgram/awg/go.mod и go.sum.
#
#   recgram/tools/build_awg.sh [abi,abi,...]     по умолчанию arm64-v8a,armeabi-v7a,x86,x86_64
set -euo pipefail

ABIS="${1:-arm64-v8a,armeabi-v7a,x86,x86_64}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/TMessagesProj/src/main/jniLibs"
SRC="$ROOT/recgram/awg"

if ! command -v go > /dev/null; then
  [ -x /usr/local/go/bin/go ] && export PATH="/usr/local/go/bin:$PATH" || { echo "Нужен Go" >&2; exit 1; }
fi

IFS=',' read -ra LIST <<< "$ABIS"
for abi in "${LIST[@]}"; do
  case "$abi" in
    arm64-v8a)   goos=android; goarch=arm64; goarm= ;;
    x86_64)      goos=linux;   goarch=amd64; goarm= ;;
    # как и для Xray: android/arm, android/386 и android/amd64 без cgo не собираются
    armeabi-v7a) goos=linux;   goarch=arm;   goarm=7 ;;
    x86)         goos=linux;   goarch=386;   goarm= ;;
    *) echo "Неизвестный ABI: $abi" >&2; exit 1 ;;
  esac
  mkdir -p "$OUT/$abi"
  echo "AmneziaWG → $abi"
  (cd "$SRC" && CGO_ENABLED=0 GOOS=$goos GOARCH=$goarch GOARM=$goarm \
    go build -mod=readonly -trimpath -ldflags "-s -w -buildid=" -o "$OUT/$abi/libawg.so" .)
  ls -l "$OUT/$abi/libawg.so"
done
