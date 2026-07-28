#!/usr/bin/env bash
#
# Cross-compiles SDL2 + libspectrum + Fuse for Android and drops the results
# into the Gradle project (app/src/main/jniLibs and app/src/main/assets).
#
# Nothing under vendor/ is modified: everything is built out-of-tree.
#
# Usage:
#   scripts/build-native.sh                 # build all ABIs
#   ABIS="x86_64" scripts/build-native.sh   # build a single ABI
#   scripts/build-native.sh clean
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENDOR="$ROOT/vendor"
BUILD="$ROOT/build-native"
APP="$ROOT/app/src/main"

SDL_VER="2.32.10"
FUSE_VER="1.9.0"
LIBSPECTRUM_VER="1.6.2"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API="${API:-30}"                       # Android 11
ABIS="${ABIS:-arm64-v8a x86_64}"
JOBS="${JOBS:-$(nproc)}"

# Must match applicationId in app/build.gradle: Fuse hardcodes FUSEDATADIR at
# compile time and looks for its ROMs / font there at runtime.
PKG="com.fusemobile"
DATA_ROOT="/data/data/$PKG/files"      # -> FUSEDATADIR = $DATA_ROOT/fuse

if [ "${1:-}" = "clean" ]; then
  rm -rf "$BUILD" "$APP/jniLibs" "$APP/assets/fuse"
  echo "cleaned"
  exit 0
fi

[ -d "$TOOLCHAIN" ] || { echo "NDK toolchain not found at $TOOLCHAIN" >&2; exit 1; }
echo "NDK:  $NDK"
echo "API:  $API"
echo "ABIs: $ABIS"

##############################################################################
# Upstream sources. Not kept in git (~100 MB); fetched once, verified, and
# then left strictly read-only.
##############################################################################
fetch() {
  local name="$1" url="$2" sha="$3"
  local tarball="$VENDOR/$name.tar.gz"

  [ -d "$VENDOR/$name" ] && return 0

  mkdir -p "$VENDOR"
  if [ ! -f "$tarball" ]; then
    echo "=== fetching $name ==="
    curl -fsSL -o "$tarball.tmp" "$url"
    mv "$tarball.tmp" "$tarball"
  fi

  echo "$sha  $tarball" | sha256sum -c - >/dev/null || {
    echo "checksum mismatch for $tarball" >&2; exit 1; }

  tar xzf "$tarball" -C "$VENDOR"
}

fetch "fuse-$FUSE_VER" \
  "https://downloads.sourceforge.net/project/fuse-emulator/fuse/$FUSE_VER/fuse-$FUSE_VER.tar.gz" \
  34618c419e215e16ae4584f227e899e4e55be1ddb90fb03380c910ac16cab38a
fetch "libspectrum-$LIBSPECTRUM_VER" \
  "https://downloads.sourceforge.net/project/fuse-emulator/libspectrum/$LIBSPECTRUM_VER/libspectrum-$LIBSPECTRUM_VER.tar.gz" \
  74bb2bb0e78779a09808aa7636fe7fa6c815002e8344b46d914bfb7a864c88e0
fetch "SDL2-$SDL_VER" \
  "https://github.com/libsdl-org/SDL/releases/download/release-$SDL_VER/SDL2-$SDL_VER.tar.gz" \
  5f5993c530f084535c65a6879e9b26ad441169b3e25d789d83287040a9ca5165

# SDL's Java glue has to match libSDL2.so, so it is copied rather than vendored
# by hand. Our own code lives in com/fusemobile; org/libsdl/app is upstream.
cp -r "$VENDOR/SDL2-$SDL_VER/android-project/app/src/main/java/org" "$APP/java/"

