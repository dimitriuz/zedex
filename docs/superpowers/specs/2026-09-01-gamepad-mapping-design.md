# Gamepad mapping: which physical button is which control

**Date:** 2026-09-01
**Status:** approved, not yet implemented

## The problem

`Gamepad.key()` decides what a controller does with a `switch`:

```java
case KeyEvent.KEYCODE_DPAD_LEFT:  return way(0, pressed);
...
case KeyEvent.KEYCODE_BUTTON_A:
case KeyEvent.KEYCODE_DPAD_CENTER:  send(FuseNative.JOYSTICK_FIRE, pressed);
case KeyEvent.KEYCODE_BUTTON_B:     send(ControlProfiles.BUTTON_1, pressed);
case KeyEvent.KEYCODE_BUTTON_X:     send(ControlProfiles.BUTTON_2, pressed);
case KeyEvent.KEYCODE_BUTTON_Y:     send(ControlProfiles.BUTTON_3, pressed);
```

That is right for a pad that agrees with Android about what its buttons are
called, and there is no way to say anything else. A pad that reports its faces
in a different order plays with fire on the wrong button and cannot be
corrected. A pad whose directions arrive on an axis this class does not watch —
it watches `AXIS_X`/`AXIS_HAT_X` and `AXIS_Y`/`AXIS_HAT_Y` and nothing else —
does not steer at all, and no amount of remapping *buttons* could fix it.

The odd thing is that the app already knows how to solve this. `Hotkeys` stores
button bindings as JSON against an action's name, and `GamepadActivity` captures
them by asking the person to press the button, with this in its own javadoc:

> **Bindings are captured, not chosen.** Tap a row and press the button — the
> screen takes whatever the pad actually sends, which is the only thing that
> works across pads that disagree about what their own buttons are called.

That is the thesis of this whole feature, already written and already shipped —
for what a pad does to *the app*. What it does to *the machine* never got the
same treatment. This closes that.

## What this builds

1. **`PadMap`** — a value object holding which physical binding drives each of
   the eight control slots, built from stored JSON over a built-in default.
2. **A stored mapping per pad**, keyed by device, in one new preference.
3. **A second section in `GamepadActivity`** that captures those bindings the
   same way it already captures hotkeys, accepting an axis push as well as a
   button.
4. **The `switch` above, deleted**, replaced by one lookup.

## Decisions taken, and why

**A mapping belongs to the pad it was captured on.** Keyed on the device rather
than global, because the case this feature exists for is a handheld with a
built-in pad *and* a Bluetooth pad — and a single mapping means fixing one
breaks the other. See *The device key*, which is the one part of this that is
measured before it is trusted.

**A binding may be a button or an axis.** Buttons alone would leave four rows on
the screen that some pads can never fill, which is precisely the pad this is
for. It also lets somebody put a direction on a shoulder, which one-handed play
wants.

**The defaults stay underneath and a capture sits on top.** A pad with no
mapping behaves exactly as it does today, so the README's promise — *"Plug one
in and it works — no mapping screen"* — costs nothing. Fixing one wrong button
does not mean filling in the other seven.

**Binding a button to a slot takes it off whatever else held it.** Otherwise one
press does two things. This is stated here because it is the rule that surprises:
capturing Fire on B silently unbinds Button 1, and the screen has to show that
having happened.

**`START` stays Enter and is not mappable.** It is not one of the eight, and
`Gamepad` already says why it is special: *"a game that says PRESS ENTER wants
Enter"*. Revisit if somebody asks.

**Hotkeys are not remapped and must not be.** They are already captured physical
keycodes, and `hotkey()` already runs before the switch this replaces. Keeping
that order is what stops remapping the face buttons from silently moving the
hotkeys.

## Where it lives

