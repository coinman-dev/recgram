#!/usr/bin/env python3
"""
Сборка release-APK RecGram из Windows: код берётся с GitHub, собирается в WSL.

Запуск (из D:\\Development\\recgram):
    python -I recgram/tools/build_apk.py              arm64-v8a → builds/RecGram-release-arm64.apk
    python -I recgram/tools/build_apk.py --install    то же и установить на телефон (install_phone.py)
    python -I recgram/tools/build_apk.py --abis arm64-v8a,armeabi-v7a   → builds/RecGram-release.apk
    python -I recgram/tools/build_apk.py --xray       пересобрать Xray и AmneziaWG, даже если они уже есть
    python -I recgram/tools/build_apk.py --force      собрать, даже если есть незапушенные правки

Что делает:
1. Проверяет рабочую копию: WSL собирает то, что лежит в origin/main, поэтому незакоммиченные
   и незапушенные правки в APK не попадут — скрипт останавливается и подсказывает, что сделать.
2. В WSL (~/Development/recgram): git pull --ff-only, обновление подмодулей.
3. Xray-core (VLESS): recgram/tools/build_xray.sh, если libxray.so нет для нужного ABI
   или в скрипте сменилась версия (XRAY_TAG); AmneziaWG: recgram/tools/build_awg.sh, если нет libawg.so
   или сменились исходники recgram/awg.
4. Gradle :TMessagesProj_App:assembleAfatRelease — на экран только основные шаги и ошибки,
   полный журнал — ~/Development/recgram-logs/build.log в WSL.
5. Копирует APK в builds/ и печатает его размер и SHA-256.

Ключ подписи берётся из local.properties в WSL-копии (см. secrets/README.md).
"""
import argparse
import hashlib
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
WSL_REPO = "/home/modulator/Development/recgram"
WSL_LOG = "/home/modulator/Development/recgram-logs/build.log"
APK_IN_WSL = WSL_REPO + "/TMessagesProj_App/build/outputs/apk/afat/release/app.apk"


def run(cmd, **kw):
    return subprocess.run(cmd, cwd=ROOT, stdin=subprocess.DEVNULL, **kw)


def git(*a):
    r = run(["git", *a], capture_output=True, text=True, encoding="utf-8")
    return r.stdout.strip()


