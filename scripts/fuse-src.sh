#!/usr/bin/env bash
#
# The Fuse working tree.
#
# vendor/ holds each release exactly as it was downloaded and is still never
# modified: this makes an editable *copy* of it, applies native/patches/ and
# leaves the result in build-native/src, which is what scripts/build-native.sh
# compiles.  Everything of ours is therefore a patch in git - the only form
# that can be read as a change, rebased onto the next Fuse release, or sent
# upstream.  A tree with no patches applied builds byte for byte what an
# unpatched vendor/ build did.
#
# The copy is a git repository of its own, so the fork has history: the
# pristine release is the first commit and is tagged `upstream`, and each
# commit after it is one patch in native/patches/.  Edit the tree, commit
# there, then `save` to write the series back out.
#
#   scripts/fuse-src.sh path      # where the tree is; the build asks this
#   scripts/fuse-src.sh ensure    # make it if it is not there (the build does)
#   scripts/fuse-src.sh reset     # throw it away and remake it from the patches
#   scripts/fuse-src.sh status    # what is committed, and what is not
#   scripts/fuse-src.sh diff      # working changes, not yet a commit
#   scripts/fuse-src.sh save      # commits -> native/patches/*.patch
#   scripts/fuse-src.sh regen     # settings.dat -> settings.c, settings.h
#   scripts/fuse-src.sh git ...   # anything else, in the tree's own repo
#
# Typical loop:
#   $EDITOR "$(scripts/fuse-src.sh path)"/sound.c
#   scripts/fuse-src.sh git commit -am 'sound: a second AY chip'
#   scripts/fuse-src.sh save
#   scripts/build-native.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENDOR="$ROOT/vendor"
BUILD="$ROOT/build-native"
PATCHES="$ROOT/native/patches"

# One place for the version: build-native.sh is the file that owns it.
FUSE_VER="$(sed -n 's/^FUSE_VER="\(.*\)"$/\1/p' "$ROOT/scripts/build-native.sh")"
[ -n "$FUSE_VER" ] || { echo "cannot read FUSE_VER from build-native.sh" >&2; exit 1; }

TARBALL="$VENDOR/fuse-$FUSE_VER.tar.gz"
SRC="$BUILD/src/fuse-$FUSE_VER"

# The fork's own repository, never the app's: every git call here is -C "$SRC",
# and this stops one that is not from quietly reaching the outer tree.
fgit() { git -C "$SRC" -c user.name=Zedex -c user.email=zedex@invalid "$@"; }

# Files the release ships but the build makes: they are tracked, because the
# tarball has them, and apply_patches writes them again from the patched
# settings.dat. Changing them is not work, so nothing here counts them as work
# - otherwise the tree would look dirty the moment it was made.
GENERATED=( ':(exclude)settings.c' ':(exclude)settings.h' )

# What the tree has that no patch does. Empty means there is nothing to lose.
dirty() { fgit status --porcelain -- . "${GENERATED[@]}"; }

