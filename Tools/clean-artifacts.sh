#!/usr/bin/env bash
#
# clean-artifacts.sh — убрать из рабочей копии RecGram результаты сборки, кэши
# и мусор ОС, чтобы освободить место (в WSL-копии это десятки гигабайт) и чтобы
# архив для бэкапа нёс только исходники и то, без чего не собрать APK.
#
# ХРАНИТСЯ (не трогаем):
#   - весь код, подмодули (TMessagesProj/jni/…, TMessagesProj_Modules/media),
#     recgram/ (скрипты, recgram/awg), gradle/ и gradlew;
#   - secrets/ (ключ подписи recgram-release.jks и пароли к нему) и ссылка
#     local.properties -> secrets/android-local.properties — без них APK не
#     встанет поверх установленного;
#   - .ai/ — заметки и план, вне git: архив для них единственная копия;
#   - builds/ — готовые APK, их держат для установки и проверки.
#
# УДАЛЯЕТСЯ по --force (пересоберётся само):
#   - .gradle/, build/, buildSrc/build, buildSrc/.gradle;
#   - TMessagesProj/build и TMessagesProj/.cxx — нативная сборка (вместе это
#     больше 20 ГБ; пересборка C++ после чистки — около часа);
#   - TMessagesProj_App*/build, папки afat/, TMessagesProj/jni/ffmpeg/build_android;
#   - build/ модулей в подмодулях (media/libraries/*/build, jlatexmath/build) —
#     только если в папке нет ни одного файла из git: в подмодулях бывают
#     папки build с исходниками (openh264/build, boringssl/util/build);
#   - Xray и AmneziaWG: jniLibs/*/libxray.so, libawg.so и метки .xray-*/.awg-*
#     (recgram/tools/build_apk.py соберёт их заново);
#   - __pycache__ и мусор ОС/редактора (*Zone.Identifier, .DS_Store, *.swp …).
#
# НЕ УДАЛЯЕТСЯ, но и В АРХИВ НЕ ИДЁТ:
#   - tmp/ — черновики, снимки экрана, конфиги серверов для проверки (секреты):
#     не осматривается вовсе;
#   - builds/ и любые *.apk/*.aab — их всегда можно собрать заново.
#
# ДОПОЛНИТЕЛЬНО, только по ключу (вместе с --force; без него — показ):
#   --caches  кэши сборки вне репозитория: ~/.cache/recgram-xray (исходники
#             Xray-core), ~/.cache/recgram-awg.
#             Общие кэши Gradle и Go (~/.gradle, ~/go) не трогаются — ими
#             пользуются и другие проекты.
#
# Использование:
#   Tools/clean-artifacts.sh                   # ПРОБНЫЙ ПРОГОН — что уйдёт и сколько это
#   Tools/clean-artifacts.sh --force           # собственно удалить артефакты
#   Tools/clean-artifacts.sh --force --caches  # и кэши вне репозитория
#   Tools/clean-artifacts.sh --archive [OUT]   # собрать лёгкий .tar.gz (ничего не удаляя)
#                                              # по умолчанию — в ТЕКУЩИЙ каталог
#   Tools/clean-artifacts.sh --archive --with-git [OUT]   # ...вместе с историей .git
#   Tools/clean-artifacts.sh --archive --no-secrets       # ...без secrets/
#   Tools/clean-artifacts.sh --help
#
# Работает и в Git Bash на Windows (D:\Development\recgram), и в WSL
# (~/Development/recgram) — чистит ту копию, в которой лежит.
#
set -euo pipefail

INVOKE_DIR="$(pwd)"          # откуда запустили — туда и ляжет архив
# Абсолютный путь к самому скрипту — запомнить ДО перехода в корень: --help
# читает справку из собственного текста.
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="$(basename "$ROOT")"
cd "$ROOT"

