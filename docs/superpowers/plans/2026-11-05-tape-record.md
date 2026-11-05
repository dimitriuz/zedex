# Tape Record Start/Stop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Fuse's *Media → Tape → Record Start / Record Stop* to Zedex, so that a program saving through its own routine (which the ROM save trap never sees) can still be captured onto the tape and written out (GitHub issue #31).

**Architecture:** Fuse already does the work. `tape_record_start()` samples the ULA's tape output at 44.1 kHz, and `tape_record_stop()` appends that as one RLE-pulse block to the tape in memory (`vendor/fuse-1.9.1/tape.c:771-873`). While it runs, the save trap is bypassed (`tape.c:600`), so even a ROM `SAVE` goes to the recording. Zedex adds a command on the bridge queue plus a published "is recording" flag, then a row in the Media sheet's Tape section. Saving the result is the existing *Save tape…*.

**Tech Stack:** C (`native/ui/android`), Java (Android, minSdk 30), UI Automator instrumentation tests.

**Spec:** this plan; the source is issue #31 (the second comment is the request).

## Global Constraints

- Never modify `vendor/`. Everything native goes in `native/ui/android/`. No `native/patches` change is needed.
- Fuse is single-threaded: Java reaches it only through the bridge queue (`queue_command`), drained on the emulation thread. The UI reads state only through what `androidstate_publish()` copies under `machine_mutex`.
- Native change ⇒ `ABIS=x86_64 ./scripts/build-native.sh`, then `./gradlew assembleDebug`. Gradle only packages the prebuilt `.so`.
- `env JAVA_HOME=/opt/android-studio/jbr` on every Gradle call.
- Every new string goes in all ten `values*/strings.xml` (`values`, `-cs -de -es -fr -it -pl -pt -ru -uk`), as natural translations, and `python3 scripts/check-strings.py` must pass.
- Commits: conventional prefix (`feat:`, `test:`, `docs:`), a body that explains why, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Branch `feature/tape-record` off `main`. Never commit to `main`.

## Review Focus

1. **Double start / stray stop.** Fuse's functions don't guard against these. A second `tape_record_start()` leaks the buffer and schedules a second sampling event; a stop while not recording appends a garbage block from a NULL buffer. The bridge must guard both (Task 1 test `startTwiceThenStopAppendsOneBlock`).
2. **The sheet while recording.** Load, New tape, Play and Save during a recording would mutate or write the tape that the recording is about to be appended to. Ruling: while recording, the Tape section shows **only** *Stop recording* (Task 2 test `recordingHidesTheOtherTapeRows`).
3. **Recording onto no tape at all.** This is the main use (New tape, then Record), so *Record* must show even when `hasTape()` is false. *Save tape…* must appear once the stop has appended the block (Task 2 round-trip test).
4. **Turbo toggled mid-recording.** `tstates_per_sample` is fixed at start from `processor_speed`. A recording that spans a turbo change plays back at the wrong speed. Not fixed here (Fuse desktop has the same limit). The reviewer checks that nothing *worse* happens, such as a crash.
5. **Quitting or machine change while recording.** The in-progress buffer is lost, as in Fuse. The reviewer checks that nothing crashes and that the lamp clears. `android_status.c:364` already lights the tape-write lamp from `tape_recording`.

---

### Task 1: The record command and its published state

**Files:**
- Modify: `native/ui/android/android_bridge.c`: enum near `:124`, drain switch near `:735`, a JNI entry near `:1390`
- Modify: `native/ui/android/android_state.c`: statics near `:48`, `androidstate_publish()` near `:173`, a JNI getter near `:394`
- Modify: `app/src/main/java/dev/ldlab/zedex/FuseNative.java`: next to `tapePlaying()` (`:313`)
- Test: `app/src/androidTest/java/dev/ldlab/zedex/TapeRecordTest.java` (new)

**Interfaces:**
- Produces: `public static native void tapeRecord(boolean on)` (queues it) and `public static native boolean tapeRecording()` (the published flag, so it lags up to a frame), both on `FuseNative`.

- [ ] **Step 1: Write the failing test** `TapeRecordTest`, in the shape of `NativeContractTest`: `emulator.useDataFolder(); emulator.launch(); assumeFalse(needsRoms)`. Put a `@Before`/`@After` pair that calls `FuseNative.tapeRecord(false)` and polls until `!tapeRecording()`, so a failed run can't leave the bench recording. Use a helper `awaitRecording(boolean)` that polls `tapeRecording()` every 50 ms for up to 2 s. Every test starts with `FuseNative.newTape()` and then waits until `FuseNative.tapeBlocks().length == 0`.
  - `recordingAppendsOneBlock`: `tapeRecord(true)`, then `assertTrue(awaitRecording(true))`, `emulator.idle(1000)`, `tapeRecord(false)`, `assertTrue(awaitRecording(false))`. Poll until `tapeBlocks().length == 1` and assert exactly 1. Also assert `hasTape()`.
  - `startTwiceThenStopAppendsOneBlock`: `tapeRecord(true)` twice with a 200 ms idle between, then stop. Assert exactly 1 block, and that it still holds 1 after a further 500 ms.
  - `stopWithoutStartAppendsNothing`: `tapeRecord(false)`, idle 500 ms, then assert 0 blocks and `!tapeRecording()`.