| | |
| --- | --- |
| `input/PadMap.java` | new. The table, the rules, the JSON. No Android input classes beyond the keycode and axis constants, so it is testable on the JVM tier |
| `input/Gamepad.java` | the `switch` goes; `slotFor` arrives. The hotkey path, the two-path direction merge and the trigger-as-axis fix are untouched |
| `screen/GamepadActivity.java` | a second section of rows, and `onGenericMotionEvent` for capturing an axis |
| `storage/Prefs.java` | the new key |
| `app/src/test/java/dev/ldlab/zedex/input/PadMapTest.java` | new |

## The device key

**Measured**, 2026-09-01, on a Realme RMX5061 with a **GameSir-Cyclone Pro**
over Bluetooth and a **Microsoft X-Box 360 pad** over USB, read with
`adb shell dumpsys input`.

**Verdict: key on `InputDevice.getDescriptor()`.** Every descriptor was
identical across all four readings; every kernel id moved in three of them.

### Reading 1: connected

```
16: GameSir-Cyclone Pro
    Classes: KEYBOARD | GAMEPAD | JOYSTICK | BATTERY | EXTERNAL
    Descriptor: f5c2919fbd87c6e420793988bf6cf56cfe3d374a
    Identifier: bus=0x0005, vendor=0x3537, product=0x1023, version=0x0101,
                bluetoothAddress=A0:5A:59:BD:2A:C5
17: GameSir-Cyclone Pro Consumer Control
    Classes: KEYBOARD | BATTERY | EXTERNAL
    Descriptor: ee0742fe7486962e718e6305dae01c3a983a316a
    Identifier: ...same vendor, product, version and bluetoothAddress
18: GameSir-Cyclone Pro Keyboard
    Classes: KEYBOARD | ALPHAKEY | BATTERY | EXTERNAL
    Descriptor: 3c1f1565ca029305b95c887240d8bd64a104e1b6
    Identifier: ...same vendor, product, version and bluetoothAddress
```

**One pad is three input devices.** That was not anticipated anywhere above and
it changes two things:

- **`vendor:product` alone is not a usable fallback key** - all three share
  `3537:1023`, so a mapping keyed that way would be shared between the pad and
  its own consumer-control and keyboard endpoints. The fallback must carry the
  name: `vendor:product:name`, which does separate them.
- **Picking the pad by source is right, and is now load-bearing rather than
  incidental.** Only id 16 reports `GAMEPAD | JOYSTICK`; the other two are
  keyboards. `padKey()` must find the device by those sources, which is also
  what `Gamepad.isFrom` already asks of an event, so the two agree.

Three different descriptors from identical vendor, product, version and
Bluetooth address also means **the descriptor incorporates the device name**,
whatever else is in it.

### Reading 2: the pad powered off and on

**All three descriptors identical.** `f5c2919f…`, `ee0742fe…`, `3c1f1565…`,
unchanged.

The kernel device ids moved, 16/17/18 to 19/20/21, which is the point of not
using them: an id is a slot in a list of what is currently attached, and it
changes every time anything is plugged in. The descriptor is what survives.

Also visible in the fuller dump: `Location: 0c:e6:7c:1b:93:a4`, which is the
**phone's own Bluetooth adapter**, not the pad's. That is a per-phone identifier
and must not reach a bug report either.

### Reading 2b: a USB pad, and two pads at once

A **Microsoft X-Box 360 pad** over USB, with the GameSir still connected:

```
22: Microsoft X-Box 360 pad
    Classes: KEYBOARD | GAMEPAD | JOYSTICK | EXTERNAL
    Descriptor: dc4619eefd76423d152d1b3789814fb245dfd008
    Location: usb-11200000.xhci0-1/input0
    Identifier: bus=0x0003, vendor=0x045e, product=0x028e, version=0x0114,
                bluetoothAddress=<not set>
```

Three things, and the second one is a bug in the plan.

**A USB pad is one device.** The Bluetooth pad's three endpoints are a
Bluetooth HID artifact, not something every pad does. Choosing by
`GAMEPAD | JOYSTICK` sources handles both shapes, so that stays right.