# Результаты сборки и кэши в самом репозитории (пути от корня). Все они в .gitignore.
ARTIFACT_DIRS=(
  ".gradle"
  "build"
  "buildSrc/build"
  "buildSrc/.gradle"
  "TMessagesProj/build"
  "TMessagesProj/.cxx"
  "TMessagesProj/obj"
  "TMessagesProj/jniLibs"
  "TMessagesProj/jniRes"
  "TMessagesProj/afat"
  "TMessagesProj/jni/ffmpeg/build_android"
  "TMessagesProj_App/build"
  "TMessagesProj_App/afat"
  "TMessagesProj_AppHuawei/build"
  "TMessagesProj_AppHuawei/afat"
  "TMessagesProj_AppHockeyApp/build"
  "TMessagesProj_AppHockeyApp/afat"
  "TMessagesProj_AppStandalone/build"
  "TMessagesProj_AppStandalone/afat"
  "TMessagesProj_AppTests/build"
  "log"
)

# build/ модулей в подмодулях: удаляются, только если в них нет файлов из git.
SUBMODULE_BUILD_GLOBS=(
  "TMessagesProj_Modules/media/libraries/*/build"
  "TMessagesProj/lib/jlatexmath/*/build"
)

# Собранные Xray и AmneziaWG (recgram/tools/build_xray.sh, build_awg.sh) и их метки.
BUILT_BIN_GLOBS=(
  "TMessagesProj/src/main/jniLibs/*/libxray.so"
  "TMessagesProj/src/main/jniLibs/*/libawg.so"
  "TMessagesProj/src/main/jniLibs/.xray-*"
  "TMessagesProj/src/main/jniLibs/.awg-*"
)

# Мусор ОС и редактора (ищется по всему дереву).
JUNK_GLOBS=("*.DS_Store" "Thumbs.db" "*Zone.Identifier" "*.swp" "*.swo" "*.orig" "*.rej" "*~" "*.pyc")

# Кэши вне репозитория (--caches).
CACHE_DIRS=(
  "$HOME/.cache/recgram-xray"
  "$HOME/.cache/recgram-awg"
)

# Не попадает В АРХИВ, но --force это не трогает.
ARCHIVE_EXTRA_EXCLUDES=("tmp" "builds" ".idea" "*.iml" "*.apk" "*.aab" "*.tar.gz")

human() { du -sh "$1" 2>/dev/null | cut -f1; }

usage() { sed -n '2,/^set -euo/p' "$SELF" | grep '^#' | sed 's/^# \{0,1\}//'; exit 0; }

# Есть ли в папке файлы, которые хранит git (свой репозиторий или подмодуль).
has_tracked_files() {
  local dir="$1" repo rel
  repo="$(git -C "$dir" rev-parse --show-toplevel 2>/dev/null)" || return 1
  rel="$(cd "$dir" && pwd)"
  rel="${rel#"$repo"/}"
  [ -n "$(git -C "$repo" ls-files -- "$rel" 2>/dev/null | head -1)" ]
}

# find по дереву без .git, tmp/ и тяжёлых папок сборки — иначе обход десятков
# гигабайт ради пары файлов мусора. tmp/ скрипт не чистит и в архив не берёт.
prune_find() {
  find . -path ./.git -prune -o -path ./tmp -prune -o -path ./builds -prune \
       -o -path ./TMessagesProj/build -prune -o -path ./TMessagesProj/.cxx -prune \
       -o -path ./TMessagesProj_App/build -prune -o -name .gradle -prune \
       -o "$@" || true
}

# ── разбор аргументов ──────────────────────────────────────────────────────
MODE="dry"          # dry | force | archive
WITH_GIT=0
NO_SECRETS=0
DO_CACHES=0
OUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --force|-f)   MODE="force" ;;
    --archive|-a) MODE="archive" ;;
    --with-git)   WITH_GIT=1 ;;
    --no-secrets) NO_SECRETS=1 ;;
    --caches)     DO_CACHES=1 ;;
    -h|--help)    usage ;;
    -*)           echo "неизвестный ключ: $1" >&2; exit 2 ;;
    *)            OUT="$1" ;;   # позиционный → путь архива
  esac
  shift