- [ ] **Step 2: Run it and see it fail.** It must fail to compile (`tapeRecord` undefined). Use the by-hand route from CLAUDE.md (*Tests*): `assembleDebug assembleDebugAndroidTest`, `adb install -r` both, then `adb shell am instrument -w -r -e class dev.ldlab.zedex.TapeRecordTest dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner`.

- [ ] **Step 3: Implement.**
  - `COMMAND_TAPE_RECORD` (a: 1 start, 0 stop) goes after `COMMAND_TAPE_BLOCK`. Its drain case starts only `if( !tape_recording )` and stops only `if( tape_recording )`, with a comment saying why: Fuse's own functions don't guard (Review Focus 1). After a stop, set `androidstate_tape_changed = 1`, the same as `COMMAND_OPEN_FILE` does, so the block browser refreshes.
  - `Java_dev_ldlab_zedex_FuseNative_tapeRecord(JNIEnv*, jclass, jboolean on)` calls `queue_command( COMMAND_TAPE_RECORD, on ? 1 : 0, 0 )`.
  - In `android_state.c`: `static int tape_record_on;`, set in `androidstate_publish()` as `tape_record_on = tape_recording;` (declared in Fuse's `tape.h:76`). `Java_dev_ldlab_zedex_FuseNative_tapeRecording` reads it under `machine_mutex`, in the same shape as `tapePlaying`.
  - `FuseNative` gets both natives with short javadocs. `tapeRecord`'s javadoc says what Record is for: a save through a program's own routine that the trap never sees; the result is a block on the tape, written out by *Save tape…*.

- [ ] **Step 4: Run and pass.** `ABIS=x86_64 ./scripts/build-native.sh`, then the Step 2 commands. Expect `OK (3 tests)`, and in `-r` output no `ASSUMPTION_FAILURE`. Then prove the guard test can fail: temporarily remove the `!tape_recording` guard, rebuild, and see `startTwiceThenStopAppendsOneBlock` fail (or crash the run). Restore the guard, rebuild, and see it pass. Note the result in the report.

- [ ] **Step 5: Commit** `feat: tape recording through Fuse's record start/stop` (the native files, `FuseNative.java`, `TapeRecordTest.java`).

---

### Task 2: The Record row in the Media sheet, strings, docs

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/media/Media.java`: `fill()` (`:415-444`) and the `// --- the tape` section (`:563`)
- Modify: all ten `app/src/main/res/values*/strings.xml`
- Modify: `docs/USING.md`, the Tape table (`:186-197`)
- Test: `app/src/androidTest/java/dev/ldlab/zedex/TapeRecordTest.java` (add two tests)

**Interfaces:**
- Consumes: `FuseNative.tapeRecord(boolean)` and `FuseNative.tapeRecording()` from Task 1.

**Strings (English, verbatim):**
- `tape_record`: `Record`
- `tape_record_stop`: `Stop recording`
- `tape_recording_started`: `Recording — start the save in the program, then Stop recording when it ends`
- `tape_recording_stopped`: `Recording added to the tape — Save tape… keeps it`

- [ ] **Step 1: Write the failing tests** (add them to `TapeRecordTest`). Set the world explicitly with `emulator.useSpectrum48()`. Read `docs` and `Emulator` for how menus are walked (`emulator.menu("Media")`, `isInMenu`, `tap`). Match rows by the English strings above. The bench must be in English: follow what `LanguageTest`/other menu tests do to pin the language, or `assumeTrue` on it.
  - `recordingHidesTheOtherTapeRows`: new tape (natively, as in Task 1), then `menu("Media")` and tap *Record*. Reopen Media and assert *Stop recording* is in the menu and *Load a tape…*, *New tape*, *Play* and *Save tape…* are not. Tap *Stop recording*, reopen, and assert *Record* and *Save tape…* are back.
  - `aRecordedSaveLoadsBack` (the round trip, and what issue #31 is really asking for):
    1. Open a `TapeProgram` with lines `10 BORDER 4: STOP` and `20 SAVE "rec" LINE 10`, `startingAt(20)`. It autoloads, runs line 20 and stops at the ROM's *Start tape, then press any key*.
    2. Natively `newTape()` and wait for 0 blocks, so the deck holds only what is recorded.
    3. Through the menu: Media → *Record*. Close the menu, then `emulator.key("ENTER")` to let the save start.
    4. Wait for the save to finish: poll `borderColour()` back to white after the stripes, or idle a measured fixed bound and say so in a comment. Then Media → *Stop recording*, and assert `tapeBlocks().length == 1`.
    5. Reset the machine (Machine → Reset, or whatever row `Emulator` already uses) and wait for the border to be white (7). Then rewind, type `LOAD ""` + ENTER the way other tests type keywords (`J` is LOAD at the prompt; quotes via `symbolShift("P")`), and press *Play* through Media.
    6. Poll `borderColour() == 4` (green) for up to 90 s. Assert it. Green is in neither loading-stripe pair, so only the loaded program running sets it.
  - Restore in `@After` whatever the test changed (machine, tape state).

- [ ] **Step 2: Run and see both fail** (no *Record* row).

- [ ] **Step 3: Implement in `Media.fill()`.**
  - Read `boolean recording = FuseNative.tapeRecording();` first.
  - When `recording`: the Tape section is the single row *Stop recording* (`ic_stop`), going to `recordTape(false)`. Nothing else in the Tape section (Review Focus 2). The disks and card sections are unaffected.
  - Otherwise, keep the existing rows in their order, and add *Record* (`ic_record`) shown **regardless of `hasTape()`**. It goes after *Blocks…* when there is a tape, or after *Load a tape…* when there isn't, and always before *Save tape…*/*New tape*.
  - `private void recordTape(boolean on)` calls `FuseNative.tapeRecord(on)` and `host.note(on ? R.string.tape_recording_started : R.string.tape_recording_stopped)`, mirroring `playTape`.
  - Update the `fill()` javadoc paragraph: *Save tape…* writes what the trap caught **and** what a recording captured, and Record exists for saves the trap never sees.

- [ ] **Step 4: Strings.** Add the four keys to `values/strings.xml` beside the other `tape_*` keys, and natural translations to the nine other locales. Keep "Save tape…" in each translation identical to that locale's own `tape_save` string, and use `…` the way neighbouring strings do. Run `python3 scripts/check-strings.py`; expect no failure.

- [ ] **Step 5: Docs.** In `docs/USING.md`'s Tape table, add a row after *Blocks…*: `<img src="icons/record.svg" width="16">` | **Record** / **Stop recording** | captures what the machine sends to the tape, for a game that saves through its own routine rather than the ROM's (Level 9's later adventures, such as *The Price of Magik*), so *Save tape…* alone would miss it; stopping adds it to the tape, and *Save tape…* writes it out |. Change the line under the table to say *Record* needs no tape either, and that while recording, *Stop recording* is the only tape row. `docs/icons/record.svg` and `stop.svg` already exist, so don't regenerate icons. Leave `README.md` alone.

- [ ] **Step 6: Run and pass.** Run the full `TapeRecordTest` (5 tests) with `-r`, and read the count against the five `@Test`s.

- [ ] **Step 7: Commit** `feat: Record and Stop recording in the tape menu` (the Java, the strings, the test), and separately `docs: the tape menu's Record row`.

---

### Task 3: Check it against a real Level 9 adventure (by hand, not the local model)

Issue #31 names Level 9's in-game save. This task answers two questions the synthetic round trip in Task 2 can't. Done by the coordinating agent on the AVD. It needs judgement about what is on screen, and the game is not ours to commit, so there is **no committed test and no game file in the repo**.

**Needs:** two Level 9 tapes from the owner's collection, an early engine and a later one, because the save routine may differ between them:
`/mnt/nas/downloads/retro/retronas/mister/games/Spectrum/games/all_games/S/Snowball 48K (1983)(Level 9 Computing).tzx` and
`/mnt/nas/downloads/retro/retronas/mister/games/Spectrum/games/all_games/P/Price of Magik, The 48K-128K (1986)(Level 9 Computing).tzx`.
Copy them to the scratchpad and push them to the AVD. They are never committed. Step 1 is for each game; Steps 2–3 only for one the trap misses. Run on a **Spectrum 48K**.

**Step 1 was measured on 2026-11-05 on the Medium_Phone AVD, using the v1.7.4 debug build, on a Spectrum 48K.** Snowball does not take keys on a 128K. That is the game, not Zedex.
- *Snowball (1983):* `SAVE` printed the ROM's own *Start tape, then press any key.* The trap appended a header and a 702-byte block, and `RESTORE` read it back (*Bytes: snowdat*). **Record is not needed for it.**
- *The Price of Magik (1986):* `SAVE` went straight to a real-time save, with red/cyan pilot stripes and no prompt. **No block was added** (the deck still ended at its own block 23, 36,162 bytes). The trap never sees it, so **Record is required**, and this is the game for Steps 2–3.

- [x] **Step 1: Does the trap already catch it?** Fast loading on, no recording. Load the game, then `SAVE` in the game and answer its prompts. Afterwards, does *Blocks…* show new blocks? Record the answer: yes means the ROM routine and the existing *Save tape…* already work; no means a custom routine, and Record is required.
- [ ] **Step 2: Save with Record.** Fresh load; New tape; Media → *Record*; `SAVE` in the game; *Stop recording* when the game says it is done; *Save tape…* to a `.tzx`.
- [ ] **Step 3: Restore.** Reset, load the game again, and open the saved `.tzx`. Use the game's `RESTORE` and play the tape when asked. Pass means the game reports the restore and the position (location, inventory, score) matches what was saved. Take a screenshot before saving and after restoring.
- [ ] **Step 4: Record the outcome** in the PR description: Step 1's answer, pass or fail for the restore, and the game and version used. The outcome also decides the wording of the reply on #31.