**Two pads can be connected at once, and both report those sources.** The plan's
`padKey()` walked the device list and returned the first pad it found - which,
with two attached, is whichever the list happens to hold first. It would load
one pad's mapping and apply it to events from both. That is the exact failure
per-pad storage exists to prevent, arrived at from the other direction. See
*Resolving the pad per event*, which replaces it.

**A pad with no unique id cannot be told from another of the same model.**
`045e:028e` is the generic XInput identity that a great many third-party pads
report, and there is no `uniqueId` to separate two of them. Two identical USB
pads will therefore share one mapping. That is acceptable - same model, same
layout - but it is a property to know rather than discover, and it is the
opposite of the Bluetooth case, where every unit is distinct.

### Reading 3: forgotten in Bluetooth settings and paired again

**All three descriptors identical.** `f5c2919f…`, `ee0742fe…`, `3c1f1565…`,
unchanged.

This is the reading that mattered most. A power cycle only re-establishes a
link; forgetting the device drops the bond and pairs it as though it had never
been seen, and it is the thing most likely to have moved a descriptor. It did
not. The kernel ids moved again, 19/20/21 to 23/24/25.

### Reading 4: the phone rebooted

**Every descriptor identical.** The GameSir's three, and the X-Box pad's.

The ids are worth putting side by side, because they are what a reasonable
person would have reached for first:

| | pad | consumer | keyboard | X-Box |
| --- | --- | --- | --- | --- |
| 1. connected | 16 | 17 | 18 | - |
| 2. power cycled | 19 | 20 | 21 | - |
| 2b. USB pad added | 19 | 20 | 21 | 22 |
| 3. re-paired | 23 | 24 | 25 | 22 |
| 4. rebooted | 9 | 10 | 11 | 8 |

Four different ids for one pad that never changed; one descriptor throughout.
An id is a slot in the list of what is attached at this instant, and the reboot
makes that plainest - the X-Box pad went from 22 to 8 without being touched.

### The answer

**Key on `InputDevice.getDescriptor()`.** It survived a power cycle, a full
forget-and-re-pair, and a reboot, on both a Bluetooth and a USB pad.

**Fall back to `vendorId:productId:name`** where a device reports no descriptor
at all. The name is not optional in that fallback: one Bluetooth pad is three
input devices sharing a vendor, a product and an address, and only the name
separates them.

### Whether the descriptor is MAC-derived: not answered, and not answerable from here

AOSP builds it from a unique id where the device has one, which for Bluetooth is
the address - and that was **not** confirmed here.

Two reconstruction attempts, both failed. The second was worth making because
the data suggested a formula: AOSP's `assignDescriptorLocked` appends the device
*name* only when vendor and product are both zero, and these three share a
non-zero `3537:1023` and one Bluetooth address while having three different
descriptors - which points at the nonce that EventHub assigns to break exactly
such a collision. `sha1("3537:1023:uniqueId:A0:5A:59:BD:2A:C5")` and the same
with `nonce:%04x` appended for 0-7, in four spellings of the address, matched
none of the three.

A third attempt was made and it was the decisive one, because the USB pad is a
case whose inputs are **all** visible: no `uniqueId`, so AOSP's own
`generateDescriptor` should reduce to `"045e:028e:"` and nothing else. Seven
spellings were tried - that bare form, plus name, plus location, plus bus, plus
version, and two orderings - and **none matched `dc4619ee…`**.

So the method is wrong, not the theory. Whatever this build computes, it is not
SHA-1 of the strings AOSP's published source suggests - unsurprising on a
heavily customised Oplus build, and unknowable without its source. **Both
earlier failures therefore carry no information at all**, and no further
guessing is warranted.

What that leaves: the descriptor's contents are opaque here, AOSP documents it
as possibly incorporating a device's unique id, and a Bluetooth device's unique
id is its address. **That is enough on its own to keep it out of `Diagnostics`**
- the decision never needed the proof, and it stands on the documented
possibility rather than on a measurement nobody can take.

