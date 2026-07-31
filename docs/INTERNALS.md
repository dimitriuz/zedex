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
  code that touches pixels, and the filters live in it — see *Filters* below.
- **`android_bridge.c`** is the Android boundary. Fuse's core is single
  threaded, so everything arriving from the UI thread is queued and replayed
  on the emulation thread from `ui_event()`. That queue, and the pause loop
  that keeps running beside it, is all this file is for now.
- **`android_window.c`** hands the drawing surface over. Android may take the
  window away at any moment and the emulation thread must have stopped using it
  before `surfaceDestroyed()` returns, so it is a handshake rather than a lock:
  the UI thread leaves a request and the emulation thread answers it at a frame
  boundary, the only moment the surface is not in use. Split out because it is
  a protocol with an invariant of its own, and because it is the one part of
  the bridge that has to keep working when everything else has stopped.
- **`android_state.c`** is what the UI thread is allowed to read: the machine
  list, which machine is running, what is in which drive. The emulation thread
  copies it into plain arrays once a frame behind a mutex. A different job from
  the queue — that one carries intentions in, this one carries facts out.
- **`keysyms.c`** maps Android keycodes to Fuse input keys, so physical keys
  and the on-screen keyboard share one path — including Caps Shift
  (`SHIFT_LEFT`) and Symbol Shift (`CTRL_LEFT`), which Fuse already maps. The
  same table decides what the activity is allowed to swallow: `onKeyDown`
  returning true consumes the event, and it used to do that for every key
  there was, so the phone's volume and media buttons did nothing while the app
  was in front — the event was taken on the way to Fuse, which then had no
  mapping for it and ignored it. `mapsKey()` walks `keysyms_map` rather than
  calling `keysyms_remap`, because the hash table behind that one is not built
  until Fuse has initialised and the question is asked before there is a
  machine — or when there is no ROM and there never will be one.
- **`aaudiosound.c`** writes to AAudio and *blocks*, deliberately: that is what
  paces the emulator. Audio is the clock, not vsync and not a wall timer — and
  where a device's bursts are too coarse for a blocking write to pace anything
  evenly, `pace_frame()` keeps it the clock without letting it clump the
  emulation. See *Stutter, and the three clocks*.
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

☰ is one icon in a **quick bar**, and the bar has a place of its own rather than
sitting over the picture: in portrait it is a strip across the top and the screen
starts underneath it — the strip being the icons alone, since a group's list is
over the picture rather than under it, and moving the machine down and up again
every time a menu is looked at is a picture that will not sit still — and in
landscape it hangs off the window's top right
corner, which is the black beside a 4:3 picture in a wide window. So it stays up.
It used to fade after three seconds because it *was* over the picture, and now it
only fades in fullscreen — a control that is always there needs no discovering,
and one that has its own strip is in nobody's way.

**Choices, questions and fields are pages, not dialogs.** A dialog is the
activity's window, so it always opens on the machine's screen — which with a
second screen means a question asked by a thumb on the panel gets answered over
there. Every single-choice list is now a page of `addChoice` rows with a tick
against the current one; every confirmation is a page with the question as a
note and one row that does the thing, Back being what Cancel was; and the pages
that need a name or a number carry `addField` lines of their own. The rows that
lead to them are submenus, so the sheet slides rather than closing and opening
again a frame later. Twenty-three of the twenty-nine dialogs went this
way. The save-state list went the other way and became a screen of its own —
`StatesActivity` — because a state is a picture and a picture wants more room
than a three-hundred-dp sheet has; it is opened through `openOwnScreen` like
settings, so it lands on the display the request came from, and the dialogs it
does use are its own windows and land there with it.

Two things make it fit that black. **Six icons, not nine**: everything that is a
file went into one group behind a folder, and pause went into *Machine* with the
reset. The group behind the picture icon is *Display* rather than *Controls*,
because what it holds is the furniture around the picture — the keyboard, the
joystick and the activity lamps — and the lamps are not a control at all. Each is
a thing worth putting away for one game and wanting back for the next, which is
why they are a tap from the bar and not only a trip to the settings; both write
the same preference, so the two never disagree.

The picture's own four are in there for the same reason: scanlines and CRT as
switches, and the video output and the border as steppers that go round their
three values. Steppers rather than choosers, because three values with the
current one written on the row need no dialog, and a dialog is what the settings
screen is for. They write the same preferences and call the same
`applyFilter()`/`applyScale()` the settings screen does. And **the icons are smaller
sideways**, sized from the room there is — the
black down the side of a 4:3 picture as wide as the window can make it, which is
the narrowest that black ever gets, since a template giving the screen less makes
the picture smaller and the black wider. On a 2400x1080 phone that comes out at
29dp a cell against 44dp in portrait, which is about the size of the activity
lamps: they sit in the same black and have always been compact.

A column was the first attempt and was worse. One icon wide fits any window, but
six cells are taller than the black *above* a keyboard across the bottom, so ☰
ended up sitting on the keys — and a bar reading downwards is a different thing to
learn from the same bar reading across.

The one landscape arrangement where it still overlaps the picture is a keyboard on
the left: there the screen's half reaches the window's right edge, so there is no
black at that corner at all. Reserving a strip instead would cost the picture
height, which is the scarce direction sideways.

The joystick keeps out of its way as it keeps out of the lamps' — but only where
it actually is. The side-bar branch used to give the whole height of the right
black to the bar, from when the bar *was* a column down it; a short row in the
corner needs none of that, and reserving it left fire a bar barely wider than
itself, so the three key buttons beside it shrank past thirty dp and vanished in
every landscape layout. The cap now applies only when the bar's box reaches down
as far as the controls' row, which the compact one never does. Fire moved
outboard to the window's edge as a result, which is where a thumb was reaching for
it anyway.

It is **gone entirely while the ROMs panel is showing**, ☰ and all. With no
machine there is nothing for any of it to act on: no state to save, nothing to
pause, no picture to photograph, no drives to look in. It kept ☰ for a while so
that the data folder stayed reachable, but the panel's own three options are the
doors out of it — download a set, import a folder, import files — and each of
them puts ROMs where they are wanted, so a bar of actions that cannot act was
worse than no bar. Nothing reveals it while the panel is up, since startup and
the sheet closing both ask as well as a tap on the picture.

**Pause lives in the Machine group** and its row is built when the group is
opened, so it says *Pause* or *Resume* without anything having to keep it up to
date — which is what the bar's own pause icon needed when it was a permanent
button, and one fewer thing to forget.

**Fullscreen** is a quick-bar action and a stored setting: the bar gives up its
strip, and in landscape the keyboard goes away. Only in landscape, because that
is the only place it buys anything — a 4:3 picture in a tall window is limited by
the width, so in portrait the keyboard costs the picture nothing and taking it
away would leave a black third of the window and nothing gained. Hiding it is not
enough on its own either: `arrange()` has to treat the window as the *no keyboard*
template as well, or the picture stays the size it was with a band of black where
the keys had been.

That rule depends on the orientation, which means turning the device changes the
answer — so `setFullscreen()` deliberately does *not* return early on an unchanged
value, and the activity calls it again from `onConfigurationChanged()`. Without
that, a device turned while fullscreen kept whichever answer was true when it was
switched on, and the keyboard stayed away in portrait.

Getting out is the same icon, reached the way it always was: a tap on the picture
brings the bar back for three seconds.

The fade's end action has to check whether the bar is still meant to be hidden.
Cancelling a `ViewPropertyAnimator` runs its end action anyway, so a reveal
arriving mid-fade was undone a moment after it happened: the tap registered,
and the button stayed invisible.

`QuickBar` holds the handful of things done often enough that three taps
through the sheet is a nuisance — a save state, a screenshot, the controls out
of the way. It is the one child `EmulatorLayout` does not size: how many icons
it has, and whether a group has opened its list, are its own business, so it is
measured `AT_MOST` and hung off the top right corner of the picture.

The bar is icons alone and the list a group opens is not. An icon in the bar is
a place you learn, and five of them are learnable; a list is read once, and its
choices are not guessable from a picture — a dot and a strip of film do not say
*GIF* and *MP4*, and nothing drawn says whether tapping the joystick will show
it or hide it. So the bar's icons are named only to accessibility, and the list
carries words on the screen. Being a list rather than a row, it has the width
for them. A group's own icon turns cyan while its list is open, so it is clear
which one the list belongs to, and anything at all — an action, another group,
the fade — puts the list away again.

☰ opens a sheet that slides in from the edge rather than a dialog. A dialog sat
in the middle of the window, which is where the machine is, and could only be a
flat list of equal-looking items; a sheet down one side leaves the picture
visible, has room for section headings, and closes by tapping the screen you can
still see. `MenuDrawer` is written out rather than taken from androidx, whose
`DrawerLayout` would be the app's first dependency for what amounts to a
translation, a fade and a list. The items are ordinary text views with the words
in them, which is what keeps the tests and `scripts/ui-tap.py` addressing the
menu by name.

The sheet has **pages**. Everything at one level came to a dozen rows, four
headings and three rules — taller than a landscape window, so the last of it
had to be scrolled to, and scrolling to find a menu item is the thing a menu
exists to avoid. What is at the top now is the one thing done constantly,
opening something, then the machine that is running, and then a handful of
doors: *States*, *Media*, *Capture*, *Controls*, *Settings*.

A page is a function, not a list, and it is called every time the page is
shown. That is what lets *Media* list the drives this machine has today,
*Capture* offer *Stop recording* only while something is recording, and
*Joystick* name the interface currently plugged in — none of which a menu built
once at startup could do. It also replaced three `AlertDialog`s that existed
only because the flat sheet had nowhere to put them.

What stayed a dialog is **choosing one of a set** — a machine, a joystick type,
a landscape layout — because a checked radio in a list is what says *one of
these, and this is the one*, and a sheet of plain rows cannot; and anything
that needs confirming. The line is: sheet pages navigate and act, dialogs
choose and confirm.

Back goes up one page and out of the sheet from the top of it, so the key means
the same thing at every depth.

