#!/usr/bin/env bash
#
# Cross-compiles libspectrum + Fuse for Android and drops the result into the
# Gradle project (app/src/main/jniLibs and app/src/main/assets).
#
# Fuse is used completely unmodified. Rather than adding an Android UI to its
# build system, we configure it --with-fb (which builds Fuse's portable widget
# UI and nothing framebuffer specific outside ui/fb), then simply never
# compile ui/fb and link native/ui/android in its place. Same trick for the
# sound driver.
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
NATIVE="$ROOT/native"

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
# Upstream sources. Not kept in git (~13 MB); fetched once, verified, and
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
    # --with-fb selects the widget UI without pulling in anything framebuffer
    # specific outside ui/fb, which we discard below.
    #
    # -include limits.h: compat.h falls back to PATH_MAX=1024 when nothing has
    # pulled in <limits.h> yet, which is what happens on bionic in some
    # translation units. Other units see the real 4096, so struct path_context
    # ends up smaller than the code writing into it thinks - a stack smash in
    # compat_get_next_path(). Forcing limits.h first makes PATH_MAX consistent.
    ( cd "$FUSE_BUILD" && \
      CPPFLAGS="-include limits.h" \
      "$VENDOR/fuse-$FUSE_VER/configure" \
        --host="$HOST" --prefix="$DATA_ROOT" --datadir="$DATA_ROOT" \
        --with-fb --without-gpm --with-audio-driver=null \
        --without-gtk --without-x --without-png \
        --without-libxml2 \
        --disable-desktop-integration )
  fi

  # Ask the generated Makefile for its own variables rather than guessing at
  # include paths and the object list.
  cat > "$FUSE_BUILD/printvar.mk" <<'EOF'
include Makefile
print-%:
	@echo "$($*)"
EOF
  mkvar() { make -s -C "$FUSE_BUILD" -f printvar.mk "print-$1"; }

  # Perl-generated sources (z80 opcodes, settings, menu data, widget options).
  make -C "$FUSE_BUILD" -j"$JOBS" $(mkvar BUILT_SOURCES) >/dev/null

  # Everything Fuse would link, minus the UI we are replacing. A few objects
  # (the timer, the scalers, the sound driver) reach the link through
  # fuse_LDADD rather than fuse_OBJECTS, so they have to be built too.
  FUSE_OBJS=$(mkvar fuse_OBJECTS | tr ' ' '\n' | grep -v '^ui/fb/' | tr '\n' ' ')
  FUSE_LDADD=$(mkvar fuse_LDADD | tr ' ' '\n' \
               | grep -v '^sound/nullsound\.o$' | tr '\n' ' ')
  LDADD_OBJS=$(echo "$FUSE_LDADD" | tr ' ' '\n' | grep '\.o$' | tr '\n' ' ')
  make -C "$FUSE_BUILD" -j"$JOBS" $FUSE_OBJS $LDADD_OBJS

  ############################################################################
  echo "=== [$ABI] Android UI and audio ==="
  ############################################################################
  OUR_OBJS=""
  mkdir -p "$FUSE_BUILD/android"
  # Compiled from inside the build tree: Fuse's DEFAULT_INCLUDES starts with
  # -I. for the generated config.h.
  for src in "$NATIVE"/ui/android/*.c "$NATIVE"/sound/*.c; do
    obj="android/$(basename "${src%.c}").o"
    ( cd "$FUSE_BUILD" && \
      $CC $CFLAGS -DHAVE_CONFIG_H $(mkvar DEFAULT_INCLUDES) $(mkvar AM_CPPFLAGS) \
          $(mkvar CPPFLAGS) -c -o "$obj" "$src" )
    OUR_OBJS="$OUR_OBJS $obj"
  done

  ############################################################################
  echo "=== [$ABI] link libfuse.so ==="
  ############################################################################
  ( cd "$FUSE_BUILD" && \
    $CC -shared -Wl,-soname,libfuse.so -Wl,--no-undefined -o libfuse.so \
      $FUSE_OBJS $OUR_OBJS \
      $FUSE_LDADD $(mkvar LIBS) \
      -landroid -llog -lEGL -lGLESv3 -laaudio )

  mkdir -p "$APP/jniLibs/$ABI"
  cp "$FUSE_BUILD/libfuse.so" "$APP/jniLibs/$ABI/libfuse.so"
  "$STRIP" "$APP/jniLibs/$ABI/libfuse.so"

  ############################################################################
  # Fuse's data files (ROMs, widget font, UI bitmaps) are ABI independent;
  # take them from the first ABI we build via a staged `make install`.
  ############################################################################
  if [ ! -d "$APP/assets/fuse" ]; then
    STAGE="$BUILD/stage"
    rm -rf "$STAGE"
    make -C "$FUSE_BUILD" install-pkgdataDATA DESTDIR="$STAGE" >/dev/null
    mkdir -p "$APP/assets"
    cp -r "$STAGE$DATA_ROOT/fuse" "$APP/assets/fuse"
    echo "data files: $(ls "$APP/assets/fuse" | wc -l) entries"
  fi
done

echo
echo "Done. Native libs:"
ls -la "$APP"/jniLibs/*/