done

# ── режим архива ───────────────────────────────────────────────────────────
if [ "$MODE" = "archive" ]; then
  ts="$(date +%Y%m%d-%H%M%S)"
  if [ -z "$OUT" ]; then
    OUT="$INVOKE_DIR/${BASE}-backup-${ts}.tar.gz"
  elif [ "${OUT#/}" = "$OUT" ] && ! [[ "$OUT" =~ ^[A-Za-z]: ]]; then
    OUT="$INVOKE_DIR/$OUT"                                    # относительный — от каталога запуска
  fi

  excludes=()
  for d in "${ARTIFACT_DIRS[@]}"; do excludes+=(--exclude="$BASE/$d"); done
  for g in "${SUBMODULE_BUILD_GLOBS[@]}"; do excludes+=(--exclude="$BASE/$g"); done
  for g in "${BUILT_BIN_GLOBS[@]}"; do excludes+=(--exclude="$BASE/$g"); done
  excludes+=(--exclude='__pycache__' --exclude='.gradle' --exclude='.cxx')
  for g in "${JUNK_GLOBS[@]}"; do excludes+=(--exclude="$g"); done
  for e in "${ARCHIVE_EXTRA_EXCLUDES[@]}"; do
    case "$e" in
      *.*|.*) excludes+=(--exclude="$e") ;;                   # шаблон (*.apk) или скрытый каталог (.idea)
    esac
    excludes+=(--exclude="$BASE/$e")                          # и копия в корне репозитория
  done
  # .git подмодулей лежит в .git/modules — без --with-git их история тоже не входит
  [ "$WITH_GIT" -eq 0 ] && excludes+=(--exclude="$BASE/.git" --exclude="$BASE/*/.git" --exclude='.git')
  [ "$NO_SECRETS" -eq 1 ] && excludes+=(--exclude="$BASE/secrets" --exclude="$BASE/local.properties")

  echo "Собираю архив (весь код с подмодулями, .ai/ — его копия только здесь):"
  echo "  -> $OUT"
  [ "$WITH_GIT" -eq 0 ] && echo "  (история .git не входит — нужна, добавьте --with-git)"
  echo "  (tmp/, builds/ и APK не входят)"
  if [ "$NO_SECRETS" -eq 0 ] && [ -d secrets ]; then
    echo "  ⚠ входит secrets/ — КЛЮЧ ПОДПИСИ и пароли к нему: храните архив как секрет"
    echo "    (без них: --no-secrets)"
  fi
  tar -C "$(dirname "$ROOT")" -czf "$OUT" "${excludes[@]}" "$BASE"
  echo "Готово. Размер архива: $(human "$OUT")"
  exit 0
fi

# ── чистка / пробный прогон ────────────────────────────────────────────────
total_kb=0
found=0
note() { printf '  %-9s %s\n' "$1" "$2"; }
add_kb() { total_kb=$(( total_kb + $(du -sk "$1" 2>/dev/null | cut -f1) )); }

echo "Артефакты под $ROOT:"
for d in "${ARTIFACT_DIRS[@]}"; do
  [ -d "$d" ] || continue
  [ -L "$d" ] && continue
  if has_tracked_files "$d"; then
    note "" "$d/ — в ней есть файлы из git, остаётся"
    continue
  fi
  found=1
  note "$(human "$d")" "$d/"
  add_kb "$d"
  [ "$MODE" = "force" ] && rm -rf -- "$d"
done

shopt -s nullglob
for pat in "${SUBMODULE_BUILD_GLOBS[@]}"; do
  for d in $pat; do
    [ -d "$d" ] || continue
    [ -L "$d" ] && continue
    if has_tracked_files "$d"; then
      note "" "$d/ — исходники подмодуля, остаётся"
      continue
    fi
    found=1
    note "$(human "$d")" "$d/"
    add_kb "$d"
    [ "$MODE" = "force" ] && rm -rf -- "$d"
  done
done