Every row is a single `TextView`, and its icon and its chevron are that view's
own compound drawables rather than views beside it. A label nested inside a
clickable container would put the words on one accessibility node and the click
on another, and both the tests and `ui-tap.py` need them together; a glyph
pasted into the string would travel with the text a test matches on. The icons
are one white 24dp outline each, tinted where they are drawn, which is how the
chevron is quieter than the label without a second set of files.

Everything the menu does goes through the same queue as keys, because none of
it is safe to call from the UI thread: the item queues a command and the
emulation thread runs `machine_select()`, `machine_reset()` or
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

*Save as…* writes a new file in the data folder. Expect its bytes to differ
from the original even when nothing has changed: Fuse writes them out of its
own in-memory track representation rather than copying the file. What the
machine changed is still only what changed — a TR-DOS `SAVE` of one program
comes back as three sectors different out of 2560.

**Writing back over the file a disk came from** is the same call with three
problems in front of it.

The first is that Fuse opens files by path and a picked file is a document:
what is in the drive is a copy staged in the cache, and its name — the one
`fdd->disk.filename` reports and the menu shows — is all the two ways a disk
can arrive have in common. So the app keeps a map from that name to the
document it was staged from, filled in by `stage()`, which every path goes
through: picked into a drive, opened as media, or handed over by another app.
A row appears only when the staged copy is still in the cache as well, since a
disk Fuse made itself reports *Blank disk* and has no file behind it. The
picker asks for `FLAG_GRANT_WRITE_URI_PERMISSION` so there is somewhere to
write to; where the provider will not give it, the write fails and says so.

The second is that `disk_write()` opens its file `"wb"` — truncated — before
it knows whether it has anything to write. Writing straight into the document
would destroy an irreplaceable file to save an unformatted disk. So the disk
goes to a copy in `cache/writeback` under its own name, keeping the extension
and so the format, and only a copy that exists is streamed over the document.

The third is that the write is a command on the queue and nothing answers
back. The app waits for the file to appear and stop growing —
`WRITE_POLL_MS` apart, up to `WRITE_TIMEOUT_MS` — and treats a timeout as a
failure, which is what a removed file looks like. Fuse has already said why
through `ui_error` by then.

### The DivMMC, and three things Fuse cannot ask for

Fuse emulates the whole interface — `peripherals/ide/divmmc.c` with
libspectrum's MMC card behind it — and the app supplies the three things its
desktop UI does through dialogs and one it does not do at all. All of it is in
`native/ui/android/android_card.c`; the settings switch and the *Memory card*
rows in the ☰ *Media* page are the whole of the UI.

**The firmware.** A DivMMC is an 8K EPROM, 128K of RAM and a card slot, and
without firmware in the EPROM it does nothing — esxDOS is what makes it a
filesystem. Fuse has no setting for the EPROM's contents, because on real
hardware you flash it *from the Spectrum*: the firmware ships as a tape that
writes the EPROM through the interface. That works here too, but it is a
five-minute ritual to repeat on every phone, so `flash_eprom()` does what the
tape does — drop the write protect, set CONMEM to page the EPROM in at 0x0000,
write eight kilobytes with `writebyte_internal()`, page out, protect again.

The write protect **has to go back on**. `divxxx_refresh_page_state()` only
automaps while the EPROM is protected, so an unprotected DivMMC never pages
itself in and the firmware never runs. It comes off only to write.

The readback afterwards is not distrust of the memory write: port 0xe3 belongs
to the +D and the DivIDE as well, so if either were ever plugged in it would
answer first, the write would land in a ROM that is not writable, and the
machine would then automap eight kilobytes of 0xff — which is `RST 38h` eight
thousand times over, and a dead Spectrum. For the same reason the interface is
refused without firmware rather than plugged in blank: that hangs the machine
before the first frame is drawn, which looks exactly like the app being broken.

The firmware is not shipped — esxDOS is not ours to distribute — so *Settings,
Machine, DivMMC firmware* takes a file and keeps it as `roms/divmmc.bin`.
Deliberately not `.rom`: `Storage.haveRoms()` treats any `.rom` as proof the
emulator can start, and a folder holding only this would start it into a
machine with no ROM at all.

**Hard resets, and the error that took an afternoon.** esxDOS reads the card
once, while it starts, and keeps what it found — so a card inserted afterwards
needs a reset. That reset must be a *hard* one. `divxxx_reset()` keeps the
MAPRAM bit through a soft reset, so the machine comes back up out of the
DivMMC's RAM page 3, where esxDOS left a copy of itself and of the drive it had
then, instead of out of the EPROM. It boots, it prints its banner, and every
path is then invalid: `ESXDOS error #19, 0:1`, which reads exactly like a
broken card image and is not. Inserting a card hard resets, and so does the ☰
*Reset* row while the interface is in — there is one Reset in this app and it
has to be the one that works.

**HDF, and why a card image is copied.** libspectrum reads exactly one kind of
mass storage image: an HDF, a 128 byte header saying how big the drive is
followed by its sectors. A card image from anywhere else — a `.vhd` off a
MiSTer, an `.img` from `dd` — is those sectors and no header, and Fuse turns it
away as *not a valid HDF file*. `CardImage` writes the header round it: the
sectors are copied through unchanged, so the games stay exactly where the card
said they were.

It is a copy because the header goes at the *front* — adding it in place would
mean moving sixty-four megabytes up by 128 bytes — and because a card is
written to. Cards therefore live in `cards/` beside the tapes and the disks,
never in the cache: a card swept away with the cache is a card that lost its
saves. The copy goes through a `.part` file and is renamed over the target,
since picking the card that is already in the slot is an obvious thing to do and
writing straight to it would be reading and truncating the same file at once.

libspectrum wants a whole number of 1024-sector blocks — an SD card's capacity
is measured in half megabytes — and a real card image usually is not one: the
MiSTer image this was written against is a 64MB partition with a partition
table in front of it, one sector over. The last block is padded with zeros
rather than the geometry rounded down, because rounding down cuts the end off
the filesystem, and a sector the card claims to have but the file does not is a
read error waiting for whatever lands there.

**Writing back, without being asked.** libspectrum keeps written sectors in a
hash table and puts them on the card only when it is told to, so a card nobody
commits loses everything the machine wrote. A menu item alone would mean a save
survives only if the user remembers a menu item, on a device that gets put in a
pocket mid-game — so `androidcard_tick()` commits once a second from the pump,
`run_while_paused()` commits on the way into a pause, which is where the app
goes when Android takes it away, and inserting or ejecting commits first. An
empty commit walks an empty hash table and costs nothing; a commit that writes
is doing the I/O that had to happen anyway. *Write changes now* stays in the
menu for the moment you want to be certain.

Committing before an eject or a replacement is also what keeps Fuse from asking
about unsaved changes through a modal only Enter or Escape dismisses: there is
nothing left to ask about.

**The lamp.** The card is the one indicator that can honestly say which way the
data is going, and it comes from the same trick as the others — a peripheral
registered on port 0xeb that only watches writes, so it cannot affect what the
machine reads back. An MMC card is driven a byte at a time over SPI and the
direction is in the command: 17 reads a sector, 24 writes one. Blue is esxDOS
reading, amber is something on its way onto the card.

Verified on the emulator with esxDOS 0.8.9 and a 64MB MiSTer card image: the
firmware flashed, esxDOS booted, `.ls` listed the card, the NMI browser opened
it, and *Alien 8* loaded off it and ran.

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

The list is `StatesActivity`: a grid of cards, each the screen as it was, the
name, the date and the two buttons. The file arithmetic — what a state is, what
it is called, saving, loading, renaming, deleting, and decoding a thumbnail —
lives in `States`, because two screens need it: the list, and the emulator for
the controller's quick save and quick load.

Renaming moves both files, since the name is the base name of both and a
thumbnail left behind is a row with no picture. It keeps the snapshot's
extension — the format that wrote a state is the format that will load it,
whatever the setting says now — and refuses a name that already exists rather
than overwriting: tapping a row while saving is how a state is written over,
and that asks first. The pencil and the bin on each row do what only a long
press used to; the long press still works, and both reopen the list they came
from so several states can be tidied in one visit.

Both directions are queued like any other command and run between frames on
the emulation thread, which is what makes the state coherent —
`snapshot_write()` and `snapshot_read()` are the same calls Fuse's own menus
make.

Loading a state is not the only way in: a `.szx`, `.z80` or `.sna` opened
through **Open file…** takes the same path through Fuse.

### Settings

`SettingsActivity` is a plain framework `PreferenceFragment` over the same
`fuse` preferences file the emulator reads, so there is one store rather than
two.

It is **five tabs**, not one list. Twenty-eight preferences in a single scroll
was more than anybody could hold in their head, and the picture filters alone
were ten of it. The tab is now the grouping, so a category inside one only
survives where it still divides something: *Picture* keeps *Filters* and
*Display* apart, and the other four need no headings at all.

The strip is hand-built, like the ☰ sheet and the quick bar — tabs otherwise
mean `ViewPager2` and a `TabLayout`, which would be the app's first
dependencies for a row of buttons and a fragment swap. Icon over a word,
because an icon alone at a fifth of the width is a guess. One
`PreferenceFragment` class over five XML screens rather than five classes:
everything in it already asked `findPreference()` whether a setting was on this
screen before touching it, since it had to cope with one being absent anyway.

**A disabled preference is not dimmed by this theme at all.** Measured on API
36, the darkest pixel of the title is (48, 50, 59) whether the row is enabled or
not — so `android:dependency` was decorative, and a setting that could do
nothing looked exactly like one that could. `FadingListPreference` overrides
`onBindView` to put the whole row at 38% alpha when it is disabled: the whole
row, so the summary and the widget go with it, and alpha rather than a colour so
there is no opinion about what "dim" looks like on a dark theme. It has to be
set on every bind, because these views are recycled as the list scrolls and a
faded one would otherwise turn up under an enabled row. The machine list is not a fixed array: it is read back from Fuse through
`FuseNative.machineNames()`, the same snapshot the ☰ switcher uses.

Each setting is applied twice. At startup it goes on Fuse's command line —
Fuse generates `--x` / `--no-x` for every boolean setting and `--x n` for
every numeric one — so the options are in force before Fuse has finished
starting, which matters because a file arriving by intent can be loading
before the command queue is first drained. Changed later, it goes through the
queue like everything else.

