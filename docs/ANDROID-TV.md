# Android TV: what it would take

Findings from reading the tree on 2026-08-06, before any code. Nothing here is
implemented; this is the shape of the job and the decisions it needs.

**The verdict is that this is a small job wearing a large hat.** The emulation,
the renderer and the audio need nothing at all. What TV asks for is that every
screen can be reached without a finger, and that a game can be found without a
file picker — and one of those two is already on the list for other reasons.

## What already works

Rather more than expected, because two earlier decisions happen to have been the
TV-shaped ones.

- **The manifest already says a touchscreen is optional.**
  `android:name="android.hardware.touchscreen" android:required="false"` is in
  `app/src/main/AndroidManifest.xml`. That is the single declaration most ports
  get wrong, and without it Play will not list an app on TV at all.

- **The controller support is the TV control scheme, already written.**
  `input/Gamepad.java`: A is fire, B, X and Y are the three key buttons beside
  it, the stick and the hat and the D-pad all steer, Start is Enter. Everything
  the app wants for itself is behind a hotkey modifier rather than taking
  buttons away from the game, and `ControlProfiles` already maps buttons to
  arbitrary Spectrum keys — so a game wanting QAOP is playable on a pad that has
  never heard of a Q. None of this needs revisiting for TV.

- **A USB or Bluetooth keyboard already works**, through Fuse's own keysym table
  (`native/ui/android/keysyms.c`, reached from `FuseNative.mapsKey`). Enter and
  the cursor keys included.

- **The menus already take focus.** `view/QuickBar.java` and `view/MenuDrawer.java`
  both build their rows with `setFocusable(true)` and `setClickable(true)`, so
  Android's focus engine can walk them with a D-pad without anything being
  added. How far that carries is the audit below, but the foundation is there.

- **The picture can already be alone on the screen.** The layout hides the
  on-screen pad and keyboard on request, which is the TV default, and
  fullscreen is the normal state there rather than a mode.

- **GL and AAudio are fine on TV**, and the app already copes with more than one
  display (`screen/Panels.java`), so nothing is wedded to a single phone window.

## What is missing

### Packaging — hours

- A `LEANBACK_LAUNCHER` intent filter, or the app does not appear in the TV
  launcher at all.
- A banner, 320×180 xhdpi, on the application or the activity.
- `uses-feature android.software.leanback required="false"`, so one APK still
  installs on both.
- For Play: a TV listing with its own screenshots, and the TV quality checks.
  The `play` build type already exists to ship from.

### Input arbitration — about a day, and there is a real bug in here

**A TV remote is not a gamepad, and today the machine treats its D-pad as the
Spectrum's cursor keys.** `Gamepad.isPad()` asks for `SOURCE_GAMEPAD` or
`SOURCE_JOYSTICK`; a remote reports `SOURCE_DPAD | SOURCE_KEYBOARD`, so
`EmulatorActivity.onKeyDown` falls past the controller and into `forwardKey`,
and `keysyms.c` maps `AKEYCODE_DPAD_UP`…`RIGHT` to `INPUT_KEY_Up`…`Right`. That
is playable for a game that reads the cursor keys and useless for everything
else, which is most of them.

What it should do:

- D-pad steers the **joystick** while the machine has the focus, exactly as a
  pad does, so the joystick type setting decides what the game sees.
- D-pad **navigates** while a menu is open — the focus engine already would,
  provided the machine's own handling stands aside.
- OK / `DPAD_CENTER` is fire; `BACK` opens the menu, which it already does.
- Somewhere for the quick bar: **it is summoned by tapping the picture**, and
  there is no tap on a television. Back-into-the-menu covers everything, but the
  bar wants a key of its own.

### Reaching every screen — the long pole, but a shallow one

