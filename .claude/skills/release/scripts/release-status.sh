#!/usr/bin/env bash
# Everything the release skill needs to know before it changes anything.
#
# One script rather than a dozen commands typed each time, because half of
# these are questions whose wrong answer is only discovered after a tag has
# been pushed - and a pushed tag is the one step of a release that cannot be
# taken back quietly.
#
#     .claude/skills/release/scripts/release-status.sh
#
# Prints, in order: the preflight verdict, the current and last released
# versions, and every commit since that release with its body. Exits non-zero
# when something would stop the release, so it can gate the rest.

set -u

cd "$(git rev-parse --show-toplevel)" || exit 1

fail=0
note() { printf '  %-6s %s\n' "$1" "$2"; }
bad()  { note "BLOCK" "$1"; fail=1; }
ok()   { note "ok" "$1"; }
warn() { note "warn" "$1"; }

echo "PREFLIGHT"

branch="$(git rev-parse --abbrev-ref HEAD)"
[ "$branch" = "main" ] && ok "on main" \
                       || bad "on '$branch', not main - a release is cut from main"

if [ -n "$(git status --porcelain)" ]; then
    bad "the tree is dirty - commit or stash first, the tag must point at what ships"
else
    ok "tree is clean"
fi

git fetch -q origin 2>/dev/null
behind="$(git rev-list --count HEAD..origin/main 2>/dev/null || echo '?')"
ahead="$(git rev-list --count origin/main..HEAD 2>/dev/null || echo '?')"
[ "$behind" = "0" ] && ok "up to date with origin/main" \
                    || bad "$behind commit(s) behind origin/main - pull first"
[ "$ahead" = "0" ] || warn "$ahead commit(s) not pushed yet - they will go with the release"

gh auth status >/dev/null 2>&1 && ok "gh is authenticated" \
                               || bad "gh is not authenticated - run: gh auth login"

version="$(sed -n 's/^version=//p' version.properties)"
last="$(git tag --list 'v*' --sort=-v:refname | head -1)"

echo
echo "VERSIONS"
note "" "version.properties says $version"
note "" "last tag is ${last:-none}"

if [ -n "$last" ] && [ "${last#v}" != "$version" ]; then
    warn "version.properties is already ahead of the last tag - a bump may have"
    warn "landed without being tagged. Check before bumping again."
fi

echo
echo "COMMITS SINCE ${last:-the beginning}"
range="${last:+$last..}HEAD"
count="$(git log --oneline "$range" | wc -l | tr -d ' ')"
if [ "$count" = "0" ]; then
    bad "nothing since $last - there is no release to cut"
else
    note "" "$count commit(s)"
    echo
    # Trailers are for git, not for anybody reading what changed.
    git log --no-merges --format='--- %h %s%n%b' "$range" \
        | grep -v '^Co-Authored-By:\|^Co-authored-by:\|Generated with \[Claude'
fi

echo
echo "WHAT THEY TOUCHED"
git diff --stat "$range" -- . | tail -30

echo
[ "$fail" = "0" ] && echo "READY" || echo "NOT READY - fix the BLOCK lines above"
exit "$fail"