Fast loading is three of Fuse's settings in the three combinations worth
having. `tape_traps` catches the ROM's loading routine and `fastload` makes a
trapped block appear at once, which between them cover every tape that loads
the standard way; `accelerate_loader` goes further and skips the timing loops
of custom loaders that never call the ROM at all, which is the part that can
occasionally defeat one.

| | `tape_traps` | `fastload` | `accelerate_loader` |
| --- | --- | --- | --- |
| **Off** | 0 | 0 | 0 |
| **Safe** | 1 | 1 | 0 |
| **Turbo** | 1 | 1 | 1 |

So *Safe* is what to fall back to when a tape will not load, and it is still
nothing like real time. These are the same three levels as Spectacol, the other
Fuse-based Android port, and they are its grouping rather than Fuse's — Fuse has
only the three booleans. The setting used to be one switch, which could only
ever be *Off* or *Turbo*; it migrates by which end it was at.

`detect_loader` is separate, and a fourth thing again: it watches the ULA for
the pattern of a loader polling it, starts the tape when it sees one, and stops
it when the loader stops asking. Fuse's acceleration does not depend on it —
they are two consecutive `if`s in `loader_detect_loader()`, and the second only
wants the tape to be playing.

Sound settings are only read when Fuse's sound subsystem starts, so changing
one calls `fuse_emulation_pause()` / `fuse_emulation_unpause()` to restart
it — which is what Fuse's own options dialogs do. That pair counts, and the app
now pauses itself whenever it is not in front, so a sound setting changed from
the settings screen restarts nothing at the time: the inner unpause only takes
the count from two to one. It applies on the way back to the machine, when the
outer pause lifts and `sound_unpause()` finally runs `sound_init()`. Which is
the right moment anyway — there is nothing to listen to until then.

**AY stereo separation** is Fuse's `stereo_ay`, and it is a *string* matched
with `strcmp` against `None`, `ACB` and `ABC` — anything else silently means
none. So those three words are what the preference stores, and `--separation`
takes them verbatim. ACB puts channel A left, C in the middle and B right; ABC
puts A left, B in the middle and C right. Either one makes `sound_channels` two
instead of one, which the AAudio driver asks the device for and falls back from
if it cannot have it. `settings_set_string()` does the assignment, because the
setting owns its copy and handing it a literal would leave Fuse freeing static
storage later.

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

In portrait the keyboard is inset from the window's left, right and bottom edges
by ten dp. The keys in the corners are the hardest to hit on a tall phone — the
bottom two are where the gesture bar and the curve of the glass are, and a thumb
reaching the outer columns arrives at an angle — and since the keyboard scales to
fit whatever box it is given, a few dp costs a fraction of the key size and gives
every key a border to miss into.

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
beside it. The joystick's two controls are children here too, placed as below.

### A second screen

A dual-screen handheld — the AYN Thor and its like — is the shape this app has
been folding itself into on one screen: a picture that wants a whole display,
and controls that want to be under a thumb. `SecondScreen` is a
`Presentation`, which is Android's own answer for a window on another display,
so the app stays one activity with one emulation thread and one surface.

