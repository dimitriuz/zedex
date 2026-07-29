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

☰ is one icon in a **quick bar**, and the bar fades out three seconds after it
is last used, because it sits in the corner of the picture and is therefore in
the way of the thing it belongs to. A tap anywhere on the screen brings it
back; closing the sheet takes it away again. It starts visible rather than
hidden — a control nobody knows is there is worse than one briefly in the way —
and it is **gone entirely while the ROMs panel is showing**, ☰ and all. With no
machine there is nothing for any of it to act on: no state to save, nothing to
pause, no picture to photograph, no drives to look in. It kept ☰ for a while so
that the data folder stayed reachable, but the panel's own three options are the
doors out of it — download a set, import a folder, import files — and each of
them puts ROMs where they are wanted, so a bar of actions that cannot act was
worse than no bar. Nothing reveals it while the panel is up, since startup and
the sheet closing both ask as well as a tap on the picture.

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
opening something, and a handful of doors: *States*, *Media*, *Capture*,
*Machine*, *Controls*, *Settings*.

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
  between them — 1189px of a 2400px window, the largest space of the three.
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
the keyboard for one key is why the keyboard has to be on screen at all.

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

A is fire; B, X and Y are the three key buttons. The shoulders and the middle two
are the app rather than the machine, because a controller is usually the only
thing in reach: **Start** is Enter — as itself, not as whatever Button 1 holds,
because a game that says PRESS ENTER wants Enter — **Select** puts the on-screen
keyboard away and brings it back, **L1** loads a state and **R1** saves one.
Those are the app's own and are not part of a profile; rebinding them would only
hide them. They act on the press alone, since a release has nothing to undo and
doing them twice a push would open and close a dialog.

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
are constant across a draw and so cost a predictable nothing, and three
programs would share nine tenths of their code.

Everything is in units that mean something. `u_source` is the emulated frame in
its own pixels, so a scanline is one emulated row and stays one however far the
picture is scaled; the mask is in *output* pixels, because a shadow mask is a
property of the glass and not of the signal. Scanlines are a sine rather than a
stripe, since a beam is not a step function. Both scanlines and the mask take
light away, so the shader gives back roughly what they cost — a filter that only
made the picture dimmer would be a poor trade.

The sampler stays `GL_NEAREST` unless something wants otherwise. *Sharpness* is
bilinear sampling pulled towards the middle of each source pixel, so at 100% it
is nearest neighbour and easing it off softens only the boundary instead of
blurring everything; at 100% the shader's own snapping already lands on a texel
centre, so the sampler is switched to nearest and the default look is exactly
what it was.

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

