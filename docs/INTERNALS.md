# Zedex internals

How the Android front end is bolted onto its emulation core, and the things
that were surprising enough to be worth writing down. The core is
[Fuse](http://fuse-emulator.sourceforge.net/) and libspectrum, used
completely unmodified — no patches, no forks — so most of what follows is
about achieving that without touching a line of upstream.

### Keeping Fuse unmodified

Fuse's display, keyboard, menus, file selection and debugger all live in its
UI layer, and `ui/gtk3`, `ui/win32`, `ui/sdl2`, `ui/fb` and `ui/null` are peers
there — so an Android port belongs in that layer. Adding a `ui/android` to
Fuse's own build system would mean patching `configure.ac` and `Makefile.am`
and regenerating autotools output on every upgrade.

Instead the build configures Fuse `--with-fb`. That selects Fuse's portable
**widget UI** — the dialogs, menus, debugger and file selector, all software
drawn — and pulls in nothing framebuffer specific outside `ui/fb` itself; the
only coupling in the core is one `isatty()` guard in `ui.c`. The build then
never compiles `ui/fb`, and links `native/ui/android` in its place. The same
trick swaps `sound/nullsound.o` for the AAudio driver.

The script asks the generated Makefile for `fuse_OBJECTS`, `fuse_LDADD`,
`AM_CPPFLAGS` and friends rather than hardcoding them, so the substitution
follows upstream instead of drifting from it.

### The backend

- **`android_display.c`** writes palette indices exactly as `ui/fb` does,
  expands the frame to RGBA once, and leaves Fuse's software scalers at 1x:
  scaling and filtering are the GPU's job. It also implements
  `uidisplay_frame_save`/`_restore`, which `ui/fb` leaves as FIXMEs, so
  dialogs no longer corrupt the screen behind them.
- **`android_gl.c`** owns EGL and a GLES 3 context on the emulation thread and
  draws the frame as an aspect-corrected quad. The fragment shader is the only
  code that touches pixels, which is where CRT/scanline filters go.
- **`android_bridge.c`** is the Android boundary. Fuse's core is single
  threaded, so everything arriving from the UI thread is queued and replayed
  on the emulation thread from `ui_event()`. It also runs the window handover:
  `surfaceDestroyed()` blocks until the emulation thread has released the
  surface.
- **`keysyms.c`** maps Android keycodes to Fuse input keys, so physical keys
  and the on-screen keyboard share one path — including Caps Shift
  (`SHIFT_LEFT`) and Symbol Shift (`CTRL_LEFT`), which Fuse already maps.
- **`aaudiosound.c`** writes to AAudio and *blocks*, deliberately: that is what
  paces the emulator. Audio is the clock, not vsync and not a wall timer.
- **`ui_error_specific`** in `android_ui.c` turns Fuse's errors into Android
  toasts. Fuse would otherwise draw a Spectrum-styled modal into the emulated
  screen that only Enter or Escape dismisses — and, worse, block whatever
  raised it until then: a save to a lossy snapshot format did not write its
  file until the warning had been answered. `ui/widget/error.c` cannot simply
  be dropped, because `ui/widget/query.c` shares its `split_message`, so the
  build weakens that one symbol with `llvm-objcopy --weaken-symbol` and ours
  wins the link.

A key release is never run in the same queue pump as its press. The Spectrum
ROM scans the keyboard once per frame, so a press and release arriving
together — a synthesised tap, or a very fast finger — would otherwise be
invisible to the emulated machine.

### The menu

Everything the menu does goes through the same queue as keys, because none of
it is safe to call from the UI thread: the Android dialog queues a command and
the emulation thread runs `machine_select()`, `machine_reset()` or
`event_add( 0, z80_nmi_event )`. The machine list is snapshotted from Fuse's
`machine_types` on the emulation thread for the UI thread to read back.

A machine change can fail — Fuse falls back to 48K when a machine's ROMs are
missing — so the app checks what actually ended up running, says so, and
remembers that rather than what was asked for.

NMI is the magic button, and what it does depends on the machine. On Scorpion,
`z80_nmi()` pages ROM 2 — the Shadow service monitor — before jumping to
0x0066. On Beta-equipped machines such as Pentagon it pages TR-DOS instead.
Pentagon 512K and 1024K need no NMI to reach Gluck: their reset sets
`beta_active`, which selects ROM 2 at boot, so they start in the service ROM.

### Opening files

Fuse opens files by path and identifies them by content, so the Android side
does not need to know one format from another: the picked document is copied
out of its content provider into the cache — keeping its original name, since
libspectrum uses the extension as a hint — and the path is queued for the
emulation thread, which hands it to `utils_open_file()`.

### Saving tapes

The machine writes to its tape as well as reading from it. With fast loading
on, Fuse's `tape_save_trap()` catches the ROM's save routine and appends each
block to the tape held in memory, so `SAVE "name"` from BASIC lands there;
*Save tape…* then calls `tape_write()`, which picks TAP or TZX from the
extension. It needs fast loading, since that is what puts the trap in place —
without it the save goes out as audio that nothing is recording.

*New tape* clears `tape_modified` before calling `tape_close()`. Fuse would
otherwise ask whether to save first, through a widget dialog that only Enter
or Escape dismisses; Android has already asked by that point.

One thing worth knowing at the *Start tape, then press any key* prompt: press
Enter rather than Space. Space aborted the save with `D BREAK` in testing.

### Screenshots and recording

Fuse hands over a frame of palette indices, and both formats are built from
those rather than from the expanded picture. That is what makes this cheap
enough to run on a 50Hz machine:

- a **GIF** is a palette format too, so the indices are the pixels. Sixteen
  colours go in the global colour table and nothing is quantised or dithered
  — the file is exact. It is written frame by frame as they arrive, so length
  costs no memory, and the delay of each frame is measured rather than
  assumed, so a recording plays back at the speed it happened.
- an **MP4** goes through the device's own H.264 encoder, which wants YUV.
  Converting the sixteen palette entries once a recording turns every pixel
  into three table lookups.

Frames are copied out on the emulation thread — the callback happens while
Fuse is between frames, so the buffer is whole — and encoded on another. When
the encoder falls behind the frame is dropped rather than waited for: a
recording that skips beats a machine that stutters. A GIF is capped at 25 fps
because its delays are in hundredths of a second; an MP4 takes all 50.

Neither has sound yet.

*Open recordings folder* asks the file manager to show the folder, which
only works when the data folder is on shared storage — the app's own
directories are invisible to the rest of the system by design, and then the
path is all there is to offer. The intent needs its own task, because this
activity is `singleInstance` and the file manager would otherwise be handed
the intent in the background and never come forward. The path is compared
through `getCanonicalPath`, since `/sdcard`, `/storage/self/primary` and
`/storage/emulated/0` are all the same folder and only the last is what
`getExternalStorageDirectory` answers with.

### What the app offers to open

Spectrum media has no MIME types of its own, so `.tap`, `.tzx`, `.z80`,
`.szx`, `.sna`, `.trd`, `.dsk`, `.scl` and the rest all arrive as
`application/octet-stream` and Fuse identifies the content itself. The
manifest matches that, and a `content` or `file` scheme.

It used to match `*/*`, which made the app a candidate for opening every
file on the device: its own recordings offered themselves back to it instead
of to a video player, and it competed with the file manager for
`vnd.android.document/directory` when asked to show a folder. Now a GIF or
an MP4 offers only a video player, a folder only the file manager, and a
`.trd` offers Fuse.

### Disks

Every disk interface Fuse emulates — +3, Beta 128, +D, DISCiPLE, Opus,
Didaktik — registers its drives with `ui_media_drive_register()`, so the menu
is built by walking `ui_media_drive_find()` over the controllers rather than
naming any of them. Each drive is asked `is_available()` first, which is how
the list follows the machine: a +3 has no Beta drives and a Pentagon no +3
ones. The names shown are Fuse's own.

Loading, creating and ejecting all go through
`ui_media_drive_insert()` / `ui_media_drive_eject()`, with `disk.dirty`
cleared first: those would otherwise ask about losing changes through a
widget dialog only Enter or Escape dismisses, and Android has already asked.

A disk made with *New disk* is **unformatted** — Fuse's `disk_new()` gives it
geometry but no filesystem — so there is nothing to write until the machine
formats it. Saving one produced a silent zero byte file, so a failed or empty
write now deletes the file and says why.

Once the machine has formatted it, it writes: a blank disk in a Scorpion's
Beta A:, `FORMAT "test"` at the TR-DOS prompt, `SAVE "hi"`, then *Save…*
gives a 655360 byte TRD whose catalogue reads `test`, one file `hi`, 2543
free — and reopening it in the app shows the same.

The write itself is `disk_write()`, which picks its format from the extension
once `disk.type` is cleared — the same thing Fuse's own save-as does, minus
`ui_get_save_filename()` and the modal file selector behind it. Not every
format Fuse reads can be written: an `.scl` in particular has to come back as
a `.trd`, so the interface decides the default extension.

Disks are always written as a new file in the data folder, never back over
the one that was opened, because what was opened is a copy staged in the
cache. Expect the bytes to differ from the original even when nothing has
changed: Fuse writes them out of its own in-memory track representation
rather than copying the file.

### Save states

States live under `files/states`, named rather than numbered, so there can be
any number of them and each says what it is. The format is chosen in settings
and decided by the file's extension, which is all `snapshot_write()` looks at;
a state is whichever of `.szx`, `.z80` and `.sna` carries its name, so states
written before the setting changed still load. Saving removes the others for
that name, so a name is never ambiguous.

New states are named after the media that is loaded — the base name of the
opened file, or of the state last loaded — with a number appended if that is
taken, and the name is editable before saving. A reset or a machine change
empties the machine, so there is nothing left to name a state after and they
go back to being numbered `Snapshot 1`, `Snapshot 2` and so on.

SZX is the default because it is libspectrum's own format and the only one
that can represent every machine here — a state saved on a Pentagon or a
Timex restores as itself. The other two are for exchanging states with other
emulators, and Fuse warns on every save to them that information has been
lost; that warning is now a toast (see below).

Each slot also gets a `.thumb`: the last frame at half size, written by the
display backend as a width, a height and RGBA rows, which Android decodes
straight into a `Bitmap`. It costs 76kB a slot and saves guessing which save
is which.

Both directions are queued like any other command and run between frames on
the emulation thread, which is what makes the state coherent —
`snapshot_write()` and `snapshot_read()` are the same calls Fuse's own menus
make.

Loading a state is not the only way in: a `.szx`, `.z80` or `.sna` opened
through **Open file…** takes the same path through Fuse.

### Settings

`SettingsActivity` is a plain framework `PreferenceFragment` over the same
`fuse` preferences file the emulator reads, so there is one store rather than
two. The machine list is not a fixed array: it is read back from Fuse through
`FuseNative.machineNames()`, the same snapshot the ☰ switcher uses.

Each setting is applied twice. At startup it goes on Fuse's command line —
Fuse generates `--x` / `--no-x` for every boolean setting and `--x n` for
every numeric one — so the options are in force before Fuse has finished
starting, which matters because a file arriving by intent can be loading
before the command queue is first drained. Changed later, it goes through the
queue like everything else.

Fast loading is one switch over three Fuse settings: `tape_traps` catches the
ROM's loading routine, `fastload` makes a trapped block appear at once, and
`accelerate_loader` speeds up the timing loops of custom loaders that never
call the ROM at all.

Sound settings are only read when Fuse's sound subsystem starts, so changing
one calls `fuse_emulation_pause()` / `fuse_emulation_unpause()` to restart
it — which is what Fuse's own options dialogs do.

### Sharing the window

`EmulatorLayout` is a `ViewGroup` of its own rather than nested
`LinearLayout`s, because the four landscape arrangements are not all the same
kind of container: two stack, two sit side by side, and one puts the keyboard
over the screen. Measuring both children in one place covers all of it without
ever re-parenting them — which matters, since detaching the `SurfaceView` would
destroy the surface Fuse draws into and cost a handover on every change. It
computes the two boxes in `arrange()`, called from `onMeasure` and read back in
`onLayout`, so the two cannot disagree.

The keyboard cooperates by treating an exact height as a decision already made.
Left to choose it takes its natural 541x201 aspect, which is what portrait
wants; given a box it scales to fit, centres itself and hit-tests through the
same transform, so a capped box simply letterboxes and every key still lands.
That is the whole fix for landscape: full width at that aspect made the
keyboard four fifths of the height and left the machine a slot 188 pixels tall.

Portrait is always the stacked arrangement — there is only one sensible answer
when the window is taller than it is wide — and the natural height at full
width is around a third, so the cap never bites. It is also where the screen's
box is trimmed to the height a 4:3 picture actually uses, 810 px of 1080 wide:
the renderer centres inside whatever it is given, so a full-height box put the
picture in the middle with a band of black above it as well as below. One band,
below, is what was wanted.

Hiding the keyboard sets it `GONE` rather than giving it an empty box, because
its keys are accessibility nodes and forty of them with no bounds would still
be there for a screen reader to find. That happens outside the measure pass —
changing visibility asks for another layout, and doing that during measure is
how layout loops start — so it is driven from `setTemplate` and
`onConfigurationChanged` instead. Hiding is a landscape template, so rotating
into portrait brings the keyboard back.

Four children, not two: the ROMs panel takes the whole window rather than the
screen's share, since it is a takeover rather than part of the picture, and the
☰ button is laid out last so it stays reachable over the panel — while sitting
at the top right of the *screen*, so it follows the picture when the keyboard is
beside it. The on-screen joystick belongs here too when it arrives, with each
template deciding where it goes.

### The on-screen keyboard

`SpectrumKeyboardView` draws Fuse's own `keyboard.png`. The key rectangles were
measured off the image and are held in its 541x201 coordinate space, expanded
to meet their neighbours so the gutters in the artwork are not dead to a
fingertip. Presses are tracked per pointer, which is what makes both two-finger
chords and the shift latch work.

The picture is one bitmap, so the view would otherwise be a single unnamed
`View` with nothing inside it to address. Each key is published as a virtual
accessibility node instead, named the way the Spectrum names it — `ENTER`,
`CAPS SHIFT`, `7` — which is both what makes a screen reader usable and what
lets the tests press keys without knowing a coordinate.

Sending an accessibility event when nothing is listening throws, and nothing
listening is the normal case, so the latch only announces itself when
`AccessibilityManager.isEnabled()`. That was learned the hard way: the check
was missing at first and every long press on a shift killed the app. The
instrumentation suite ran clean throughout, because UI Automator switches
accessibility *on*.

### ROMs, and where things live

No ROMs are shipped. Fuse's tarball carries the Sinclair and Timex ones and
the staged `make install` would happily bundle them, so the build deletes
them from the assets again; what does travel with the app is Fuse's own UI
data, the widget font and the status bitmaps, which are not ROMs.

A complete set under the names Fuse looks for — `48.rom`, `128-0.rom`,
`plus3-0.rom` and the rest, including the Pentagon and Scorpion ones Fuse
cannot distribute — is at
<https://archive.org/details/zx-roms-fuse-roms>. Fuse's per-machine ROM
filenames are listed in its `settings.dat` if you need to check what a
particular machine wants.

Fuse looks for a ROM in the current working directory before anywhere else,
so the app simply `chdir`s into the user's `roms` folder before starting the
emulation thread. That is the whole mechanism: no per-ROM paths on the
command line, and it follows the folder setting immediately, because `chdir`
is process wide.

Without ROMs Fuse cannot even reach the 48K machine it falls back to, and
gives up hard — `fuse_abort()` — so the app checks the folder first and does
not start the emulator at all until there is something in it.

Two failures follow from that, and they need different answers. If there is no
`.rom` at all the emulation thread is never started, so importing some and
starting it then is enough. If there *is* one but Fuse cannot use it, Fuse has
already run and `main()` has returned; `Java_dev_ldlab_zedex_FuseNative_start`
refuses a second call, since Fuse's globals are not reinitialisable, so the
only way back is a new process. The app tells the two apart by polling
`currentMachine()` for six seconds after starting — Fuse publishes a machine as
soon as one is running — and offers a restart rather than more ROMs in the
second case. Either way something is on screen saying so, because Fuse draws
nothing at all when it gives up and the surface would otherwise stay black
with no way out of it.

ROMs arrive by one of three routes: a document tree walked three deep for
`.rom` and `.zip` entries, a multiple selection from the file picker, or a
direct download of the archive.org set. Both pickers are kept because Android
refuses to grant a tree on `Download`, where a downloaded set usually lands,
while the file picker opens it without complaint. Zip entries are reduced to
their last path component before being written, so an archive carrying
directories — or `../` — cannot write outside the ROMs folder.

Save states and ROMs share a root the user picks from what the device offers.
It has to be a real filesystem path the app can write without a permission,
because Fuse reaches both with plain stdio; that means internal storage or an
app-specific external directory, not an arbitrary tree from the document
picker. The folder to read *content* from has no such restriction, since that
goes through the picker: any granted tree works as its starting point.

### Data files and environment

Fuse resolves ROMs and its widget font against the compile-time `FUSEDATADIR`,
so the build passes `--datadir=/data/data/dev.ldlab.zedex/files` and a staged
`make install` provides the assets that `EmulatorActivity` unpacks there on
first run. **`applicationId` in `app/build.gradle` and `PKG` in
`scripts/build-native.sh` must stay in sync**, and the build tree records
which package it was configured for: one that does not match is thrown away
and reconfigured, because a stale one compiles, links and installs happily
and only fails at runtime, looking for a font in the old package's
directory. The activity also points
`$HOME`, `$XDG_CONFIG_HOME` and `$TMPDIR` at app-private storage before the
emulation thread starts.

### The one real portability bug

`compat.h` defines `PATH_MAX` as 1024 if nothing has defined it yet, and
`struct path_context` embeds a `char path[PATH_MAX]`. On bionic some
translation units pull in `<limits.h>` (PATH_MAX 4096) before `compat.h` and
some do not, so `compat_get_next_path()` `strncpy()`s 4096 bytes into a
1024-byte field — smashing the caller's stack and nulling `ret_path`, which
crashes inside `snprintf` while looking for `fuse.font`.

Fixed without touching Fuse by forcing a consistent value:
`CPPFLAGS="-include limits.h"`. Worth reporting upstream.