A walk through each surface with nothing but a D-pad: the first-run panel, the
ROMs panel, states, pokes, media, the disk pages, every dialog, and the settings
screens (androidx preference is already navigable). Much of this will work as it
stands; what will not is anything that is a picture with taps on it rather than
views, and anything that only appears in response to a touch.

### Overscan — small

`view/SafeArea.java` already exists for display cutouts; a television wants the
same idea with a small inset. Modern sets mostly do not overscan, so this is a
setting or a fixed few per cent rather than a fight.

## The two real risks

### Opening a file

**Android TV frequently has no Documents UI at all**, so `ACTION_OPEN_DOCUMENT`
can resolve to nothing and *Open file…* becomes a button that does nothing. On a
phone that path is the main way a game arrives; on a television it may not
exist.

This is where the file browser that is already wanted for other reasons — one
that can look inside a `.zip`, which the system picker cannot filter for anyway
— stops being a nicety. Until it exists, what TV has is the data folder, *Open
recent*, and a hand-off from another app.

Worth knowing before designing it: a USB stick on a TV box appears as a
removable volume, which is exactly the case the system picker handles badly and
a browser of our own handles by knowing where to look.

### Typing

Three answers, in increasing order of effort. **Try them in this order**, because
the first two may cost nothing:

1. **A USB or Bluetooth keyboard.** Works today, no code. Many TV owners have
   one; nobody should have to.
2. **The system keyboard skin.** `view/SystemKeyboardView.java` is a one-pixel
   view that drives the platform IME and commits text through
   `FuseNative.character`. The television's own IME is remote-navigable, so this
   may already be the answer — and it is the cheapest thing on this page to
   find out.
3. **The drawn Spectrum keyboard, navigated by D-pad.** A focus cursor moving
   over key rectangles that already exist per skin as accessibility nodes.
   Bounded work, and the nicest result, but only worth it if 2 disappoints.

## The control scheme

| Input | Steering | Fire | Keys | The app |
| --- | --- | --- | --- | --- |
| Gamepad *(recommended)* | stick, hat, D-pad | A | B, X, Y and profiles | hotkey + button |
| TV remote | D-pad | OK | the keyboard skin | Back → menu |
| USB/BT keyboard | cursor keys | — | all forty | menu |

Nothing in the first row is new. The second row is the day's work above. The
third already works.

## A plan, in the order that makes it usable soonest

1. **Manifest and banner**, so it installs and appears. Hours.
2. **Remote input**: D-pad to the joystick, OK to fire, a key for the bar.
   This is what turns "it launches" into "a game is playable".
3. **The focus walk**, screen by screen, on a TV emulator; fix what cannot be
   reached.
4. **Typing**: test the IME skin first; only build the navigable keyboard if
   that is not good enough.
5. **Overscan** inset.
6. **The file browser**, as its own piece of work — it is wanted on phones too,
   and it is the difference between "plays what it already has" and "plays what
   is on the stick".

Steps 1–3 are a TV build worth showing someone. Step 6 is what makes it a TV
app people would keep.

## Testing

Neither AVD here is a television. This wants a Google TV or Android TV system
image and an AVD made from it; the remote is then the emulator's own D-pad, and
`adb shell input keyevent KEYCODE_DPAD_*` drives it from a terminal exactly as
`scripts/ui-tap.py` drives a phone. **A TV AVD is the only honest way to test
this** — a phone with a pad plugged in behaves differently, because it still has
a touchscreen and still shows a Documents UI.

## Decisions wanted before any code

- **One APK or a TV build type?** One is simpler and the manifest can carry
  both; a separate type would let the defaults differ (no on-screen controls, no
  first-run storage panel). The build already has three types and a fourth is
  not free.
- **What the remote's D-pad should do by default** — joystick, as argued above,
  or the cursor keys as it accidentally does today. Cursor keys are right for a
  handful of 1983 games and wrong for the rest.
- **Whether the file browser comes first.** It is the larger piece, it is
  useful on phones, and without it a TV build can only play what is already in
  the data folder.