**So the descriptor stays out of `Diagnostics`.** Unproven, and the conservative
direction is the one where a bug report cannot carry a per-unit identifier.
Name and mapping only. Revisit only with a second pad of the same model in hand.

### Resolving the pad per event

`Gamepad` cannot hold one `PadMap`, because two pads can be connected at once
and each has its own. It holds a lookup instead, and asks it with the device id
every event already carries:

```java
    public interface Maps {
        /** This device's mapping. Never null; the defaults for a pad nobody
         *  has changed, and for a device that has gone away. */
        PadMap forDevice(int deviceId);
    }
```

`EmulatorActivity` supplies one over `PadMaps`, caching by device id so that a
`getDevice` and a JSON parse do not happen per button press, and dropping the
cache when a device is added or removed or the mapping is edited. The seam
itself is sound — `PadMapCache` is genuinely testable on the JVM tier through
its own `Devices` seam — but the specific claim that a fake `Maps` would make
`Gamepad` assertable turned out to be false when someone tried to keep it.
**`Gamepad.key()` and `Gamepad.motion()` cannot be exercised on the JVM tier at
all**, which is what `GamepadTest`'s own javadoc measures and explains in detail:
the mockable `android.jar` substituted at test runtime hardcodes defaults (0,
false, null) for every framework method regardless of what was passed to a
constructor, and those getters are `final` on the real SDK, so overriding them
does not compile. Adding Robolectric or mockito-inline to `testImplementation`
would fix this properly, but is a build-configuration decision — not this
feature's call. The seam kept `PadMapCache` out of `Gamepad` and testable; that
part of the architecture held. What did not hold was the promise written before
it was measured.

`Gamepad.releaseAll()` still applies to everything, since a lookup changing
means whatever was down was down under the old arrangement.

## `PadMap`

### The slots

The eight already exist and are not re-invented: `FuseNative.JOYSTICK_LEFT`,
`RIGHT`, `UP`, `DOWN`, `FIRE` (0–4) and `ControlProfiles.BUTTON_1`, `BUTTON_2`,
`BUTTON_3` (5–7), `ControlProfiles.SLOTS` = 8.

`ControlProfiles.slotName(slot)` already puts words to them - but they are
**hardcoded English**, not resources, which is why the profile editor's own rows
read in English on a Polish phone. Reusing it here would spread that to a second
screen. Either give the eight slots real string resources and have both screens
read them, or reuse `slotName` and accept the inconsistency knowingly. Decide
before writing the rows: the first is nine files and fixes the profile editor
too.

### The stored form

One preference, `padMappings`, a JSON object. **A String**, so it is read with
`getString` and `scripts/check-prefs.py` has to pass — a preference read as the
wrong type throws only on a device where the key is present, which is how 1.1.0
shipped a crash in the bug reporter.

```json
{
  "1a2b3c4d": {
    "name": "8BitDo SN30 Pro",
    "FIRE": "k96",
    "BUTTON_1": "k97",
    "LEFT": "a15-"
  }
}
```

- Keyed by **slot name**, not index, for the reason `Hotkeys` gives for its own
  bindings: the constants can be reordered without spoiling what anyone saved.
- A binding is a short string: `k<keycode>`, or `a<axis><+|->`. Two shapes, so a
  tagged integer would be unreadable — and this has to be legible in a bug
  report, which is where "my pad is wrong" gets answered.
- `name` is `InputDevice.getName()`, carried so the screen can list a pad that is
  not plugged in at the moment, and so a report says which pad it was.
- An unparseable value, an unknown slot name, or malformed JSON is **ignored**,
  and the rest of the mapping still loads. A pad's mapping is not worth losing
  whole because one row of it is from a future version.

### The effective table

Computed once at construction, so `slotFor` is a lookup and every rule lives in
one place:

```
effective = the defaults           (binding -> slot)
for each (slot, binding) in the stored overrides:
    remove EVERY entry whose value is slot
    remove the entry whose key is binding, if any
    effective[binding] = slot
```