def wsl_bash(script, stream=False):
    """Скрипт bash в WSL. stream=True — печатать вывод построчно, вернуть код выхода.

    Скрипт идёт через stdin: в аргументах командной строки wsl.exe раскрывает $(…) и $var сам,
    ещё в Windows-каталоге, до запуска bash."""
    cmd = ["wsl", "--", "bash", "-l", "-s"]
    # байтами: в текстовом режиме Windows превратит \n в \r\n, и bash споткнётся о \r
    data = (script.replace("\r", "") + "\n").encode("utf-8")
    if not stream:
        r = subprocess.run(cmd, cwd=ROOT, input=data, capture_output=True)
        return r.returncode, (r.stdout + r.stderr).decode("utf-8", "replace")
    p = subprocess.Popen(cmd, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    p.stdin.write(data)
    p.stdin.close()
    for line in p.stdout:
        print("   " + line.decode("utf-8", "replace").rstrip(), flush=True)
    return p.wait(), ""


def check_local(force):
    """Всё ли, что есть в D:, уже на GitHub."""
    run(["git", "fetch", "-q", "origin"])
    problems = []
    dirty = [l for l in git("status", "--porcelain").splitlines() if l.strip()]
    if dirty:
        problems.append("незакоммиченные изменения:\n      " + "\n      ".join(dirty[:15])
                        + ("\n      …" if len(dirty) > 15 else ""))
    branch = git("rev-parse", "--abbrev-ref", "HEAD")
    if branch != "main":
        problems.append(f"текущая ветка {branch}, а WSL собирает main")
    ahead = git("rev-list", "--count", "origin/main..HEAD") or "0"
    if ahead != "0":
        problems.append(f"{ahead} коммит(ов) не запушено — сделайте git push")
    if problems:
        print("⚠ В сборку попадёт только то, что лежит на GitHub (origin/main). Сейчас:")
        for p in problems:
            print("   • " + p)
        if not force:
            print("Закоммитьте и запушьте правки, либо запустите с --force, чтобы собрать без них.")
            sys.exit(2)
        print("   --force: собираю без этих правок.\n")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="Сборка release-APK RecGram в WSL")
    ap.add_argument("--abis", default="arm64-v8a", help="ABI через запятую (по умолчанию arm64-v8a)")
    ap.add_argument("--install", action="store_true", help="после сборки установить на телефон")
    ap.add_argument("--xray", action="store_true", help="пересобрать Xray-core принудительно")
    ap.add_argument("--force", action="store_true", help="собирать, даже если есть незапушенные правки")
    a = ap.parse_args()
    abis = [x.strip() for x in a.abis.split(",") if x.strip()]
    for abi in abis:
        if abi not in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
            sys.exit(f"Неизвестный ABI: {abi}")
    started = time.time()

    print("1/5 Проверка рабочей копии")
    check_local(a.force)
    print("   на GitHub:", git("log", "--oneline", "-1", "origin/main"))

    print("2/5 WSL: обновление исходников")
    code, out = wsl_bash("pgrep -f '[G]radleWrapperMain' >/dev/null && echo BUSY || true")
    if "BUSY" in out:
        sys.exit("В WSL уже идёт сборка Gradle — дождитесь её окончания и запустите снова.")
    code, _ = wsl_bash(f"""set -e
cd {WSL_REPO}
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "В WSL-копии есть изменённые файлы — откладываю их в git stash:"
  git status --short --untracked-files=no
  git stash push -q -m "build_apk.py $(date +%F_%T)"
fi
git fetch -q origin
git checkout -q main
git merge -q --ff-only origin/main
git submodule update --init --recursive -q
git log --oneline -1""", stream=True)
    if code:
        sys.exit("Не удалось обновить WSL-копию (см. вывод выше).")

    print("3/5 Xray-core (VLESS) и AmneziaWG")
    tag_line = (ROOT / "recgram/tools/build_xray.sh").read_text(encoding="utf-8")
    m = re.search(r'XRAY_TAG="\$\{XRAY_TAG:-([^}]+)\}"', tag_line)
    tag = m.group(1) if m else "?"
    stamp = f"{WSL_REPO}/TMessagesProj/src/main/jniLibs/.xray-{tag}"
    need = []
    for abi in abis:
        code, _ = wsl_bash(f"test -s {WSL_REPO}/TMessagesProj/src/main/jniLibs/{abi}/libxray.so && test -f {stamp}-{abi}")
        if code or a.xray:
            need.append(abi)
    if need:
        print(f"   собираю Xray {tag} для {', '.join(need)}")
        code, _ = wsl_bash(f"""set -eo pipefail
cd {WSL_REPO}
bash recgram/tools/build_xray.sh {','.join(need)} 2>&1 | {{ grep -v '^go: downloading' || true; }}
for abi in {' '.join(need)}; do rm -f TMessagesProj/src/main/jniLibs/.xray-*-$abi; touch {stamp}-$abi; done""", stream=True)
        if code:
            sys.exit("Xray не собрался (см. вывод выше).")
    else:
        print(f"   Xray {tag} уже собран для {', '.join(abis)}")

    # AmneziaWG: пересобираем, если сменились исходники recgram/awg (метка — хэш main.go, go.mod, go.sum)
    h = hashlib.sha256()
    for f in ("main.go", "go.mod", "go.sum"):
        h.update((ROOT / "recgram/awg" / f).read_bytes())
    awg = h.hexdigest()[:12]
    awg_stamp = f"{WSL_REPO}/TMessagesProj/src/main/jniLibs/.awg-{awg}"
    need = []
    for abi in abis:
        code, _ = wsl_bash(f"test -s {WSL_REPO}/TMessagesProj/src/main/jniLibs/{abi}/libawg.so && test -f {awg_stamp}-{abi}")
        if code or a.xray:
            need.append(abi)
    if need:
        print(f"   собираю AmneziaWG для {', '.join(need)}")
        code, _ = wsl_bash(f"""set -eo pipefail
cd {WSL_REPO}
bash recgram/tools/build_awg.sh {','.join(need)} 2>&1 | {{ grep -v '^go: downloading' || true; }}
for abi in {' '.join(need)}; do rm -f TMessagesProj/src/main/jniLibs/.awg-*-$abi; touch {awg_stamp}-$abi; done""", stream=True)
        if code:
            sys.exit("AmneziaWG не собрался (см. вывод выше).")
    else:
        print(f"   AmneziaWG уже собран для {', '.join(abis)}")

    print("4/5 Gradle :TMessagesProj_App:assembleAfatRelease — это 5–10 минут, после смены нативного кода дольше")
    keep = r"^> Task :TMessagesProj_App:(compileAfatReleaseJavaWithJavac|minifyAfatReleaseWithR8|packageAfatRelease)|^> Task .*externalNativeBuild|error:|FAILED|BUILD (SUCCESSFUL|FAILED)|What went wrong|^\* What|^e: "
    code, _ = wsl_bash(f"""export JAVA_HOME=$HOME/.local/jdk/17 ANDROID_HOME=$HOME/Android/Sdk
export PATH=$JAVA_HOME/bin:$PATH
mkdir -p $(dirname {WSL_LOG})
cd {WSL_REPO}
set -o pipefail
./gradlew --no-configuration-cache --console=plain -PrecgramAbis={','.join(abis)} :TMessagesProj_App:assembleAfatRelease 2>&1 \\
  | tee {WSL_LOG} | grep --line-buffered -E '{keep}'""", stream=True)
    if code:
        sys.exit(f"Сборка не удалась. Полный журнал в WSL: {WSL_LOG}")

    print("5/5 Копирую APK")
    name = "RecGram-release-arm64.apk" if abis == ["arm64-v8a"] else "RecGram-release.apk"
    dst = ROOT / "builds" / name
    dst.parent.mkdir(exist_ok=True)
    r = run(["wsl", "--", "cat", APK_IN_WSL], capture_output=True)
    if r.returncode or len(r.stdout) < 1_000_000:
        sys.exit("Не удалось забрать APK из WSL.")
    dst.write_bytes(r.stdout)
    sha = hashlib.sha256(r.stdout).hexdigest()
    mins = (time.time() - started) / 60
    print(f"   {dst.relative_to(ROOT)} — {len(r.stdout) / 1_048_576:.1f} МБ, SHA-256 {sha[:16]}…  ({mins:.0f} мин)")

    if a.install:
        print("Установка на телефон")
        sys.exit(run([sys.executable, "-I", str(ROOT / "recgram/tools/install_phone.py"), str(dst)]).returncode)


if __name__ == "__main__":
    main()