# What the tree's own commits would be saved as, normalised: format-patch
# numbers each subject "[PATCH 2/3]", so a tree two patches deep compared with a
# series three deep differs on that line and on nothing else.
series_from_tree() {
  local out="$1" f
  rm -rf "$out"; mkdir -p "$out"
  fgit format-patch --quiet --no-signature --zero-commit \
      --output-directory "$out" upstream..HEAD
  for f in "$out"/*.patch; do
    [ -e "$f" ] || continue
    sed -i 's/^Subject: \[PATCH [0-9]*\/[0-9]*\]/Subject: [PATCH]/' "$f"
  done
}

# ...and the same normalisation for the series in git, so the two are
# comparable file by file.
series_from_patches() {
  local out="$1" p
  rm -rf "$out"; mkdir -p "$out"
  shopt -s nullglob
  for p in "$PATCHES"/*.patch; do
    sed 's/^Subject: \[PATCH [0-9]*\/[0-9]*\]/Subject: [PATCH]/' \
        "$p" > "$out/$(basename "$p")"
  done
  shopt -u nullglob
}

# Whether the working tree is the series, is the series with fewer patches
# applied - somebody pulled new ones - or is something else, which means it
# holds work that native/patches does not.
#
#   same    the tree is exactly the series
#   behind  the tree is a prefix of it; safe to throw away and remake
#   ahead   it is not; refuse, because remaking it would lose something
tree_state() {
  local mine theirs a b n=0
  mine="$BUILD/src/.cmp-tree"; theirs="$BUILD/src/.cmp-series"

  [ -n "$( dirty )" ] && { echo ahead; return; }

  series_from_tree "$mine"
  series_from_patches "$theirs"

  for a in "$mine"/*.patch; do
    [ -e "$a" ] || break
    b="$theirs/$(basename "$a")"
    [ -f "$b" ] && cmp -s "$a" "$b" || { echo ahead; return; }
    n=$(( n + 1 ))
  done

  if [ "$n" -eq "$( ls "$theirs"/*.patch 2>/dev/null | wc -l )" ]; then
    echo same
  else
    echo behind
  fi
}

ensure() {
  if [ -d "$SRC/.git" ]; then
    case "$( tree_state )" in
      same)   return 0 ;;
      behind)
        echo "=== fuse working tree: native/patches has moved on, remaking it ==="
        # The per-ABI build trees were made from the old sources and compare
        # timestamps against them, so they go too. This is what a cached CI
        # build-native/ needs, and getting it wrong is not a build failure but
        # a library quietly missing a patch.
        rm -rf "$SRC" "$BUILD/fuse"
        ;;
      ahead)
        echo "the Fuse working tree holds work that native/patches does not:" >&2
        dirty >&2
        fgit log --oneline upstream..HEAD | sed 's/^/  /' >&2
        echo "run 'scripts/fuse-src.sh save' to keep it, or 'reset' to lose it" >&2
        exit 1
        ;;
    esac
  elif [ -d "$SRC" ]; then
    # A tree from before this repository existed, or half a checkout.
    rm -rf "$SRC" "$BUILD/fuse"
  fi

  [ -f "$TARBALL" ] || {
    echo "no $TARBALL - run scripts/build-native.sh first, which fetches it" >&2
    exit 1; }

  # From the tarball rather than from vendor/, so the baseline is the release
  # and nothing else. Fuse's perl codegen writes settings.c, the z80 opcode
  # tables and the widget menus into the *source* directory, so any tree that
  # has been built in - which vendor/ was, before the build moved here - holds
  # files a fresh checkout would not, and the patches would be against a
  # baseline only this machine has.
  echo "=== fuse working tree: unpacking $FUSE_VER and applying patches ==="
  mkdir -p "$(dirname "$SRC")"
  tar xzf "$TARBALL" -C "$(dirname "$SRC")"

  fgit init -q
  fgit add -A
  fgit commit -qm "fuse $FUSE_VER as released"
  fgit tag upstream

  apply_patches
}

# settings.c and settings.h, from settings.dat. Run when the tree is made, and
# by hand - `fuse-src.sh regen` - after editing settings.dat in the tree, since
# then nothing else will: make would write them to the build tree, where the
# quoted include cannot see them.
regenerate() {
  ( cd "$SRC" \
    && perl -I perl settings.pl settings.dat > settings.c.tmp \
    && mv settings.c.tmp settings.c \
    && perl -I perl settings-header.pl settings.dat > settings.h.tmp \
    && mv settings.h.tmp settings.h ) || {
      echo "the perl codegen for settings.dat failed" >&2; exit 1; }
  echo "regenerated settings.c and settings.h from settings.dat"
}

apply_patches() {
  local p n=0
  shopt -s nullglob
  for p in "$PATCHES"/*.patch; do
    fgit am --3way --keep-non-patch "$p" >/dev/null || {
      echo "failed to apply $(basename "$p"); the tree is left mid-am." >&2
      echo "Fix it with: scripts/fuse-src.sh git am --show-current-patch" >&2
      exit 1; }
    n=$(( n + 1 ))
  done
  shopt -u nullglob

  # Fuse generates settings.c and settings.h from settings.dat with perl, and
  # the release ships the results - so a patch that changes settings.dat has to
  # be followed by a regeneration, and it has to land in *this* tree rather
  # than in the build tree.
  #
  # It cannot be left to make. `#include "settings.h"` from a file in the
  # source tree resolves to the source tree's own copy before any -I is looked
  # at, so a build-tree copy with the new setting in it loses to the shipped
  # one sitting beside machine.c, and the compile fails on a member that is
  # demonstrably there. That is worth a paragraph because it looks exactly like
  # a stale build.
  #
  # Doing it here rather than carrying the results in the patches keeps
  # generated files out of the series, where they conflict on every upstream
  # release for no reason.
  if fgit diff --name-only upstream..HEAD | grep -qx settings.dat; then
    regenerate
  fi

  # And the objects have to notice: git writes every patched file with today's
  # timestamp, but which of a file and its generated output lands first inside
  # the same second is not something to rely on.
  ( cd "$SRC" && fgit diff --name-only upstream..HEAD | while read -r f; do
      [ -e "$f" ] && touch "$f"
    done )

  echo "applied $n patch(es) from native/patches"
}

case "${1:-}" in

  path)
    # Deliberately silent and side-effect free: the build substitutes this.
    echo "$SRC"
    ;;

  ensure)
    ensure
    ;;

  reset)
    if [ -d "$SRC" ] && [ -n "$( dirty )" ]; then
      echo "the working tree has uncommitted changes:" >&2
      dirty >&2
      echo "commit them and save them, or lose them with: rm -rf '$SRC'" >&2
      exit 1
    fi
    rm -rf "$SRC"
    # The per-ABI build trees compare timestamps against this tree, and a file
    # a dropped patch has *reverted* comes back with its original mtime - older
    # than the object built from the patched one, so make would keep the stale
    # object and the change would appear not to have happened.
    rm -rf "$BUILD/fuse"
    ensure
    ;;

  status)
    [ -d "$SRC" ] || { echo "no working tree; run: scripts/fuse-src.sh ensure"; exit 0; }
    echo "tree:    $SRC"
    echo "patches: $(ls "$PATCHES"/*.patch 2>/dev/null | wc -l) in native/patches"
    echo "commits on top of upstream:"
    fgit log --oneline upstream..HEAD | sed 's/^/  /'
    if [ -n "$( dirty )" ]; then
      echo "uncommitted changes (NOT in any patch):"
      dirty | sed 's/^/  /'
    fi
    ;;

  diff)
    shift
    fgit diff "$@"
    ;;

  save)
    [ -d "$SRC" ] || { echo "no working tree" >&2; exit 1; }
    if [ -n "$( dirty )" ]; then
      echo "uncommitted changes; commit them first - a patch is made from" >&2
      echo "commits, so anything else would be silently left out:" >&2
      dirty >&2
      exit 1
    fi
    mkdir -p "$PATCHES"
    rm -f "$PATCHES"/*.patch
    # --zero-commit so regenerating an unchanged series is a no-op in git
    # rather than a diff of hashes.
    fgit format-patch --quiet --no-signature --zero-commit \
        --output-directory "$PATCHES" upstream..HEAD
    ls "$PATCHES"/*.patch | sed "s|^$ROOT/|saved |"
    ;;

  regen)
    regenerate
    ;;

  dirty)
    # For the build script, which warns about a tree nobody else can reproduce.
    [ -d "$SRC/.git" ] && dirty
    ;;

  git)
    shift
    fgit "$@"
    ;;

  *)
    sed -n '2,/^set -euo/p' "$0" | sed 's/^#\{1,2\} \{0,1\}//; s/^#$//' >&2
    exit 1
    ;;
esac