`slotFor(keycode)` and `slotFor(axis, sign)` read it; `bindingFor(slot)` inverts
it for the screen.

Two things fall out of that, both wanted:

- **Binding B to Fire unbinds Button 1.** `A=FIRE, B=BTN1, X=BTN2, Y=BTN3` with
  `FIRE <- B` becomes `B=FIRE, X=BTN2, Y=BTN3`; A and Button 1 have nothing.
- **Capturing a direction replaces *both* of its defaults.** A direction has two
  by design — the stick and the hat — which is why the removal is of every entry
  for the slot rather than of one. A captured Left should be the only Left.

### The defaults

Exactly today's behaviour, written as a table rather than a `switch`:

| Slot | Default bindings |
| --- | --- |
| `LEFT` | `KEYCODE_DPAD_LEFT`, `AXIS_X-`, `AXIS_HAT_X-` |
| `RIGHT` | `KEYCODE_DPAD_RIGHT`, `AXIS_X+`, `AXIS_HAT_X+` |
| `UP` | `KEYCODE_DPAD_UP`, `AXIS_Y-`, `AXIS_HAT_Y-` |
| `DOWN` | `KEYCODE_DPAD_DOWN`, `AXIS_Y+`, `AXIS_HAT_Y+` |
| `FIRE` | `KEYCODE_BUTTON_A`, `KEYCODE_DPAD_CENTER` |
| `BUTTON_1` | `KEYCODE_BUTTON_B` |
| `BUTTON_2` | `KEYCODE_BUTTON_X` |
| `BUTTON_3` | `KEYCODE_BUTTON_Y` |

`AXIS_X` and `AXIS_HAT_X` are both listed because `Gamepad.axis()` already takes
whichever of the two is pushed furthest; the table says the same thing in the
new vocabulary. A `PadMapTest` case asserts this table against the old `switch`,
case for case, and that test is the reason it is safe to delete it.

## `Gamepad`

`key()` keeps its shape. After `hotkey()`, which is unchanged and still first:

```java
int slot = map.slotFor(event.getKeyCode());

if (slot == PadMap.NONE) {
    if (event.getKeyCode() != KeyEvent.KEYCODE_BUTTON_START) return false;
    FuseNative.key(KeyEvent.KEYCODE_ENTER, pressed);
    return true;
}

if (slot <= FuseNative.JOYSTICK_DOWN) return way(slot, pressed);

send(slot, pressed);
return true;
```

`way()` and `send()` are as they are. The direction slots go through `way()`
because the two-path merge has to survive: many pads report the hat as both an
axis and a D-pad key, and a release down one path would otherwise cancel a press
that came down the other.

`motion()` keeps `fromAxes` and gains the map. Where it now hard-codes four
comparisons it asks `slotFor(axis, sign)` for every axis the device reports
(`InputDevice.getMotionRanges()`), so a bound axis nothing watches today works.
`Mouse.stick()` keeps reading `AXIS_X`/`AXIS_Y` directly — the mouse is a stick,
not four directions, and remapping does not apply to it.

`setMap(PadMap)` beside the existing `setHotkeys`, called when the device
changes and when the setting changes, and it calls `releaseAll()` first for the
same reason `setHotkeys` calls `endHold()`: whatever was down was down under the
old arrangement.

## The screen

`GamepadActivity` gains a section rather than a sibling screen. It is already
*"binds a controller's buttons"*, already captures, already handles there being
no pad connected, and a second screen would be a second copy of all of that.

- **The machine** — eight rows — above **The app**, the existing hotkey rows.
  The machine first because it is what most people come for.
- A **pad picker** at the top, listing the connected pad and any others with a
  stored mapping, so a mapping can be corrected without the pad to hand.
- **Reset this pad**, which drops its entry and returns it to the defaults.
- A row shows what it is on now, whether that is a capture or a default, and
  says which — a default that looks like a choice invites re-capturing it for no
  reason.