for pat in "${BUILT_BIN_GLOBS[@]}"; do
  for b in $pat; do
    [ -f "$b" ] || continue
    [ -L "$b" ] && continue
    found=1
    case "$b" in
      */.xray-*|*/.awg-*) ;;                                  # метки — не показываем по одной
      *) note "$(human "$b")" "$b (Xray/AmneziaWG)" ;;
    esac
    add_kb "$b"
    [ "$MODE" = "force" ] && rm -f -- "$b"
  done
done
shopt -u nullglob

# __pycache__ по всему дереву.
pyc_dirs=$(prune_find -type d -name __pycache__ -print 2>/dev/null)
if [ -n "$pyc_dirs" ]; then
  found=1
  note "" "$(printf '%s\n' "$pyc_dirs" | wc -l | tr -d ' ') папк(и) __pycache__"
  [ "$MODE" = "force" ] && printf '%s\n' "$pyc_dirs" | while IFS= read -r d; do rm -rf -- "$d"; done
fi

# Мусор ОС и редактора: считаем, по --force удаляем.
junk_args=()
for g in "${JUNK_GLOBS[@]}"; do junk_args+=(-name "$g" -o); done
unset 'junk_args[${#junk_args[@]}-1]'   # убрать хвостовой -o
junk_count=$(prune_find -type f \( "${junk_args[@]}" \) -print 2>/dev/null | wc -l | tr -d ' ')
if [ "$junk_count" -gt 0 ]; then
  found=1
  note "" "$junk_count файл(ов) мусора ОС/редактора (Zone.Identifier, .DS_Store, swap …)"
  [ "$MODE" = "force" ] && prune_find -type f \( "${junk_args[@]}" \) -print0 2>/dev/null | xargs -0 -r rm -f
fi

if [ "$found" -eq 0 ]; then
  echo "  (чисто — удалять нечего)"
else
  echo
  if [ "$total_kb" -ge 1048576 ]; then
    printf 'Освободится: ~%s.%s ГБ\n' "$(( total_kb / 1048576 ))" "$(( total_kb % 1048576 * 10 / 1048576 ))"
  elif [ "$total_kb" -ge 1024 ]; then
    printf 'Освободится: ~%s МБ\n' "$(( total_kb / 1024 ))"
  else
    printf 'Освободится: ~%s КБ\n' "$total_kb"
  fi
fi

# Что остаётся и почему — чтобы не искать глазами.
echo
echo "Остаётся:"
[ -d builds ]  && note "$(human builds)" "builds/ — готовые APK (в архив не идут)"
[ -d tmp ]     && note "$(human tmp)" "tmp/ — черновики и конфиги для проверки (не осматривается, в архив не идёт)"
[ -d secrets ] && note "$(human secrets)" "secrets/ — ключ подписи и пароли"
[ -d .ai ]     && note "$(human .ai)" ".ai/ — заметки и план"

# ── кэши вне репозитория ───────────────────────────────────────────────────
if [ "$DO_CACHES" -eq 1 ]; then
  echo
  echo "Кэши вне репозитория:"
  any=0
  for d in "${CACHE_DIRS[@]}"; do
    [ -d "$d" ] || continue
    any=1
    note "$(human "$d")" "~${d#"$HOME"}"
    [ "$MODE" = "force" ] && rm -rf -- "$d"
  done
  [ "$any" -eq 0 ] && echo "  нет"
fi

if [ "$MODE" = "force" ]; then
  echo
  echo "Удалено. ✓  (код, подмодули, secrets/, .ai/, builds/ на месте)"
  echo "  вернуть сборку: python -I recgram/tools/build_apk.py  (первая сборка после чистки — около часа)"
elif [ "$found" -ne 0 ] || [ "$DO_CACHES" -eq 1 ]; then
  echo
  echo "Это был ПРОБНЫЙ ПРОГОН — ничего не удалено."
  echo "  удалить:            Tools/clean-artifacts.sh --force [--caches]"
  echo "  вместо этого архив: Tools/clean-artifacts.sh --archive"
fi