for ABI in $ABIS; do
  case "$ABI" in
    arm64-v8a)   TARGET=aarch64-linux-android;  HOST=aarch64-linux-android ;;
    armeabi-v7a) TARGET=armv7a-linux-androideabi; HOST=arm-linux-androideabi ;;
    x86_64)      TARGET=x86_64-linux-android;   HOST=x86_64-linux-android ;;
    x86)         TARGET=i686-linux-android;     HOST=i686-linux-android ;;
    *) echo "unknown ABI $ABI" >&2; exit 1 ;;
  esac

  PREFIX="$BUILD/prefix/$ABI"
  mkdir -p "$PREFIX"

  export CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
  export CXX="$TOOLCHAIN/bin/${TARGET}${API}-clang++"
  export AR="$TOOLCHAIN/bin/llvm-ar"
  export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  export STRIP="$TOOLCHAIN/bin/llvm-strip"
  export CFLAGS="-fPIC -O2"
  export CPPFLAGS=""
  export LDFLAGS=""
  # Only look at the .pc files we cross-built ourselves - never the host's.
  export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
  export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
  unset PKG_CONFIG_SYSROOT_DIR

  ############################################################################
  echo "=== [$ABI] SDL $SDL_VER ==="
  ############################################################################
  if [ ! -f "$PREFIX/lib/libSDL2.so" ]; then
    cmake -S "$VENDOR/SDL2-$SDL_VER" -B "$BUILD/sdl2/$ABI" -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_INSTALL_PREFIX="$PREFIX" \
      -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TEST=OFF
    cmake --build "$BUILD/sdl2/$ABI" --parallel "$JOBS"
    cmake --install "$BUILD/sdl2/$ABI"
  fi

  ############################################################################
  echo "=== [$ABI] libspectrum $LIBSPECTRUM_VER ==="
  ############################################################################
  if [ ! -f "$PREFIX/lib/libspectrum.a" ]; then
    mkdir -p "$BUILD/libspectrum/$ABI"
    ( cd "$BUILD/libspectrum/$ABI" && \
      "$VENDOR/libspectrum-$LIBSPECTRUM_VER/configure" \
        --host="$HOST" --prefix="$PREFIX" \
        --with-fake-glib \
        --without-libgcrypt --without-bzip2 --without-libaudiofile \
        --enable-static --disable-shared && \
      make -j"$JOBS" && make install )
  fi

  ############################################################################
  echo "=== [$ABI] fuse $FUSE_VER ==="
  ############################################################################
  FUSE_BUILD="$BUILD/fuse/$ABI"
  if [ ! -f "$FUSE_BUILD/Makefile" ]; then
    mkdir -p "$FUSE_BUILD"
    # -include limits.h: compat.h falls back to PATH_MAX=1024 when nothing has
    # pulled in <limits.h> yet, which is what happens on bionic in some
    # translation units. Other units see the real 4096, so struct path_context
    # ends up smaller than the code writing into it thinks - a stack smash in
    # compat_get_next_path(). Forcing limits.h first makes PATH_MAX consistent.
    ( cd "$FUSE_BUILD" && \
      CPPFLAGS="-include limits.h" \
      "$VENDOR/fuse-$FUSE_VER/configure" \
        --host="$HOST" --prefix="$DATA_ROOT" --datadir="$DATA_ROOT" \
        --with-sdl --without-gtk --without-x --without-png \
        --without-libxml2 --without-gpm \
        --disable-desktop-integration )
  fi
  # Link the emulator as libmain.so instead of an executable: SDL's Android
  # bootstrap dlsym()s SDL_main out of it, and fuse.c's main() is already
  # renamed to SDL_main by <SDL.h> when UI_SDL2 is defined. No source changes.
  # -XCClinker is needed because the link goes through libtool, which would
  # otherwise eat a bare -shared on a program target.
  make -C "$FUSE_BUILD" -j"$JOBS" \
    LDFLAGS="-XCClinker -shared -XCClinker -Wl,-soname,libmain.so -XCClinker -Wl,--no-undefined"

  mkdir -p "$APP/jniLibs/$ABI"
  cp "$FUSE_BUILD/fuse" "$APP/jniLibs/$ABI/libmain.so"
  cp "$PREFIX/lib/libSDL2.so" "$APP/jniLibs/$ABI/libSDL2.so"
  "$STRIP" "$APP/jniLibs/$ABI/libmain.so" "$APP/jniLibs/$ABI/libSDL2.so"

  ############################################################################
  # Fuse's data files (ROMs, widget font, UI bitmaps) are ABI independent;
  # take them from the first ABI we build via a staged `make install`.
  ############################################################################
  if [ ! -d "$APP/assets/fuse" ]; then
    STAGE="$BUILD/stage"
    rm -rf "$STAGE"
    make -C "$FUSE_BUILD" install DESTDIR="$STAGE" >/dev/null
    mkdir -p "$APP/assets"
    cp -r "$STAGE$DATA_ROOT/fuse" "$APP/assets/fuse"
    echo "data files: $(ls "$APP/assets/fuse" | wc -l) entries"
  fi
done

echo
echo "Done. Native libs:"
ls -la "$APP"/jniLibs/*/
