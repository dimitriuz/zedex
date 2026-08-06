#!/usr/bin/env bash
#
# Cross-compiles libspectrum + Fuse for Android and drops the result into the
# Gradle project (app/src/main/jniLibs and app/src/main/assets).
#
# Rather than adding an Android UI to Fuse's build system, we configure it
# --with-fb (which builds Fuse's portable widget UI and nothing framebuffer
# specific outside ui/fb), then simply never compile ui/fb and link
# native/ui/android in its place. Same trick for the sound driver.
#
# What is compiled is a copy of the release with native/patches applied - see
# scripts/fuse-src.sh. vendor/ is still exactly what was downloaded, and with
# no patches present the copy is the release byte for byte.
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

FUSE_VER="1.9.1"
LIBSPECTRUM_VER="1.6.3"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API="${API:-30}"                       # Android 11
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
JOBS="${JOBS:-$(nproc)}"

# The last place Fuse looks for its data files, and the only one baked in. It
# no longer has to match applicationId - the app names argv[0] as a path inside
# its own files and Fuse finds them beside that first - which is what lets the
# debug build have a package of its own.
PKG="dev.ldlab.zedex"
DATA_ROOT="/data/data/$PKG/files"      # -> FUSEDATADIR = $DATA_ROOT/fuse

if [ "${1:-}" = "clean" ]; then
  # The Fuse working tree is inside $BUILD and is the one thing here that can
  # hold work: it is vendor/ plus native/patches/, and anything not yet saved
  # as a patch exists nowhere else. Refuse rather than take it away.
  if [ -n "$("$ROOT/scripts/fuse-src.sh" dirty)" ] && [ "${2:-}" != "--force" ]; then
    echo "the Fuse working tree has changes that are not in native/patches:" >&2
    "$ROOT/scripts/fuse-src.sh" dirty >&2
    echo "run 'scripts/fuse-src.sh save' first, or 'clean --force'" >&2
    exit 1
  fi
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
  5815b42256d4dd28581d59f3ceec33fcd736b7a68afe032b1e65ba714fb55642
fetch "libspectrum-$LIBSPECTRUM_VER" \
  "https://downloads.sourceforge.net/project/fuse-emulator/libspectrum/$LIBSPECTRUM_VER/libspectrum-$LIBSPECTRUM_VER.tar.gz" \
  fa4a5e68c1ab5860dcdf99f5486ee6313995dbe30e84160e9f699d0f8db77d76

##############################################################################
# What Fuse is actually built from: a copy of the release with native/patches/
# applied. vendor/ stays exactly as downloaded. With no patches present this
# is the release, byte for byte; see scripts/fuse-src.sh.
##############################################################################
"$ROOT/scripts/fuse-src.sh" ensure
FUSE_SRC="$("$ROOT/scripts/fuse-src.sh" path)"

# A tree edited but not saved builds something no one else can reproduce, and
# `build-native.sh clean` would be the end of it. Say so on every build.
if [ -n "$("$ROOT/scripts/fuse-src.sh" dirty)" ]; then
  echo
  echo "NOTE: the Fuse working tree has changes that are in no patch:"
  "$ROOT/scripts/fuse-src.sh" dirty | sed 's/^/      /'
  echo "      building them anyway; 'scripts/fuse-src.sh save' keeps them."
  echo