**Capturing an axis** is the new mechanism. `onGenericMotionEvent` takes the axis
furthest past a threshold **well above** the play threshold — `DEAD_ZONE` is
0.4, so capture at 0.7 — and requires every axis to fall back under 0.2 before
it will arm again. Without that, a worn stick's resting drift binds itself the
instant a row is tapped, and the person never sees what happened.

Pad input must not also move the screen's own focus while a row is waiting; the
existing capture already claims key events, and the motion path needs the same,
which is the note in CLAUDE.md about `onGenericMotionEvent` having to keep
claiming pad input because a hat axis moves no focus by itself.

## Strings

New strings for: the two section headings, the eight slot rows (see *The slots* — they may or may not need
resources of their own), the pad picker, *Reset this pad*, "press a button or push a direction",
and the "this is the default" marker. **A new string is nine files**, and
`scripts/check-strings.py` has to pass, so the row list is worth settling before
any of them are written.

## Testing

**JVM, `PadMapTest`** — this is why the mapping is a value object and not a
lookup inside `Gamepad`:

- the defaults reproduce the old `switch` case for case, including
  `DPAD_CENTER` as a second Fire — the guard that makes deleting it safe
- an override takes its binding off the slot that had it, and the old binding
  off the overridden slot
- capturing a direction drops **both** its default bindings, stick and hat
- an axis binding and a key binding for one slot both resolve
- JSON round-trips; an unknown slot name, an unparseable binding and malformed
  JSON each leave the rest intact and fall back to the defaults
- two devices' mappings are independent, and an unknown device gets the defaults

**On the device, with the real Bluetooth pad:**

- the descriptor measurement above, first, before the storage is written
- an unmapped pad behaves exactly as it does today — the README's promise, and
  the thing most likely to be broken by accident
- Fire remapped, checked in a real game rather than on the screen that set it
- the built-in pad and the Bluetooth pad keeping separate mappings
- a mapping captured on an axis, on a pad where that axis is the hat
- the pad unplugged mid-press leaves nothing held (`releaseAll` still fires)

**Not an instrumentation test.** The bench has no pad, and a UI Automator test
that skips when none is connected prints `OK` having asserted nothing — the
failure mode this project has already been bitten by twice.

## Diagnostics

`Diagnostics` already enumerates input devices, and adding the mapping would
make "my pad is wrong" answerable from a report instead of a conversation.

**The pad's name and its mapping, not its descriptor.** `DiagnosticsTest`
asserts the report carries no identifier, and Android's descriptor is
MAC-derived on some devices — so it stays out unless the measurement above shows
two pads of the same model producing the same string.

## Build order

1. Measure the descriptor. Decide the key. Write it into this document.
2. `PadMap` and `PadMapTest`, defaults only — the table, asserted against the
   `switch` that is still there.
3. `Gamepad` reads `PadMap`; delete the `switch`. Nothing has changed
   behaviourally and the test says so.
4. Overrides, the effective table, and the rest of `PadMapTest`.
5. Storage: the preference, load and save, `check-prefs.py`.
6. The screen's machine section, buttons only.
7. Axis capture.
8. The pad picker and reset.
9. Diagnostics.
10. README and `docs/USING.md`.

Steps 2 and 3 are deliberately a pair that changes nothing: the refactor lands
proved before the feature is built on it.

## Not doing

- **Remapping `START`.** See above.
- **Remapping the hotkeys' modifier**, which is already captured and already
  per-button.
- **Per-game mappings.** `Keymap` and `Suggested` already do the per-game thing
  at the layer above — which Spectrum key a button sends. This layer is which
  physical button *is* Button 1, and it belongs to the pad, not the game.
- **Analogue steering.** Four on-or-off directions is what a Spectrum joystick
  is. The mouse already reads the stick as a stick and keeps doing so.
- **Importing anyone else's mapping format.** SDL's database and Recalbox's
  `p2k.cfg` both exist; `Keymap` already reads the second for a different
  purpose. Worth revisiting only if capturing turns out to be the slow part,
  which it is not yet known to be.
