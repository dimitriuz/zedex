---
name: release
description: Cuts a Zedex release end to end - reads everything that has changed since the last tag, asks for the version number when one was not given, bumps version.properties, tags and pushes, watches the signing workflow, writes real release notes onto the draft it produces, and publishes once the user confirms. Use this whenever the user mentions releasing, cutting or shipping a release, tagging a version, bumping the version, publishing to GitHub Releases, or writing release notes - and also when they only say a version number ("let's do 1.7.0"), ask what has gone in since the last release, or ask whether something is worth a release yet.
---

# Cutting a Zedex release

A release here is not something you create on GitHub. **You push a tag, and
the tag is the whole trigger**: `.github/workflows/release.yml` fires on `v*`,
checks the tag against `version.properties`, builds and signs the APK, verifies
the Play bundle, and creates the release **as a draft** with `--generate-notes`
and two assets. Everything below is arranged around that one fact.

What this adds is the part the workflow cannot do: deciding the number, and
writing notes somebody would want to read. `--generate-notes` produces a list
of PR titles, and for a release made of direct commits it produces one line -
`**Full Changelog**: .../compare/v1.6.1...v1.6.2` - which is what every recent
release says and the reason this exists.

## Step 1 - look before touching anything

```sh
.claude/skills/release/scripts/release-status.sh
```

It prints the preflight verdict, the current and last-released versions, every
commit since that release **with its body**, and what they touched. It exits
non-zero when something would stop the release.

Read the bodies rather than the subjects. This project writes commit messages
that explain why, and that is where the material for the notes is - a subject
line says *what* changed and the notes have to say what it means to somebody
downloading the APK.

Do not skip past a `BLOCK` line. Each one is there because the mistake it
catches is only discoverable after the tag is pushed, and **a pushed tag is
the one step of a release that cannot be undone quietly** - it starts a build
that publishes a draft under that name.

## Step 2 - the version number

If the user gave one, use it. If they did not, **ask** - do not infer a number
and proceed. Offer a suggestion with the reasoning beside it so the answer is
one word:

- an entry the app gains or loses, or anything a user would notice as new -> minor
- fixes and documentation only -> patch
- say plainly when the release is documentation only; that is a real answer and
  the user may decide it is not worth a release at all

Two constraints that are not stylistic:

- **It must be greater than the last tag.** `versionCode` is
  `major*10000 + minor*100 + patch` and Android refuses to install a lower one
  over a higher one.
- **No component may pass 99.** 1.6.100 and 1.7.0 both come out as 10700, so
  two different releases would claim one `versionCode`.

Check the tag is free before going on: `git tag --list v<version>` and
`gh release view v<version>`. A tag that already exists means the last attempt
got further than it looked.

## Step 3 - bump, commit, tag, push

`version.properties` is the only place the version lives, and the workflow
compares the tag against it and refuses to build if they disagree. So the bump
commit must land **before** the tag, and the tag must point at it.

```sh
sed -i 's/^version=.*/version=<version>/' version.properties
git add version.properties
git commit -m "chore: v<version>"
git push origin main
git tag v<version>
git push origin v<version>
```

The bump goes **straight onto main**, which is the standing exception to this
project's branch-then-PR rule: it is a one-line change with nothing to review,
and the tag has to point at a commit that is already on main. Every release
since the rule was adopted has been cut this way - check `git log --grep
'^chore: v'` if that ever looks wrong.

Push the commit before the tag, in two commands rather than one. If they go
together and the commit is rejected, the tag is already gone and points at
nothing anybody else can see.

## Step 4 - watch the build

```sh
gh run watch "$(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
```

Confirm the run it found is this tag's before watching it - right after a push
the newest run can still be the previous release's, and watching that one
reports a success that has nothing to do with this build.

It takes several minutes. It can fail on things that have nothing to do with
the code - a missing repository secret, the signing keystore, the Play bundle
checks - and every one of those failures is a workflow that stopped **before**
creating the release, so there is nothing to clean up but the tag.

If it fails, say what failed and stop. Do not retry a tag: fix the cause, then
`git push --delete origin v<version> && git tag -d v<version>`, and start again
from Step 3. If a draft release was already created, `gh release delete
v<version>` first, or the retag will attach to the old one.

## Step 5 - the notes

The workflow has now created a draft. Rewrite its body, keeping GitHub's own
compare link at the foot - it is the only part of the generated notes worth
having, and people do follow it.

**One line per feature or fix.** Not a paragraph, not prose that sets the
scene - the same rule the README lives under, for the same reason: somebody is
deciding whether to take an update, and a wall of text buries the thing they
came for. Group under `## What's new`, `## Fixed` and `## Also` where there is
enough to group.

```markdown
## What's new

- Map a controller's buttons to the machine's eight controls - *Settings › Controls › Controller buttons…*
- A direction can be captured from a stick or hat push, for a pad whose D-pad is an axis
- Each pad keeps its own mapping; a picker appears once there is more than one

## Fixed

- Rebinding a hotkey with a controller never worked: the dialog swallowed every press

**Full Changelog**: https://github.com/dimitriuz/zedex/compare/v1.6.2...v1.7.0
```

What makes these notes good rather than a rearranged commit log:

- **One line, and it says what changed.** A clause of why is fine where the
  what is meaningless without it - "for a pad whose D-pad is an axis" earns its
  place because otherwise nobody knows who the row is for. Two sentences does
  not.
- **Group by what somebody would notice**, not by commit prefix. Three commits
  that together added one feature are one line.
- **Drop what does not reach the user.** Refactors, tests, CI, and the internal
  half of a change nobody can see. If that empties the list, say so in a
  sentence - "a documentation release, nothing in the emulator itself" is more
  useful than five lines about markdown.
- **Name the thing, not the file.** "Zedex can be added to the Cocoon frontend"
  over "added docs/frontends/ZXSpectrum-Zedex.json".
- **Carry over anything a user must do.** A new permission, a setting that
  moved, a folder to pick again. This is the only place they will read it.
- **Do not invent.** Everything has to be in the commits, and where a change was
  measured on a device and where it was not, the commit body says so - the notes
  must not upgrade the second into the first.

Then put them on the draft, from a file rather than a flag so the markdown
survives the shell:

```sh
gh release edit v<version> --notes-file <path>
gh release view v<version> --web
```

## Step 6 - publish, once the user says so

Show the user the draft and ask. Say what publishing means, because it is not
obvious and it is not reversible in any way that helps:

> Publishing makes the assets reachable, and the app's update check reads the
> newest release - so every install starts being offered this build the moment
> you say yes. Nothing offers it while it is a draft.

The right thing between here and there is to install the APK from the draft and
check it runs. Offer that; do not insist on it.

```sh
gh release edit v<version> --draft=false
```

## What the release publishes, and why the names matter

Two assets, `Zedex-<version>.apk` and `Zedex-<version>.apk.sha256`.

**The app builds those names itself.** `Updater` composes
`Zedex-<version>.apk` from the version it read, and fetches its own version's
`.sha256` at each start - which is the project's only measure of how many
people are running it. Renaming an asset in the workflow silently stops updates
being offered and silently stops the count. If a release ever needs a different
asset name, that is a change to the app as well.
