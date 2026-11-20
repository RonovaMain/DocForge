#!/usr/bin/env bash
# Собирает фильтры печати foo2* (foo2zjs и родственные) для Android и кладёт их в app/src/main/jniLibs/<abi>/lib<имя>.so.
# Запускается в GitHub Actions перед сборкой APK. Ошибка одного фильтра не останавливает остальные,
# а если исходники не скачались, APK просто соберётся без них (в окне печати эти драйверы будут помечены «нет в этой сборке»).
#
# Исполняемый файл под видом .so — приём, на котором Android разрешает запуск: из каталога нативных библиотек
# приложения запускать можно, из данных приложения — нельзя (поэтому в build.gradle.kts включена useLegacyPackaging).
set -u
cd "$(dirname "$0")/.."

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "::warning::Android NDK не найден, фильтры печати не собраны"
  exit 0
fi
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=26
SRC=build-drivers/foo2zjs
OUT=app/src/main/jniLibs
FILTERS="foo2zjs foo2xqx foo2qpdl foo2slx foo2lava"

if [ ! -f "$SRC/foo2zjs.c" ]; then
  rm -rf "$SRC"
  mkdir -p build-drivers
  echo "Скачиваю исходники foo2zjs…"
  git clone --depth 1 https://github.com/koenkooi/foo2zjs.git "$SRC" 2>/dev/null \
    || { curl -fsSL http://foo2zjs.rkkda.com/foo2zjs.tar.gz | tar xz -C build-drivers; }
fi
if [ ! -f "$SRC/foo2zjs.c" ]; then
  echo "::warning::Исходники foo2zjs не скачались, фильтры печати не собраны"
  exit 0
fi

built=""
build_one() { # abi компилятор имя
  local abi="$1" cc="$2" name="$3" srcs="$SRC/$3.c" extra log
  [ -f "$SRC/$name.c" ] || { echo "  $name: нет файла $name.c"; return; }
  for extra in jbig.c jbig_ar.c; do [ -f "$SRC/$extra" ] && srcs="$srcs $SRC/$extra"; done
  mkdir -p "$OUT/$abi"
  log="$(mktemp)"
  # -w и gnu99: исходники старые, современный clang иначе ругается на неявные объявления функций
  if "$BIN/$cc" -O2 -w -std=gnu99 -fPIE -pie -D_GNU_SOURCE -I"$SRC" -Wl,-z,max-page-size=16384 \
      -o "$OUT/$abi/lib$name.so" $srcs -lm 2>"$log"; then
    "$BIN/llvm-strip" "$OUT/$abi/lib$name.so" 2>/dev/null || true
    echo "  $abi/$name: собран"
    built="$built $abi/$name"
  else
    echo "  $abi/$name: не собрался, пропускаю"
    tail -n 5 "$log"
    rm -f "$OUT/$abi/lib$name.so"
  fi
  rm -f "$log"
}

for name in $FILTERS; do
  build_one arm64-v8a "aarch64-linux-android${API}-clang" "$name"
  build_one armeabi-v7a "armv7a-linux-androideabi${API}-clang" "$name"
done

if [ -z "$built" ]; then
  echo "::warning::Ни один фильтр печати не собрался, APK будет без них"
else
  echo "Собрано:$built"
fi
exit 0