fi

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

  # Android 15 and later can run with 16 KB memory pages, and Play requires an
  # app targeting API 35 or higher to be built for them: a library whose LOAD
  # segments are 4 KB aligned cannot be mapped on such a device at all, so this
  # is not a warning to be lived with.
  #
  # The NDK's own CMake and ndk-build toolchains pass this; invoking the target
  # clang by hand, as this script does, gets the linker default, which is 4 KB.
  # `readelf -lW` on the result should say 0x4000 - see the check at the end.
  PAGE_ALIGN="-Wl,-z,max-page-size=16384"
  export LDFLAGS="$PAGE_ALIGN"
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

  # Fuse bakes FUSEDATADIR in at configure time, so a build tree configured
  # for a different package is worse than no build tree at all: it compiles,
  # links and installs, and then cannot find its own font at runtime. The
  # tree records what it was configured for, and anything else - including a
  # tree from before this check existed, which has no record - is discarded.
  #
  # The source directory is in that record for the same reason and it is the
  # sharper of the two: a build tree holds no sources, only a Makefile with the
  # srcdir it was configured against and objects made from it. Pointed at the
  # patched tree while it was configured for vendor/, make finds every object
  # up to date against sources we are no longer building, says so, and links a
  # library with none of the patches in it.
  FUSE_STAMP="$PKG $FUSE_SRC"
  if [ -d "$FUSE_BUILD" ] && \
     [ "$(cat "$FUSE_BUILD/.package" 2>/dev/null)" != "$FUSE_STAMP" ]; then
    echo "configured for another package or source tree; reconfiguring"
    rm -rf "$FUSE_BUILD"
  fi

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
      "$FUSE_SRC/configure" \
        --host="$HOST" --prefix="$DATA_ROOT" --datadir="$DATA_ROOT" \
        --with-fb --without-gpm --with-audio-driver=null \
        --without-gtk --without-x --without-png \
        --without-libxml2 \
        --disable-desktop-integration ) && \
      echo "$FUSE_STAMP" > "$FUSE_BUILD/.package"
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
  FUSE_OBJS=$(mkvar fuse_OBJECTS | tr ' ' '\n' \
              | grep -v '^ui/fb/' | grep -v '^ui/widget/error\.o$' \
              | grep -v '^ui/widget/widget\.o$' \
              | tr '\n' ' ')
  FUSE_LDADD=$(mkvar fuse_LDADD | tr ' ' '\n' \
               | grep -v '^sound/nullsound\.o$' | tr '\n' ' ')
  LDADD_OBJS=$(echo "$FUSE_LDADD" | tr ' ' '\n' | grep '\.o$' | tr '\n' ' ')
  mkdir -p "$FUSE_BUILD/android"
  make -C "$FUSE_BUILD" -j"$JOBS" $FUSE_OBJS $LDADD_OBJS \
      ui/widget/error.o ui/widget/widget.o

  # native/ui/android reports Fuse's errors as Android toasts instead of the
  # modal ui/widget/error.c draws into the emulated screen. The file cannot
  # simply be dropped - ui/widget/query.c needs its split_message - so weaken
  # the one symbol we are replacing and let ours win.
  "$TOOLCHAIN/bin/llvm-objcopy" --weaken-symbol=ui_error_specific \
      "$FUSE_BUILD/ui/widget/error.o" "$FUSE_BUILD/android/widget_error.o"
  FUSE_OBJS="$FUSE_OBJS android/widget_error.o"

  # Same again for the status bar. ui/widget/widget.c's ui_statusbar_update is
  # a stub that returns 0 and throws the news away; ours keeps it, so the app
  # can light a lamp when the tape or a disk is running. widget.c is the whole
  # widget framework and cannot be dropped either.
  "$TOOLCHAIN/bin/llvm-objcopy" --weaken-symbol=ui_statusbar_update \
      "$FUSE_BUILD/ui/widget/widget.o" "$FUSE_BUILD/android/widget_widget.o"
  FUSE_OBJS="$FUSE_OBJS android/widget_widget.o"

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
  # $LDFLAGS is for configure and make; this link is by hand, so the page
  # alignment has to be repeated here. It is the flag that matters most, since
  # this is the only library that ships.
  ( cd "$FUSE_BUILD" && \
    $CC -shared -Wl,-soname,libfuse.so -Wl,--no-undefined $PAGE_ALIGN -o libfuse.so \
      $FUSE_OBJS $OUR_OBJS \
      $FUSE_LDADD $(mkvar LIBS) \
      -landroid -lnativewindow -llog -lEGL -lGLESv3 -laaudio )

  mkdir -p "$APP/jniLibs/$ABI"
  cp "$FUSE_BUILD/libfuse.so" "$APP/jniLibs/$ABI/libfuse.so"
  "$STRIP" "$APP/jniLibs/$ABI/libfuse.so"

  # The unstripped library, kept for Play's crash reports. jniLibs gets the
  # stripped one so the APK stays the size it was - 1.8 MB against 12 - and
  # `symbols` is zipped up for upload; see *Native debug symbols* in
  # docs/DEVELOPING.md. Not in the APK, not in git, and regenerated by any build.
  mkdir -p "$BUILD/symbols/$ABI"
  cp "$FUSE_BUILD/libfuse.so" "$BUILD/symbols/$ABI/libfuse.so"

  ############################################################################
  # Fuse's data files (ROMs, widget font, UI bitmaps) are ABI independent;
  # take them from the first ABI we build via a staged `make install`.
  ############################################################################
  # Fuse's own UI data: the widget font and the status bitmaps, which travel
  # with the app. The ROMs the same install stages are dropped again - they
  # live in roms/ at the root of the repository, where the eight the clones
  # need are beside them, with the licence they travel under.
  if [ ! -d "$APP/assets/fuse" ]; then
    STAGE="$BUILD/stage"
    rm -rf "$STAGE"
    make -C "$FUSE_BUILD" install-pkgdataDATA DESTDIR="$STAGE" >/dev/null
    mkdir -p "$APP/assets"
    cp -r "$STAGE$DATA_ROOT/fuse" "$APP/assets/fuse"
    rm -f "$APP"/assets/fuse/*.rom
    echo "data files: $(ls "$APP/assets/fuse" | wc -l) entries"
  fi
done

echo
echo "Done. Native libs:"
ls -la "$APP"/jniLibs/*/

# Checked rather than assumed: a library that lost its 16 KB alignment builds,
# installs and runs on every 4 KB device, and cannot be loaded at all on a
# 16 KB one. Nothing else in the build would notice.
echo
for ABI in $ABIS; do
  SO="$APP/jniLibs/$ABI/libfuse.so"
  [ -f "$SO" ] || continue
  BAD=$(readelf -lW "$SO" | awk '/LOAD/ && $NF != "0x4000" { print $NF }')
  if [ -n "$BAD" ]; then
    echo "ERROR: $ABI/libfuse.so has LOAD segments aligned $BAD, not 0x4000." >&2
    echo "       Android 15 with 16 KB pages cannot map it. Is PAGE_ALIGN" >&2
    echo "       still on the link line?" >&2
    exit 1
  fi
  echo "$ABI: 16 KB aligned"
done