**The views are lent, not copied.** `EmulatorLayout.setLentAway()` detaches the
keyboard, the lamps, the quick bar, the joystick and the ☰ sheet, and the
presentation adopts those same objects. A second set would be easy to build and wrong: they hold state a
copy would not — a latched shift, whichever group of the bar is open — and every
caller that already talks to them (the activity's fades, the ☰ toggles, the
lamps' polling) would then be talking to the wrong one. The `SurfaceView` is the
one thing that cannot go: detaching it destroys the surface Fuse draws into.

Lending is why the layout keeps its children in an explicit order. Front-to-back
is what `addView` order means here, so a view coming back has to land where it
was, which `attach()` works out by counting the ones still present. Everything
that measures or places a child asks `here()` first — is this mine, and is it
visible — since a view parented to another window must not be measured by this
one. `arrange()` treats a lent layout as the keyboardless template, which is
how the machine's screen ends up looking exactly like fullscreen.

Two rules change while the views are away. The keyboard and the lamps normally
disappear in fullscreen, because there the point is to give the picture back the
strip they were taking; on a panel of their own they are taking nothing, so only
"is this wanted at all" applies. And the bar never fades: fading is for a bar
sitting over the picture, and this one has a home.

The panel's own layout is deliberately dumb — the bar at the top, the joystick,
the keys, the lamps at the foot: what a hand does is near the hand and what it only reads is
out of the way. The keyboard gets a weighted box rather than its natural height,
since a panel is usually shorter than the keys are tall at that width and the
alternative is losing the bottom row off the edge; it scales into whatever box
it gets, and `setBottomAligned` puts it against the bottom of that box instead
of the middle, so the room left over collects above the keys rather than around
them. Draw and hit test share the one rectangle, so aligning it moves both. The
bar is told the panel's width, because a bar sized for a phone loses its last
icon off a narrow one, and the keys get the width edge to edge.

The joystick gets a band of its own: pad at one end, fire at the other, the
three key buttons stacked inside fire's end. The arc they make on the machine's
screen is a way of fitting them into black that happens to be there; a band is a
row, and a row has somewhere to put them.

The app's own screens — settings, about, the hotkeys — open on the panel, since
a screen asked for by a thumb on one display should not appear on the other.
Two things make that work. They are launched into a **new task**, because a task
lives on one display and launching into ours dragged the machine to the panel
and left the first screen empty. And the presentation steps aside while one is
up, because it is drawn above the activity windows on its display and would
otherwise hide what it just opened — through `ActivityLifecycleCallbacks` rather
than an activity result, which a new task cannot return.

The window goes in `onStop` and comes back in `onResume`, and a
`DisplayManager.DisplayListener` covers a panel appearing or being unplugged
while the app runs. The display it goes to is the last one attached that is not
the one the activity is on — Android launches an app where the last touch was,
so the activity can end up on the panel itself, and a presentation over the
machine's own screen would take away the picture rather than the furniture. Dismissing hands the views back first: a view left parented
to a window that has gone is a view its layout will never see again.

The ☰ sheet is lent as well, and for a plain reason: a button on one screen
whose answer appears on the other looks broken. It is not part of the panel's
stack — it slides in over everything, scrim and all — so the presentation puts
the column and the sheet in a frame together rather than in the column. What
cannot follow is anything that is a window of its own: the settings screen, the
file picker and every dialog are the activity's, and appear where the activity
is.

The Android keyboard skin is a case of its own. The one pixel an input method
types into is lent to the panel like the rest, so the IME is talking to the
panel's window and what it types reaches Fuse either way — but **which display
an IME is drawn on is not the app's to choose.** A secondary display's IME
policy defaults to *fall back to the default display*, and only the device can
set it otherwise (`setDisplayImePolicy` is a system call; the developer option
that forces desktop mode on external displays is the other way in). So on most
hardware the phone's keyboard appears over the machine while the panel shows the
bar and the lamps, and the ☰ keyboard page says so rather than leaving it a
mystery. The drawn skins have no such problem: they are views, and views go
where they are put.

### Three keyboards

A skin is a picture and a table of key rectangles in that picture's own pixels.
Nothing else differs: the presses, the latching, the accessibility nodes and the
scaling are the same code for both, which is what makes a second one cheap. The
rubber 48K is Fuse's own artwork, which the app already installs; the 128K is a
photograph of a real plate, cropped to the keys - the badge and the heatsink
beside them are inches of nothing to press.

**The grid was a calculation, not fifty-nine measurements.** The plate turns out
to have a uniform pitch of 111.8 pixels of the photograph across every row, so
each row is a first centre and a count; only the wide keys and the L of ENTER are
spelled out. The rectangles were then drawn back over the photograph and looked
at, which is the only way to know they are on the keys.

ENTER appears twice, because it is one key in two places: without a rectangle for
its upper arm, the touch expansion would give that arm to P.

**Most of the extra keys are not extra at all.** The 128K plate has keys the
machine does not - TRUE VIDEO, GRAPH, EXTEND MODE - but Fuse already maps a PC
keyboard onto a Spectrum one, so Escape *is* EDIT, Caps Lock *is* CAPS LOCK,
Backspace *is* DELETE, and the arrows and the punctuation come out as Fuse's own
shifted pairs. Only five keys need a modifier of their own, and all five are CAPS
SHIFT with something: TRUE VIDEO, INV VIDEO, GRAPH, EXTEND MODE and BREAK. The
modifier goes down first and comes up last, and it is *not* released if that shift
is latched - a latched CAPS SHIFT and then GRAPH would otherwise let go of the
latch on the way out.

Two things learned from the accessibility tree, both worth knowing before touching
this again:

- **A skin change is invisible to UI Automator.** The keys are virtual nodes, and
  the view says so with `notifySubtreeAccessibilityStateChanged()`, which is
  enough for a screen reader. It is not enough for UI Automator, which caches a
  window's tree and goes on reporting the *other* skin's keys until the app is
  relaunched. Nothing the app can say clears that cache; removing the view from
  the tree and putting it back made no difference either. So `scripts/ui-tap.py`
  and the tests see the old names after a live switch, and the app is not at
  fault.
- **A key called `"` cannot be found.** `uiautomator dump` writes the name into an
  XML attribute and that one node came back unusable - it simply vanished from the
  dump. It is called `QUOTE` instead, which is also what a screen reader would say.

`scripts/ui-type.py` carries both tables and reads the stored skin to choose,
because it taps by coordinate rather than by name: the 128K's keys are somewhere
else entirely and the rubber one's coordinates type nonsense on it.

**The scale follows the bitmap, not the size.** Where the artwork lands in the
view and how far it is scaled are worked out in `fit()`, which runs on a size
change *and on a skin change* - not the same event. Sideways the keyboard's box is
capped at a fraction of the window, so it is the same size for either skin and
nothing resized: the view went on holding the 48K's scale, stretched the 128K's
picture to the 48K's shape, and put every hit test off by the difference, with the
press highlight landing between the keys. Portrait hid it completely, because
there the box follows the skin's aspect and a switch really does resize it.

**The third keyboard is the phone's own.** It is in the same list because it is the
same choice, and it has no picture and no key table: `Skin.SYSTEM` draws nothing,
the layout treats the window as having no keyboard of ours in it, and Android's
input method comes up over the bottom of the picture.

Three things had to be got right.

- **An IME commits text; it does not press keys.** A soft keyboard hands over a
  string through `commitText()` and sends real key events only for a few editing
  keys. So the characters go through a path of their own, `FuseNative.character()`,
  and the key events take the ordinary one.
- **A character needs no translation.** Fuse's `input_key` values *are* ASCII for
  everything printable, and its own `keysyms_map` turns one into the Spectrum keys
  it takes — a colon is SYMBOL SHIFT and Z, and Fuse knows that. So
  `run_character()` skips `keysyms_remap()` and hands the character straight to
  `input_event()`, which is why the punctuation comes out right without a table
  here. The same press-and-release-in-one-pump rule applies as for keys and the
  joystick, since an IME commits both ends at once, so characters have their own
  slice of the press-tag namespace.
- **`showSoftInput()` does not work in this app.** It returned false however the
  focus was arranged, because the window fits none of its own system windows and
  drives the bars through a `WindowInsetsController`; the keyboard is one more
  inset to `show()`. And the request only lands once the window has focus, so it
  is made from `onWindowFocusChanged()` and again from `onResume()` - at startup it
  beat the window to it and was dropped.

There is nothing to see in the app: the input target is a one-pixel view in the
corner, because an input method needs something focused to talk to and what it
types shows up on the machine's screen.

Two more things follow from it being Android's keyboard and not ours.

**The picture gets out of its way.** While the keyboard is up it covers the bottom
of the window, so the screen box becomes the space above it and the picture sits
at the top of that rather than staying centred in a window whose lower half cannot
be seen. The inset arrives through `setOnApplyWindowInsetsListener`, which is also
the only way to know the height.

**The menu has to agree with it.** The keyboard can be dismissed from the
keyboard - its own key, a back gesture - and until that listener existed the app
went on offering to hide something already gone. The listener records what
happened and never asks for anything, so noticing cannot become requesting; and it
ignores "not visible" until the keyboard has been seen at least once, because the
insets arrive before it does and at startup the first thing it heard was that the
keyboard it had just asked for was not there - whereupon it closed it.

Worth knowing when testing on an emulator: an AVD reports a hardware keyboard, so
Gboard shows only its floating toolbar. `adb shell settings put secure
show_ime_with_hard_keyboard 1` brings the keys back.

A keyboard beside the screen gets the foot of its half rather than all of it.
It centres itself in whatever box it is given, so a full-height box put it in
the middle with as much empty above as below; the bottom is where a thumb is,
and it leaves the space in one place for the joystick to use.

### The on-screen joystick

It goes in the black, not on the picture. The renderer centres a 4:3 quad in
whatever box it is given, so there is nearly always spare black somewhere, and
that is a thumb's width of room the picture was never using. `placeJoystick()`
tries four things in order and which one applies falls out of the template
rather than being written down per template:

- **Beside the picture.** A 4:3 quad in a wide box leaves a bar down each
  side — 480px of a 2400px landscape window with no keyboard, and more with
  one below, since the shorter box makes the picture narrower still. This is
  what *no keyboard*, *keyboard below* and *keyboard over the screen* all get;
  in the last of those the bar is cut short at the top of the translucent
  keyboard.

  The lamps are in the left bar too, and what the pad takes is the width
  *outside* them rather than the height *below* them. Ducking under them was
  the first attempt and it cost the largest space on offer: the lamps are a
  narrow column that reaches most of the way down a landscape picture, so
  everything below them is a strip too short for a pad, and the joystick fell
  all the way through to floating over a window with 700px of black going
  spare. Only the left bar is narrowed by this, so the two are measured
  separately — fire would otherwise be pushed out towards the window's edge by
  however much the lamps took.
- **Below it.** Portrait gives the picture only the height a 4:3 image uses
  and puts the keyboard at the foot of the window, so what is left is one band
  between them — 1189px of a 2400px window, the largest space of the three. The
  controls go **against the keyboard**, not in the middle of that band: that is
  where a thumb rests, it is what the side bars already do, and centring them left
  them floating in the middle of nowhere when the band was tall.

  What counts as a keyboard below is "wider than half the window", not "exactly
  the whole of it". The portrait keyboard is inset from the edges so its corner
  keys can be hit, and the exact test read that inset as *no keyboard below* - so
  the joystick was placed against the bottom of the window, on top of the keys.
  That was the one visible symptom of a padding change three commits earlier.

  The floor is also whichever is lower of the keyboard, the system keyboard's
  inset and the strip the system keeps for its own gestures. Nothing of ours goes
  in that strip: a thumb that means *fire* and lands there sends the app to the
  background instead.
- **Above the keyboard.** The two side-by-side templates give the screen a box
  taller than 4:3 wants, so there are no side bars, and the band under the
  picture is thin. The keyboard is where the room is: it is one bitmap with a
  fixed 541x201 aspect, and half a landscape window is far wider than that is
  tall, so it is given the foot of its half rather than the middle of it and
  634px of a 1080px window is left empty above it. Both controls go there,
  centred in the band, since only one half of the window is ours — the pad at
  one end and fire at the other, and the far end cut back to the lamps when
  they hang into it, which with the keyboard on the left they do.

  Centring the keyboard in its half was the older arrangement and it split that
  634px into two bands of 317, one above and one below, neither of them where a
  thumb naturally is.
- **Over it.** Nothing left. The controls float in the picture's bottom corners
  at 55% alpha. Reachable only with a whole-pixel scale small enough to leave a
  short band and a narrow bar at once.

Both controls are the same class with a `Part`, because everything but the
drawing is shared. The pad is a stick rather than four buttons: the direction
is the angle of the finger about the centre snapped to eight, so a push into a
corner is a real diagonal and sliding around the pad steers without lifting
off. Snapping beats testing each axis against a threshold — every push outside
the dead zone is one of exactly eight answers, with no band where a hard push
registers nothing.

Presses go through the same queue as keys, and the queue's hold-a-release-over-
to-the-next-frame rule had to be generalised to cover them: Cursor and
Sinclair are *keys* inside Fuse, and the Spectrum reads a Kempston port no more
often than it scans the keyboard, so a tap that arrived and left within one
pump would be invisible either way. Keys and directions share one namespace of
tags so a direction cannot be mistaken for a keycode.

The backend calls `joystick_press( 0, button, pressed )` directly rather than
going through `input_event()`. For a joystick, `input_event()` also feeds the
widget UI's dialog navigation and turns fire button 2 into *open the menu*,
neither of which a five-control pad on a touchscreen wants.

Which Spectrum interface the pad appears as is `joystick_1_output`, and the
names in the menu are Fuse's own `joystick_name[]` — a plain table with no
state behind it, so it can be read before the emulation thread has started.
Kempston is the one type that is also a piece of hardware: without
`joy_kempston` the port is not decoded and a game reads a stick that is not
there. Fuse keeps the two apart because a real setup can have the interface
fitted and unused; here choosing the type is the whole of the user's intent, so
the interface follows it and `periph_posthook()` makes it take effect — which
is what Fuse's own options dialogs do. Kempston is also the default, because
Fuse's own default of *None* would leave the pad with nothing to do.

Hiding the joystick sets every control `GONE` for the same reason the keyboard
is: they are accessibility nodes, and a screen reader would otherwise still find
them, sitting on top of each other at nowhere. The three key buttons go with the
pad, since they are only where they are because fire is.

### Keys instead of a joystick

**Keyboard** is offered in the same list as Fuse's interfaces and is not one:
nothing is plugged into the machine and the pad presses keys. Plenty of games
predate the interfaces and simply read QAOP, and for those every real interface
does nothing while this works. Fuse is told *None*, and its stored value is 1000
rather than one past `joystick_name[]` so that a setting made today cannot come
to mean a real interface if that table ever grows.

Beside fire are **three key buttons**, which are keys whatever the type is: a
game that wants a joystick usually also wants Enter to start it, and reaching for
the keyboard for one key is why the keyboard has to be on screen at all. Enter,
Space and CAPS SHIFT by default — CAPS SHIFT because it is half of BREAK and half
of the cursor keys and games ask for it by name, where SYMBOL SHIFT is punctuation,
which is a thing you type rather than a thing you play with.

**The arc is turned up out of fire's way.** Level with fire, the lowest of the
three sat below and inboard of it, which is exactly where a thumb arrives when it
reaches for fire, and in a shallow band it was also the button nearest the bottom
of the window. A quarter turn puts the lowest level with fire and the highest
straight above it. That wants room above, which a narrow strip of black may not
have, so `placeKeyButtons()` takes as much of the turn as fits — a full step, half
a step, or the arc as it was — rather than dropping the buttons, which is what it
has to do when nothing fits at all.

What each control sends comes from a **profile**: eight keys, the pad's five in
Fuse's own `joystick_button` order so a button number indexes it directly, then
the three buttons. Named, switchable, and stored as a JSON array in the
preferences — `org.json` is in the framework, so it costs no dependency. Six are
built in: QAOPM, QAOP + Space, the cursor keys, both Sinclair sets and WASD. The
first five of a profile only mean anything for a Keyboard joystick; the last
three always do. One profile rather than two lists, because what a game wants is
a set, and a set is one thing to name and one thing to choose.

Keys are **Android keycodes**, which is what the whole app already speaks — the
on-screen keyboard reports them, `FuseNative.key()` takes them, and Fuse's keysym
table maps them, with `SHIFT_LEFT` for CAPS SHIFT and `CTRL_LEFT` for SYMBOL
SHIFT. Nothing above the JNI needs a Spectrum key table of its own, and a
physical gamepad's `KeyEvent` will drop into the same profile without a
translation step.

`Controls` is the one place a press is routed: joystick or key, for the pad, for
the buttons, and for a screen reader's click alike. It is static like
`FuseNative`, because the routing is a property of the settings rather than of
any one view, and three views would otherwise each need telling. That is also
what makes the planned gamepad support small — A to fire and the rest to the
three buttons, through the same two calls.

Every control draws the key it sends: the pad's four ways show them **instead of**
their arrows, since where the four sit is what says which way each one is, and
fire shows its key under the word. Only when the pad is sending keys, though — a
name that was there whatever the type would be a lie half the time.

**The editor is a screen, not a settings row.** Tap a control, tap a key: it puts
the same `SpectrumKeyboardView` the emulator uses into picker mode, where a tap
names a key rather than pressing one. Choosing SYMBOL SHIFT by pointing at
SYMBOL SHIFT wants the picture of the keyboard, and the picture wants room.
Bindings apply as they are made, like the rest of the app's settings, and what is
edited is always the current profile — the one the controls are using, so a
change can be felt straight away.

### A physical controller

There is nothing to set up and nothing to pair: `Gamepad` reads the events the
window already gets and pushes them at `Controls`, so a pad comes out as
whichever interface the joystick type says — or as the profile's keys when that
type is Keyboard, which makes a game that wants QAOP playable on a gamepad
without the gamepad knowing what a Q is. That is the whole benefit of having
routed the on-screen controls through one place first.

A is fire; B, X and Y are the three key buttons; **Start** is Enter — as itself,
not as whatever Button 1 holds, because a game that says PRESS ENTER wants Enter.

### The Kempston mouse

A Kempston mouse is two 8-bit counters and a button byte, and the counters are
**relative**: the program reads them, does its own arithmetic and keeps its own
pointer. Nothing ever tells the machine where a pointer is, which is the only
reason a touchscreen can drive one at all — a drag *is* a delta, and there is no
absolute position to disagree about.

`kempmouse_update( dx, dy, button, down )` is the whole interface, and
`settings_current.kempston_mouse` plugs it in through `periph_update()`. Three
things are worth writing down:

- **Fuse's own `ui_mouse_*` path is bypassed.** It gates everything on
  `ui_mouse_grabbed`, which belongs to a desktop with a cursor to capture and a
  middle button to toggle the capture with. The bridge calls `kempmouse_update()`
  directly and lets the mode be the grab.
- **The signs and the bits are Fuse's.** `kempmouse` does `pos.y -= dy`, so what
  goes in is movement in screen terms — down is positive — and the buttons are
  active low with **the left button on bit 1 and the right on bit 0**, which is
  what `ui_mouse_button()` does with the default `mouse_swap_buttons`. Measured
  from BASIC: `IN 64223` reads 255 with nothing pressed, 253 with the left button
  and 254 with the right.
- **It is only plugged in while the mode is on.** The interface answers 0xfadf,
  0xfbdf and 0xffdf, and a game that reads those for something else would get the
  mouse's answer instead. `periph_update()` is told either way; the hard reset it
  offers to do is declined, since plugging a mouse in is not worth losing the
  program in memory. `periph_activate_type()` registers the ports there and then,
  so nothing has to be reset for the mouse to start answering.
- **A snapshot load unplugs it, and that was a real bug.** `snapshot.c` calls
  `periph_disable_optional()` before every load, which zeroes *every* optional
  peripheral's setting; each module then puts itself back only if the snapshot
  recorded it as active. An `.sna` cannot record a Kempston mouse at all and most
  `.szx` files do not, so loading a save state took the mouse away — while the lamp
  went on lighting, because the port monitor is attached either way. A game read
  the ports, got the floating bus, and its pointer sat still. The bridge keeps
  `mouse_wanted` and calls `restore_mouse()` after a snapshot load and after
  opening a file, which is where that clearing happens.

  Reported from SimCity 128 and found by logging what the ports were doing: reads
  of the buttons 150 times a second and of x and y 22 times each, with
  `settings_current.kempston_mouse` at 0. Everything about the emulation was
  right; the interface simply was not there.

`Mouse` holds the mode, and everything arrives there: a drag from the
`SurfaceView`'s touch listener, the pad and the D-pad through `Controls` — which
is why that class exists — and a physical stick from `Gamepad.motion()`, where the
analogue value is used rather than the four directions, since how far the stick is
pushed is how fast the pointer should go. A held direction runs a 60Hz nudge, and
the fractions left over by the scaling are kept: a drag scaled down to mouse units
is mostly fractions, and throwing them away means a slow drag moves nothing at all.

**A drag, not a tap.** A tap on the picture already reveals the quick bar, so the
touch listener only takes over once the finger has moved past ten dp — under that
it returns false and the click listener gets it as before.

The **mouse lamp** lights when the machine reads those three ports, whether or not
the mouse is plugged in, which makes it the answer to "does this game use the
mouse?" — and its amber says which. Blue is a mouse being read; **amber is the
machine asking for a mouse that is not plugged in**, which is the one thing the
lamp used to hide. On the other lamps amber means writing; here there is nothing
to write to a mouse, and both meanings are "look at this". The first time it
happens the app also says so in words, once per run, because a lit lamp and a
cursor that will not move is a puzzle rather than a hint. It also lights the joystick lamp, because the mouse ports have bit five
clear and that is what the joystick watcher's deliberately loose decode catches.
Both are true — something is reaching for a Kempston — and tightening the joystick
decode would cost more than the overlap does. Adding a sixth lamp also moved
`ACTIVITY_WRITING` from 5 to 8: the low bits are the lamps and the shifted ones
their write flags, and a sixth lamp would have landed on the tape's write bit.

Measured end to end on the API 36 emulator with `10 PRINT AT 0,0;IN 64479: GO TO
10` running on a 48K: a 550 pixel drag right moved the x counter 171 to 110, which
is +195 through 256 at 0.35 units a pixel, and holding the pad's right took it 110
to 22 while it was held.

### Hotkeys, and why they need a modifier

Everything the app wants of a controller for *itself* is behind one button. The
five app actions used to be hard-wired to Select, Start, L1, R1 and R2, which
worked and could not be changed, and could not grow either: **a pad has no spare
buttons.** Four faces are fire and the key profile, the stick and hat steer, and
anything the app takes is taken from the game.

So `Hotkeys` does what RetroArch does. One button is the **hotkey** — Select
unless it is changed — and every action is that button *and* another. A button
means one thing while the hotkey is down and another while it is not, nothing is
taken from the game, and there is never a question which was meant. The hotkey may
also be set to **none**, and then the bindings fire on their own, for a pad with
buttons to spare.

The defaults put the two that want no dialog on the shoulders — quick load on L1,
quick save on R1 — and leave the state *dialogs* unbound, since a list to read is
not something to reach for mid-game. The faces are free while the hotkey is down,
so B pauses, X is fullscreen and Y hides the keyboard, and R2 held runs the machine
fast. The two stick clicks take the "which set of controls is this" settings — L3
the joystick type, R3 the key profile — since those are what a game disagrees with,
and a click of the stick already under your thumb is the easiest thing on a pad to
find without looking. Start quits, as RetroArch has it.

The rules `Gamepad.hotkey()` follows, all of which are the tricky part rather than
the obvious part:

- **The hotkey is swallowed whether or not anything follows it.** A modifier that
  also did something of its own would do it every time anyone reached past it.
- **The hotkey has to be down first**, as on a keyboard. Whether it is down is
  asked at the moment the other button goes down, not afterwards.
- **A held action ends on whichever comes up first.** Fast forward is the only one,
  and letting go of the hotkey is the ordinary way out of a chord — miss that and
  the machine is left running at 500%. `holding` remembers what is running so it
  is ended exactly once, however the two buttons are released.
- **Unarmed, a bound button is an ordinary button again**, so R1 without the
  hotkey still reaches the machine if the machine has a use for it.
- **A trigger is a button on some pads and an axis on others**, so the axes are
  turned back into `BUTTON_L2`/`BUTTON_R2` and pushed through the same path — and
  only on a change, since motion events arrive in a stream and a one-shot action
  would otherwise fire with every one of them.

**Bindings are captured, not chosen.** `GamepadActivity` lists the *actions* —
they are a fixed two dozen, while pads are not — and each row waits for a press.
Pads disagree about what their own buttons are called, and some report Select as
`KEYCODE_BACK`, so the only reliable question is "what did it just send?". The
capture reads `dispatchKeyEvent` rather than `onKeyDown` because a dialog is up
and Start and B are both ways of dismissing one. `onKeyDown` in the emulator gives
the pad the event before Back is looked at, so a pad that sends Back for Select
works as a hotkey if that is what it is bound to.

Every action is something a menu already does, called the same way the menu calls
it. That is the test for being on the list: a hotkey is a shortcut to something
that exists, so nothing here is reachable only from a controller and there is no
second implementation to keep in step. Reset is the one that does *not* ask first
— a dialog is the one thing a pad in a stand across the room cannot dismiss, and a
chord behind a modifier is deliberate enough.

The chord arithmetic is the one part of this port with a test of its own that is
not a UI Automator test: `HotkeyTest` feeds synthetic gamepad `KeyEvent`s straight
into `Gamepad`, because no build machine's emulator has a controller plugged into
it. It binds L1 and R2 and nothing else, since a bound face button or Start falls
through to Fuse, which is not running in a test that never launched the activity.

Three details that are easy to get wrong:

- **Source, not keycode.** A keyboard's arrow keys are `DPAD_*` too, and the
  machine has its own use for those, so every event is checked for
  `SOURCE_GAMEPAD` or `SOURCE_JOYSTICK` before it is looked at. The test
  emulator's `qwerty2` device reports `KEYBOARD | DPAD`, which is exactly the
  case that must not be caught — and is not.
- **A hat arrives twice.** Many pads report it as both `AXIS_HAT_X/Y` and as
  D-pad key events. The two paths are tracked separately and combined, because a
  release down one would otherwise cancel a press that came down the other and
  the stick would stick. Releases are still sent before presses, for the same
  reason `JoystickView.steer()` does it.
- **Nothing else will let go.** A pad unplugged mid-press, or an app sent to the
  background, leaves the machine holding a direction, so `releaseAll()` runs from
  `onPause()` and from `onInputDeviceRemoved()`.

`InputManager.InputDeviceListener` is how a pad appearing is noticed; Android has
no broadcast for it, and the device list is the only answer to "is there one".
That is also what drives *Hide for a controller*, which is a separate flag in the
layout rather than a write to the user's own *Show on screen* — unplugging has to
bring back whatever they had chosen, and it cannot if plugging in threw it away.

### Speed, and what the clock really was

**Fast forward** is a quick-bar button held down and R2 on a controller: 500%
while it is held, and back to the *Speed* setting when it is let go. The setting
is not written to — this is a thing being done, not a preference being changed,
and a loading screen skipped at speed should not leave the machine fast for the
game afterwards. It is guarded against being told the same thing twice, since it
arrives from a finger, from a trigger button and from that trigger's axis, and on
some pads from two of those at once.

Making it work turned up **two reasons the speed had never worked at all**, the
*Speed* setting included.

The first is that `settings_current.emulation_speed` is not read where it is set.
With sound enabled — and here the sound is the clock — the speed reaches the
machine through `sound_get_effective_processor_speed()`, which scales the blip
buffer's clock rate, and that is read once, in `sound_init()`. Setting the number
alone changed nothing: the emulation went on running at whatever rate the audio
device was draining samples. So the option restarts the sound now, the same way
the volume and stereo options do. Past 300% Fuse switches the sound off itself —
its own range for having any is 50 to 300 — and then `timer.c` does the throttling
and reads the speed live. Which is why fast forward is silent, and should be.

The second is worse, because it had quietly taken the clock away from the sound:
`eglSwapBuffers()` waits for the display's next refresh, so presenting every
emulated frame tied the emulation to the panel. At sixty hertz the machine could
not exceed about 120% however fast it was asked to run — 500% measured 105%. Above
real time the frames the panel cannot show are dropped instead, in
`androidbridge_present()`: a screen refreshing sixty times a second cannot show
two hundred and fifty frames, and the emulation gets the time back. At normal
speed every frame is presented exactly as before, so the pacing there is
untouched.

Measured on an API 36 emulator by counting emulated frames: 50 a second at 100%,
100 at 200%, and 247 while fast forward was held — 494% of a real Spectrum, the
AVD not quite managing the last one per cent — and 48 again the moment it was let
go.

### The border, and the three places that count pixels

The machine draws 256x192 pixels inside a border 32 across and 24 deep, which is
why Fuse's frame is 320x240 and why a tenth of every side of it is usually one
flat colour. *Border* in the picture settings shows all of it, a quarter of it, or
none.

**A quarter, where a fifth was asked for.** The border has to come off in whole
pixels, because a source frame of fractional ones makes a nonsense of asking for a
whole number of device pixels per emulated one — and it has to stay 4:3, or the
picture is subtly the wrong shape. Both hold when the same fraction comes off
each axis and the result is integral on both: a quarter is 8 of 32 across and 6 of 24
down. A fifth would be 6.4 and 4.8. So the three sizes are 320x240, 272x204 and
256x192, all exactly 4:3.

The crop is a **fraction of the frame** rather than a count of pixels, which is
what keeps it right for the Timex hi-res modes: there the frame is drawn at twice
the size, and a tenth of 640 is 64 where a tenth of 320 is 32. Both come out
whole.

In the shader everything works in `v_uv`, the *visible* picture from 0 to 1, and
`crop()` is the last thing before a sample — `uv * u_crop + u_crop_at`. That is
what lets the effects stay as they were: `u_source` becomes the visible frame, so
a scanline is still one emulated row and the sharpening still snaps to a texel
(the map is uniform, so a visible pixel and a texel are the same size). Every
sample goes through it, including the blur taps and the composite's seven.

Three places count emulated pixels and all three have to agree, or the joystick
ends up over the picture and a whole-pixel scale stops being whole:

- `place()` in the renderer, which is handed the *visible* size;
- `EmulatorLayout`, which mirrors the renderer's rule to know where the picture
  lands — the aspect is unchanged, so only its whole-pixel arithmetic moves;
- the scale list in the settings, whose entries are the visible size times the
  scale, and whose largest offer is what the display has room for. Changing the
  border rebuilds it: a slim border reads 1x - 272 x 204.

`Border` is the one enum all of them read.

**Captures are not cropped.** Screenshots, recordings and save-state thumbnails
are built from the palette-index buffer rather than from the shader, so they are
the whole 320x240 frame whatever the setting says. That is arguably the honest
capture and is certainly the simpler one; if it should follow the setting, the
crop has to be repeated in `Recorder` and in the thumbnail writer.

### Stutter, and the three clocks

Dropping the frames a fast machine could not show fixed the speed, and left the
swap still *waiting* at normal speed — which was its own fault, reported as the
picture freezing for a few frames every second or so while the sound carried on
without a break. Sound carrying on is the clue: the sound cannot break, because
it is buffered and because it is the clock. Only the picture can.

There were two causes, and it took instrumenting the emulation thread to tell
them apart — a second's worth of totals, printed once a second: frames, time
inside the swap, time inside the audio write, and a histogram of the gaps
between one frame being finished and the next.

**Waiting for the panel.** The first report said the emulation thread spent
**690 to 860 milliseconds of every second inside `eglSwapBuffers()`**, with a
65ms gap once a second and five to twelve gaps over 25ms. The default swap
interval is one, so the swap waits for the next refresh — and this is the
emulation thread, which owns the EGL context, so *the emulator* was waiting.
A Spectrum frame is 19.97ms and a sixty hertz refresh is 16.67ms; neither
divides the other, so the sound's schedule and the panel's ground against each
other, and the sound's slack was spent waiting for a refresh. `attach()` now
asks for `eglSwapInterval( display, 0 )`: the buffer is queued as it is drawn
and SurfaceFlinger shows the newest one at each refresh, which is what a
compositor does anyway — there is nothing to tear. Time in the swap fell to
about 150ms a second.

**Waiting for the audio device.** That alone did not fix the gaps, and the
histogram said why: 34 frames a second arrived less than 10ms apart and 15
arrived 45 to 65ms apart, with almost nothing in between. The machine was
running in clumps. `AAudioStream_getFramesPerBurst()` on that AVD is **2006
sample frames — 45ms**, and a blocking write only returns when the device has
swallowed a whole burst, so the emulation ran the two or three frames that fit
and then waited. Turning the sound off, which hands pacing to `timer.c` and its
ten millisecond wall clock, made the gaps even again — which is the proof that
the granularity was the audio device's and not ours.

So `pace_frame()` in `aaudiosound.c` takes over **only where a burst is longer
than half a Spectrum frame** — an emulator's audio device, or Bluetooth, where
bursts are large; a phone asked for low latency answers with two to five
milliseconds and keeps the blocking write it always had. Where it does take
over, the frame is held to a wall-clock deadline and the queue absorbs the
lumps. The deadline needs no notion of the emulation speed: Fuse hands over one
frame's worth of samples per frame, so how long the frame should take is how
long its own samples take to play, and at 200% it hands over half as many. The
wall clock and the audio device's clock are still not the same clock, so the
queue's depth trims each deadline by an eighth of its error — draining, run
sooner; filling, run later — which keeps the audio device the clock over any
length of time while the emulation advances a frame at a time.

Measured after, over forty seconds on the same AVD: every gap in the 18–23ms
bucket, worst 22ms, **no gaps over 25ms at all**, the queue holding between 2028
and 2967 sample frames against a 2006 target, and `getXRunCount()` still at the
two it reported from starting up. Smooth picture, and the sound no closer to
running dry than before.

One thing this does not fix, because nothing can: 50.08 frames a second on a
sixty hertz panel means a tenth of the refreshes must show the frame before them
again. `ANativeWindow_setFrameRate()` tells Android the content's real rate —
asked of `machine_current->timings`, since a Pentagon does not agree with a 48K
— with `FIXED_SOURCE`, which says it is the material's rate and not a target, so
a phone with more than one refresh rate may pick one that suits. It needs
`-lnativewindow`: `libandroid.so` re-exports the older `ANativeWindow_`
functions but not that one.

### Quitting

Back and Home leave the app paused in the background, which is right for an
emulator and is not a way out of it, so ☰ has a *Quit* that means it. It ends the
**process** rather than the activity: the emulation thread is a plain pthread
inside Fuse's main loop, Fuse's globals cannot be initialised twice, and the next
launch has to be able to start it again — the same constraint that makes the ROMs
panel restart rather than retry. `finishAndRemoveTask()` then
`Runtime.getRuntime().exit(0)`, which is what `restartForRoms()` already does.

Two things get a moment on the way out. A **recording** is still being written
when `Recorder.stop()` returns — it cannot block, being called from the UI thread
— so `waitForFile()` joins the encoder for up to a second, or the film ends
mid-frame. And a **disk with changes** nothing has written back is worth asking
about, since Fuse's modified flag is already there in `driveDetails()` and the
disk is work the machine cannot get back for you. Nothing else is saved: the
machine's state is volatile and always has been, which is what save states are
for.

### Pausing

Fuse has no pause of its own to borrow. `fuse_emulation_pause()` sounds like
one and is not: it stops the sound and the RZX recording and returns, leaving
the Z80 running — it is a bracket for a UI operation. What actually stops a
Fuse UI is that UI's own nested main loop, and each of them writes its own
`menu_machine_pause`. Nothing defines one here, so this port writes the pause
rather than inherits it.

The obvious implementation deadlocks. The emulation thread is the only one that
ever calls `androidbridge_present()`, and that is where the window handover
happens — `surfaceDestroyed()` blocks until it does. Blocking the thread in
`ui_event()` would therefore hang the app the moment Android took the window
away, which is *precisely* when the app pauses itself. So the paused loop hands
the last frame over again and again instead: a texture upload every sixteen
milliseconds, the handover kept alive, and the paused picture redrawn after a
rotation for free. `androiddisplay_last_frame()` exists for that.

`fuse_emulation_pause()` is still used, for what it does do — stopping the
sound, which in this port is also the clock, so the thread would otherwise sit
in a blocking AAudio write. It counts, so the pairing matters.

**The paused loop runs at two speeds**, because the reason for its pace goes away
with the window. While there is a surface, somebody is looking at a paused
picture: a frame's pace keeps a rotation instant and answers
`surfaceDestroyed()` long before its one-second timeout. While there is none —
the device asleep, the app behind something — there is nothing to redraw and
nobody to hand the surface back to, so waking sixty times a second only keeps the
CPU out of its deep idle. It waits a quarter of a second instead, which is still
sooner than coming back can be seen to take. `androidbridge_has_window()` is the
question.

Measured on an API 36 emulator, in CPU ticks of a hundred a second: running, 52
in five seconds; paused with the window there, 99 in six; **asleep, zero in
eight**, with the audio HAL in standby and no wake lock held. The screen going
off already stopped the machine — `onPause` sets `pausedByAndroid` — so what this
saves is the wakeups rather than the work, which is exactly what a sleeping phone
is counting.

The flag is `volatile` rather than a queued command, because the emulation
thread has to see it while it is *in* the paused loop and the queue is only
read between frames.

Two reasons to be stopped are kept apart. The user's pause survives going away
and coming back; Android's does not, and lifting it must not undo one the user
asked for. The lamps are cleared on the way in, since a paused machine is doing
nothing and they would otherwise freeze at whatever they last showed — which
reads as a tape still running.

Measured: 48 CPU ticks in two seconds running, two in three seconds paused in
the background.

### How big the picture is

Fitting the 4:3 frame into whatever box the screen gets is the default and was
for a long time the only option. A whole-pixel scale is the alternative, one
setting per orientation because a phone has room for very different numbers each
way up: 3x portrait and 4x landscape on a 1080x2400 panel.

The point of asking for one is that every emulated pixel becomes the same number
of real ones, and that only holds if the quad is a whole number of pixels wide
*and* starts on one. Centring can leave half a pixel over — a window is rarely
an exact multiple of anything — and half a pixel with `GL_NEAREST` is a column
of doubled pixels down one edge. So `place()` in `android_gl.c` floors a pixel
position and derives the clip-space offset from it, rather than centring the
quad and hoping. That is what `u_offset` is for; fitting leaves it at zero.

A scale too big for the box is reduced until it fits, and a box too small for
even 1x is fitted instead. That is what lets the settings list be built from the
*display* rather than from the box the picture will actually get — which depends
on the landscape template and on whether the keyboard is up, and would mean a
second copy of `EmulatorLayout`'s sums that could only ever drift.

**Which orientation applies is Java's to answer.** The renderer cannot: in
portrait with the keyboard below, the box it draws into is 1080x810 — wider than
it is tall. So it is told one number, and `EmulatorActivity` pushes it again from
`onConfigurationChanged()`.

`EmulatorLayout` applies the same rule a second time, in Java, to place the
lamps, the joystick, the play button and the quick bar against the picture's real
edge instead of where a fitted one would have been. Two copies of one rule, kept
in step by hand, because the renderer runs on the emulation thread and that is a
layout pass. They agree on everything except a Timex in hi-res, which doubles the
emulated frame: the renderer's 1x is then twice what the layout worked out, and
the controls sit a little further out than they need to.

### Filters

Scanlines and a CRT, as **two switches rather than one choice**, because they
are two different things and a tube has both: scanlines are the beam, and the
curve, the shadow mask and the glow are the glass in front of it. Either can be
had without the other, and both together is what a television looked like.

One fragment shader, not one per filter. The effects branch on uniforms, which
are constant across a draw and so cost a predictable nothing, and a program each
would share nine tenths of their code.

The values reach it as **one struct rather than a dozen arguments**, which is
what `androidgl_set_filter()` took by the time there were three displays and nine
dials: they arrive from the settings one at a time, go to the renderer together,
and a positional list of ints that all mean something different is a mistake
waiting to be made.

Everything is in units that mean something. `u_source` is the emulated frame in
its own pixels, so a scanline is one emulated row and stays one however far the
picture is scaled; the mask is in *output* pixels, because a shadow mask is a
property of the glass and not of the signal. Scanlines are a sine rather than a
stripe, since a beam is not a step function. Both scanlines and the mask take
light away, so the shader gives back roughly what they cost — a filter that only
made the picture dimmer would be a poor trade.

The sampler stays `GL_NEAREST` unless something wants otherwise. *Sharpness* is
sampling pulled towards the middle of each source pixel, so at 100% it lands on
the middle — which is that pixel and nothing else — and easing it off lets the
coordinate drift back out towards the boundary, so only the boundary softens
instead of the whole picture blurring. At 100% the coordinate is already a texel
centre, so the sampler is switched to nearest as well and the default look is
exactly what it was.

**Towards the middle, and never past it.** The first version of this multiplied
the offset instead of shrinking it and clamped the result to ±½ a texel, which is
the texel *edge* — and the edge is where the next pixel begins. Every device
column past the middle of a source pixel therefore read the pixel after it: with
a 320x240 frame in a 1080 wide window that was two columns in five wrong, one
pixel wide strokes broke up, and the *128* in the 128K startup menu read as a 2
with no upright. It looked like a scaling fault, which is what it was reported
as, and it was in the sampler all along — the picture had been drawn at the same
3.375x since long before there was a scale setting.

**The signal, before the glass.** A Spectrum reached its television through a
modulator, and what came out was not what went in. Luma survived; chroma did
not — it rode a subcarrier with a fraction of the bandwidth, so colour smeared
sideways across several pixels while the edges stayed put. That is the whole of
the composite look, and it is why magenta text on a 48K bled. It is done in YIQ
because that is what the encoding used: convert, blur I and Q along the line
with seven weighted taps, keep Y from the middle tap, convert back.

RF adds what an analogue tuner contributed of its own: snow, which is hashed
from the output pixel and a frame counter so that it moves — snow that does not
move is not snow — and a faint horizontal ripple, which is the subcarrier
beating against the luma and is what dot crawl looks like when you are not
staring at it.

The order matters. The signal is applied to the sampled frame and the glass acts
on the result, because that is the order it happened in: the tube displayed
whatever arrived. A signal also needs interpolation to smear across, so choosing
anything but RGB softens the sampler regardless of *Sharpness*.

**A dot matrix** is the third display, and the only one whose grid is in
*emulated* pixels: a dot is a picture element, so it stays one emulated pixel
however far the picture is scaled, while the shadow mask above is a property of
the glass and stays in output pixels. `fract( uv * u_source )` is the position
inside the dot, and the gap is a `smoothstep` towards its edge — `max()` of the
two axes rather than `length()`, because the cells of an LCD are square and
rounding them looks like a printed halftone. A whole-pixel scale is what makes
them exactly square; fitted, a cell is three device pixels one way and four the
other.

Two things it does that a plain grid would not. The gap **shows on dark and
nearly vanishes on white**, which is what the Game Boy Color shader this follows
does, and what keeps it usable here: most of a Spectrum screen is bright paper,
and a grid drawn evenly over all of it is a grid with a program somewhere behind
it. And the **backlight comes through the dots rather than the gaps**, which is
the only way a black area shows any dots at all — taking light away cannot do it,
since black times anything is still black. That is also what a panel does: it is
lit from behind, so its black is a dark grey with a darker grid over it.

`luigira/GBC-dot-matrix-shader`, which prompted this, is GPL-3 and three Vulkan
passes; the same reasoning as below applies, so this is its idea rather than its
code.

**Why these and not RetroArch's.** `.slang` shaders are Vulkan GLSL: running one
means glslang to SPIR-V and SPIRV-Cross back to GLSL ES, two large C++
libraries in an app that has none, and the format is a multi-pass pipeline with
framebuffers, history and feedback textures rather than a fragment shader.
`libretro/glsl-shaders` is the same collection hand-converted for the GL and
GLES path and is GPL-2-or-later, so those *could* be adopted — but only along
with RetroArch's uniform names, its `#pragma parameter` directive and
multi-pass render targets, because every CRT shader worth having is multi-pass.
That is a feature in itself. The parameters here are named and bounded the way
theirs are, so that day is not made harder.

### The activity lamps

Five indicators — tape, disk, AY, keyboard, joystick — because a Spectrum gives
nothing away. A tape that is not running looks like one that is, a game that
has stopped reading the keyboard looks like one that never did, and a game
waiting for a Kempston when Cursor is selected looks simply broken. Each of
those is state the emulator already has, and each is the difference between *it
does not work* and *it wants something else*.

Getting at it takes four different routes, none of which touches `vendor/`:

- **The tape and the disks** come through `ui_statusbar_update()`, which is how
  Fuse tells a UI to light its status bar. `ui/widget/widget.c` has a stub that
  returns 0 and throws the news away, so the build weakens that symbol and ours
  wins the link — the same trick as `ui_error_specific`.
- **The tape needs more than that**, because Fuse only announces a tape that is
  *playing* and loading fast never plays one: the trap hands the block over
  whole. So `tape_get_current_block()` is watched as well, and the lamp lights
  when the tape moves however it moved. A whole small tape can be trapped
  through in three frames, so that reading is held for half a second — a lamp
  that is technically right and never seen is no use.
- **The AY** announces nothing at all, so `machine_current->ay.registers` is
  read at the end of every frame.
- **A port read is reported by nothing, and there is no hook for one.** What
  there is, in `readport_internal()`, is a walk over *every* peripheral whose
  mask matches, ANDing what each returns. So a peripheral that returns `0xff`
  and leaves `attached` alone can watch a port without changing what the machine
  reads from it, and `periph_register()` only ever uses the type as a hash key —
  a value past the end of Fuse's enum is a type of our own. The monitor watches
  every even port for the ULA, which is how a keyboard is scanned, and the
  loosely-decoded joystick ports. Every machine's reset calls `periph_clear()`,
  which empties the port list but keeps the registrations, so the monitor is
  registered once and put back on the list whenever it finds itself off it.

Colour says direction, and only where the emulator knows one. A keyboard is
only ever read; what the AY does is sound on its way out, so it is always the
writing colour. The tape can say, because the rise of `tape_modified` is the
moment its save trap appended a block — inside the save rather than merely
after it — and that moment is held long enough to see.

**A disk will not say, and three attempts at making it say were all worse than
not.** The only trace a write leaves on a drive is `disk.dirty`, and that is a
latch: set on the first written byte, cleared only when the app saves the disk
out. Watching it rise flashed for a fifth of a second at the start of a two
minute format and then sat in the reading colour for the rest of it — the exact
opposite of what was happening. Watching its level turned the lamp amber and
left it there for the session, because a disk made by *New disk* is dirty before
the machine has touched it. The controllers do know, and `wd_fdc` even has a
`WRITETRACK` state which is precisely what a format is, but every instance of
one is `static` inside its own interface's file and `ui_media_drive_info_t`
hands out the drive rather than the controller. So the disk lamp reports access
and nothing else.

It does not take that from the status bar either. Fuse counts turning motors in
a global, and `fdd_unload()` clears a drive's `loaded` *before* calling
`fdd_motoron( d, 0 )`, which returns early for a drive with nothing in it and so
never decrements the count — eject a spinning disk once and the lamp stays on
for the rest of the session. What the lamp watches instead is `disk.i`, the
head's position along the current track, which only advances inside
`fdd_read_write_data()` and so cannot be moved by a stale flag.

The AY is three bars rather than one lamp, and the height of each is that
channel's amplitude — a chip using one channel is doing something quite
different from a chip using all three. A channel counts only while the mixer has
not switched off both its tone and its noise, since a game that finishes with a
channel usually silences it there and leaves the amplitude behind. **A channel
following the envelope generator reads as full**: where the envelope has got to
is `static` inside Fuse's `sound.c` and not reachable from here, and computing
it again on this side would mean a second envelope generator that could disagree
with the one being heard.

The lamps sit against the picture rather than the window, so they stay with the
thing they describe when the keyboard is beside the screen: a row under it in
portrait, a column beside it in landscape, which the view decides from the
configuration rather than being told. They are placed before the joystick and
the joystick is told to keep clear of them, because both want the space under
the picture in portrait and only one of them has somewhere else to go. In
landscape they are a column down the inside edge of the screen's half, which is
also the far end of the band above a keyboard on the left — so that band is cut
back to them as well.

State is polled, not pushed. It changes at 50Hz — far faster than an eye reads a
lamp, and far too fast to be worth a callback and a thread hop each time — so
the view looks every 100ms and redraws only on a change.

What it must *not* do is say what it sees. The first version put the live state
in the view's `contentDescription`, which is ten window-content-changed events a
second: the accessibility tree never settles, anything waiting for it to settle
waits for ever, and the whole instrumentation suite went red with *the ☰ button
never appeared*. A screen reader would have fared no better. The description is
now what the strip is, set once.

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

**Arriving ROMs start the machine.** Nothing about a folder that now has ROMs
in it needs confirming, and a first install that ends with the panel still up
and no button on it that runs anything reads as a broken app — which is what it
did before, since the panel waited to be dismissed by hand. So the import
finishes by starting the emulator, or by restarting the process if Fuse has
already run and given up.

What it does say something about is an *incomplete* set. `Storage.MACHINE_ROMS`
lists the twenty-six filenames Fuse's `settings.dat` defaults name, and
`missingRoms()` reports whichever are not in the folder. That is a report and
not a refusal: a set that is only 48K and 128K runs those two machines
perfectly well, so the panel names up to ten of the absentees, says the
machines that want them will not start, and offers *Run anyway*. The check is
by filename only — a file called `48.rom` that is not one is Fuse's problem,
and Fuse says so in its own way.

### Pokes

One byte into the sixteen bit space as it is paged now, which is what POKE means
and what Fuse's own `poke_apply()` does for a poke with no bank of its own:
`writebyte_internal()`, not `writebyte()` — this is a debugger's write, not the
machine's, and it has no business waiting for contention or telling the ULA about
it. Queued like every other input, so it lands between frames rather than under
the Z80's feet.

Two ways in, because they are two different things. *Poke once…* is a byte being
tried: two fields, applied, kept nowhere. *Add a poke…* takes a name as well and
puts it on the list, and **nothing on the list is applied by being there** — a
stored poke is a thing to press. That is what makes it survive a reset: load the
game again, press the poke again. The name defaults to whatever media was last
opened, since a poke without a game's name against it is a poke nobody will
recognise in a month.

Tapping a stored poke pokes it and holding one forgets it, with a confirmation.
A row whose tap means "use this" cannot also mean "throw this away", and a second
row per poke would double the list — so `MenuDrawer.addItem()` grew an optional
long press.

Numbers are read as decimal, or as hex behind `0x`, `$` or `#`, because poke
lists in the wild are written both ways. Anything else, and anything out of
range, is refused with a note rather than silently clamped: a poke to the wrong
address is worse than no poke.

### The cheat database

Three and a half thousand games with cheats, thirty four thousand fingerprints to
find them by, one megabyte of APK. It is ZX Pokemaster's database with everything
an emulator has no use for taken out — see `scripts/build-poke-db.py`, which is
where the shape of it is explained.

**Matched by hash, not by name.** A name is whatever the person who dumped the
file felt like typing; an md5 is the same everywhere. Measured against a real
10,794-file collection: **73% of the files were recognised** and **a third had
cheats**. The misses are almost all releases newer than the database and Russian
`.scl` images with 8.3 names — and a sample of them showed only 3 in 14 present in
the database *under any name*, which is why there is a search by name but no
fuzzy matching: the data is not there to be found.

The hash is taken in `stage()`, while the picked document is being copied to
somewhere Fuse can open by path. The bytes are already going past, so it costs one
pass rather than a second read, and what gets hashed is the file as distributed —
which is what the fingerprints are of.

Two things about Android's SQLite shaped the code. It cannot open a database
inside an APK, so the asset is copied to `files/` on first use, with the version
stamp written *after* the copy so an interrupted one is done again. And
`rawQuery` binds only strings while a compiled statement binds a blob but returns
only one value, so a lookup is two steps: the hash finds the game's id through
`bindBlob`, the id finds the row. The alternative — hex strings — is a megabyte
more asset for nothing.

**The .pok format is kept verbatim** and parsed in the app: `N` names a cheat,
`M` and `Z` are its pokes with `Z` the last, `Y` ends the file. Twenty lines of
parsing against three tables and a join, and the format has not changed in thirty
years. Two conventions in it matter:

- **Bank 8 means "wherever the machine is paged now"**, which is what
  `writebyte_internal()` does and all this app can offer. 72,024 of the 72,046
  pokes are bank 8; the rest name a RAM page, are skipped, and are counted in the
  note so nobody is told a cheat worked when half of it did not.
- **Value 256 means "ask"** — a number of lives, usually. 2,585 pokes are marked
  that way, and the cheat's row ends in an ellipsis to say so before it is tapped.

**The data is not ours, and it has no licence.** The pokes are The Tipshop's,
gathered as AllTipshopPokes; neither states terms, though the Tipshop invites
linking, which is what *Look it up at The Tipshop…* does. The About screen and the
README credit all three sources, and the counts on that screen are read out of the
database's own `meta` table rather than typed in, so they cannot go stale. If this
ever has to come out of the app, the ROM policy is the model: import it rather
than ship it.

### The tape deck

Fuse has the transport already — `tape_do_play`, `tape_stop`, `tape_rewind`,
`tape_is_playing` — so the menu is a few commands on the queue and one more field
in the once-a-frame snapshot. Three things about it are worth knowing.

**Play and Stop rather than a toggle.** `tape_toggle_play` exists and is the wrong
call from here: the app decides which way round it is from the published state,
and by the time a toggle ran on the emulation thread the tape could be the other
way. So the command carries the state it wants.

**Stop is the pause.** Fuse keeps the position, so playing again carries on from
where it stopped; rewind is `tape_select_block(0)` and not a wind. There is
nothing else to offer — no fast wind, no separate pause.

**Fuse stops the tape by itself.** With *Detect loaders* on, playing a tape while
the machine is not loading anything stops it again within a moment, which looks
exactly like the button not working. It is Fuse doing what that setting says.
Testing this needs the setting off, or a machine that is actually loading.

The rows only appear with a tape in, since `tape_play` refuses an empty one, and
a row that cannot act is worse than no row — which is what *Save tape…* was, since
it answered a tap with a toast saying there was nothing to write. `tape_close()` is deliberately *not*
offered: it is what *New tape* already does, and it asks through Fuse's own modal
unless `tape_modified` is cleared first — which is why the new-tape command
clears it.

**The browser is a list from the snapshot, not a walk on demand.** Blocks are
described by `libspectrum_tape_block_description()` for the type and Fuse's
`tape_block_details()` for the specifics, both formatted on the emulation thread
into `android_state.c`'s array — because a tape that is playing is a list being
mutated, and the UI thread has no business walking it. Two things keep the cost
down: the descriptions are rebuilt only when the block count or the current block
changes, or when the bridge says the tape may have changed, and the list is capped
at 128 blocks, since a TZX can carry thousands of pulse blocks and a list nobody
can scroll is no more use than a shorter one.

That dirty flag exists because **two different tapes can have the same number of
blocks and both be at the start**, so the count and the position cannot tell one
from the other. Anything that opens a file sets it, which covers autoloading too,
since that goes through the same command.

A single-choice dialog rather than a table: the tape is at exactly one block, and
tapping another winds to it with `tape_select_block()`. Fuse's own browser has two
columns; a phone-width row has space for one line, so the type and the details go
into it together.

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

**Every picker asks for `*/*`, and has to.** It is tempting to narrow *Load
disk* to disk images, but the document picker filters on MIME type and never on
extension, and an Android 16 device answers `application/octet-stream` for
`.trd`, `.scl`, `.dsk`, `.tap`, `.tzx`, `.z80` and `.rom` alike. So no filter can
tell a TR-DOS image from a +3 one; asking for octet-stream would let every tape
and snapshot through regardless, and would *hide* files a cloud provider chose to
label something else. A browser of the app's own is the way to filter by type,
and is planned rather than written.

Worth knowing before writing it: Fuse does not gate disks by drive either.
`disk_open()` identifies the image from its content and builds the geometry for
whatever drive it was handed, so a `.trd` goes into a +3's drive A: happily and
simply will not boot — the +3's ROM cannot read a TR-DOS filesystem. Filtering
by drive is guidance, not validation.

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

