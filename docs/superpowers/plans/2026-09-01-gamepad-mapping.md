# Gamepad Mapping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a person say which physical button on their controller is each of
the eight Spectrum controls, per pad, without spoiling the pads that already
work.

**Architecture:** A new `PadMap` value object owns the whole rule set - a
built-in default table, stored per-device overrides on top, and the conflict
rule that a binding drives exactly one slot. `Gamepad` loses its hard-coded
`switch` and asks `PadMap` instead. `GamepadActivity`, which already captures
hotkey bindings by asking the person to press the button, gains a second
section that captures these the same way and can also take an axis push.

**Tech Stack:** Java 17, Android SDK 30+, `org.json`, JUnit 4 on the JVM test
tier (`app/src/test`), Gradle.

**Spec:** `docs/superpowers/specs/2026-09-01-gamepad-mapping-design.md`

## Global Constraints

- **`PadMap` must not use `android.util.SparseArray`.** The stub `android.jar`
  answers its methods with defaults, so anything holding a lookup in one is
  unreachable from the JVM test tier - `HotkeysTest`'s own javadoc records this
  about `Hotkeys.Bindings`, which is why `load` there is the one thing it cannot
  cover. Use `java.util.HashMap<Integer, Integer>`. Making the rules testable is
  the entire reason this is a value object rather than a lookup inside
  `Gamepad`.
- **`padMappings` is a String preference**, read with `getString`. A preference
  read as the wrong type throws only where the key is present. Run
  `scripts/check-prefs.py` before every commit that touches preferences.
- **A new user-visible string is nine files** (`values/` plus eight
  translations) and `scripts/check-strings.py` must pass. A missing translation
  is counted, not failed; a format specifier that disagrees with English is a
  failure.
- **Slot indices are `FuseNative.JOYSTICK_LEFT`=0, `RIGHT`=1, `UP`=2, `DOWN`=3,
  `FIRE`=4, `ControlProfiles.BUTTON_1`=5, `BUTTON_2`=6, `BUTTON_3`=7,
  `ControlProfiles.SLOTS`=8.** Never write the numbers.
- **Every activity needs `attachBaseContext`** and sets its title in `onCreate`;
  `GamepadActivity` already does both, so nothing new is needed - do not remove
  them.
- Commit subjects take a conventional prefix (`feat:`, `fix:`, `test:`,
  `refactor:`, `docs:`, `chore:`). The body explains *why*.
- Work happens on the branch `feature/gamepad-mapping`, which already exists and
  holds the spec.

**Run the JVM tests with:**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*PadMapTest*'
```

---

### Task 1: Measure the device key - DONE 2026-09-01

**Done.** The answer is `InputDevice.getDescriptor()`, with
`vendorId:productId:name` as the fallback where a device reports none; the four
readings are in the spec's *The device key*. The steps below are kept as the
record of what was asked, and as the recipe if it ever has to be re-taken on
another device.

This is a measurement, not code, and it is first because its answer goes into
every stored object. `InputDevice.getDescriptor()` is documented as a stable
hash of vendor, product and name; it has never been used in this app; and "my
mapping vanished" is the worst thing this feature can do. Do not write the
storage until this is known.

**Files:**
- Modify: `docs/superpowers/specs/2026-09-01-gamepad-mapping-design.md` (the
  *The device key* section - replace the instructions with the answer)

- [ ] **Step 1: Connect the phone**

Wireless debugging: the phone shows an IP and port under Developer options ›
Wireless debugging. This machine's adb has no mDNS, so it cannot find the phone
itself.

```bash
adb connect <ip>:<port>
adb devices -l
```

- [ ] **Step 2: Read every input device**

```bash
adb shell dumpsys input | grep -iE 'Device [0-9]+:|Descriptor|Vendor|Product|Sources'
```

Write down, for the Bluetooth pad and for any built-in pad: the name, the
descriptor, the vendor id and the product id.

- [ ] **Step 3: Disconnect and reconnect the pad, and read again**

Turn the pad off and on. Repeat Step 2. Record whether the descriptor changed.

- [ ] **Step 4: Re-pair the pad from scratch, and read again**

Forget the pad in Android's Bluetooth settings, pair it again, repeat Step 2.
This is the case most likely to move a descriptor.

- [ ] **Step 5: Reboot the phone, and read again**

```bash
adb reboot
# wait, then reconnect - a reboot drops the wireless debugging session
adb connect <ip>:<port>
```

Repeat Step 2.

- [ ] **Step 6: Write the answer into the spec**

Replace the numbered instructions in *The device key* with what was measured -
the four readings, and the decision:

- descriptor identical in all four → key on `InputDevice.getDescriptor()`
- descriptor moved in any of them → key on
  `getVendorId() + ":" + getProductId() + ":" + getName()`

Also record whether the descriptor looks MAC-derived (a long hex string that
differs between two pads of the same model). Task 10 depends on that answer.

- [ ] **Step 7: Commit**

```bash
git add docs/superpowers/specs/2026-09-01-gamepad-mapping-design.md
git commit -m "docs: the pad's device key, measured"
```

---

### Task 2: `PadMap`, defaults only

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/input/PadMap.java`
- Create: `app/src/test/java/dev/ldlab/zedex/input/PadMapTest.java`

**Interfaces:**
- Consumes: `FuseNative.JOYSTICK_*`, `ControlProfiles.BUTTON_*` and
  `ControlProfiles.SLOTS`.
- Produces: `PadMap.defaults()` returning a `PadMap`; `int slotFor(int keycode)`;
  `int slotFor(int axis, int sign)`; `PadMap.NONE` = `-1`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/ldlab/zedex/input/PadMapTest.java`:

```java
package dev.ldlab.zedex.input;

import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;
import android.view.MotionEvent;

import dev.ldlab.zedex.FuseNative;

import org.junit.Test;

/**
 * Which physical binding drives which control.
 *
 * On the JVM tier deliberately: the bench has no controller, and an
 * instrumentation test that skips when none is connected prints OK having
 * asserted nothing. Everything interesting here is a table and a rule, and
 * both are reachable without a pad - which is the whole reason the mapping is
 * a value object rather than a switch inside Gamepad.
 */
public class PadMapTest {

    /**
     * The defaults are what Gamepad's switch did, case for case.
     *
     * This is the test that makes deleting that switch safe, so it is written
     * out button by button rather than looped: a loop over a table here would
     * be the same table twice and would agree with itself however wrong it was.
     */
    @Test
    public void theDefaultsAreTheOldSwitch() {
        PadMap map = PadMap.defaults();

        assertEquals(FuseNative.JOYSTICK_LEFT,  map.slotFor(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(FuseNative.JOYSTICK_RIGHT, map.slotFor(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(FuseNative.JOYSTICK_UP,    map.slotFor(KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(FuseNative.JOYSTICK_DOWN,  map.slotFor(KeyEvent.KEYCODE_DPAD_DOWN));

        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(KeyEvent.KEYCODE_DPAD_CENTER));

        assertEquals(ControlProfiles.BUTTON_1, map.slotFor(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(ControlProfiles.BUTTON_2, map.slotFor(KeyEvent.KEYCODE_BUTTON_X));
        assertEquals(ControlProfiles.BUTTON_3, map.slotFor(KeyEvent.KEYCODE_BUTTON_Y));
    }

    /** Start is Enter and is not a slot; Gamepad handles it past the map. */
    @Test
    public void startIsNotASlot() {
        assertEquals(PadMap.NONE,
                     PadMap.defaults().slotFor(KeyEvent.KEYCODE_BUTTON_START));
    }

    /**
     * A direction has two default bindings, the stick and the hat, because
     * Gamepad already takes whichever of the two is pushed furthest. The table
     * says the same thing in the new vocabulary.
     */
    @Test
    public void bothTheStickAndTheHatSteerByDefault() {
        PadMap map = PadMap.defaults();

        assertEquals(FuseNative.JOYSTICK_LEFT,  map.slotFor(MotionEvent.AXIS_X, -1));
        assertEquals(FuseNative.JOYSTICK_RIGHT, map.slotFor(MotionEvent.AXIS_X, +1));
        assertEquals(FuseNative.JOYSTICK_UP,    map.slotFor(MotionEvent.AXIS_Y, -1));
        assertEquals(FuseNative.JOYSTICK_DOWN,  map.slotFor(MotionEvent.AXIS_Y, +1));

        assertEquals(FuseNative.JOYSTICK_LEFT,  map.slotFor(MotionEvent.AXIS_HAT_X, -1));
        assertEquals(FuseNative.JOYSTICK_RIGHT, map.slotFor(MotionEvent.AXIS_HAT_X, +1));
        assertEquals(FuseNative.JOYSTICK_UP,    map.slotFor(MotionEvent.AXIS_HAT_Y, -1));
        assertEquals(FuseNative.JOYSTICK_DOWN,  map.slotFor(MotionEvent.AXIS_HAT_Y, +1));
    }

    /** An axis nobody bound drives nothing, whichever way it is pushed. */
    @Test
    public void anUnboundAxisDrivesNothing() {
        PadMap map = PadMap.defaults();

        assertEquals(PadMap.NONE, map.slotFor(MotionEvent.AXIS_RZ, +1));
        assertEquals(PadMap.NONE, map.slotFor(MotionEvent.AXIS_RZ, -1));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*PadMapTest*'
```

Expected: compilation failure, `cannot find symbol: class PadMap`.

- [ ] **Step 3: Write `PadMap`**

`app/src/main/java/dev/ldlab/zedex/input/PadMap.java`:

```java
package dev.ldlab.zedex.input;

import dev.ldlab.zedex.FuseNative;

import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Which physical binding on a controller drives which of the eight controls.
 *
 * A pad that agrees with Android about what its buttons are called needs none
 * of this, and gets none of it: the defaults below are exactly what
 * {@link Gamepad} used to decide with a switch, so an unmapped pad behaves as
 * it always has. What this adds is somewhere for the pads that disagree to
 * say so - and for the ones whose directions arrive on an axis nothing here
 * watches, which no amount of remapping buttons could have fixed.
 *
 * <b>A binding is a button or an axis.</b> An axis carries a sign, since one
 * axis is two directions.
 *
 * <b>The lookup is a HashMap and not a SparseArray</b>, which matters more than
 * it looks: the stub android.jar answers SparseArray's methods with defaults,
 * so a lookup held in one cannot be read on the JVM test tier at all - see
 * HotkeysTest, where that is the one thing it could not cover. Every rule in
 * this class is a rule about a table, and a table nobody can assert against is
 * how a mapping quietly stops mapping.
 */
public final class PadMap {

    /** No control is on this binding. */
    public static final int NONE = -1;

    /**
     * A button, or one direction of one axis, as a single lookup key.
     *
     * Keycodes and axis numbers are both small non-negative ints from
     * different vocabularies, so they are pushed apart rather than trusted not
     * to collide: KEYCODE_BUTTON_A and AXIS_HAT_X are both perfectly ordinary
     * numbers and one of them would otherwise be the other.
     */
    private static final int AXIS_BASE = 1 << 16;

    static int button(int keycode) {
        return keycode;
    }

    static int axis(int axis, int sign) {
        return AXIS_BASE + axis * 2 + (sign < 0 ? 0 : 1);
    }

    /**
     * What a pad does before anyone changes anything: Gamepad's old switch,
     * plus the two axis pairs its motion path already read.
     *
     * A direction has three bindings - the D-pad key, the stick and the hat -
     * because all three steered before this class existed and taking one away
     * would be a regression wearing the clothes of a feature.
     */
    private static final int[][] DEFAULTS = {
        { button(KeyEvent.KEYCODE_DPAD_LEFT),   FuseNative.JOYSTICK_LEFT },
        { button(KeyEvent.KEYCODE_DPAD_RIGHT),  FuseNative.JOYSTICK_RIGHT },
        { button(KeyEvent.KEYCODE_DPAD_UP),     FuseNative.JOYSTICK_UP },
        { button(KeyEvent.KEYCODE_DPAD_DOWN),   FuseNative.JOYSTICK_DOWN },

        { axis(MotionEvent.AXIS_X, -1),         FuseNative.JOYSTICK_LEFT },
        { axis(MotionEvent.AXIS_X, +1),         FuseNative.JOYSTICK_RIGHT },
        { axis(MotionEvent.AXIS_Y, -1),         FuseNative.JOYSTICK_UP },
        { axis(MotionEvent.AXIS_Y, +1),         FuseNative.JOYSTICK_DOWN },

        { axis(MotionEvent.AXIS_HAT_X, -1),     FuseNative.JOYSTICK_LEFT },
        { axis(MotionEvent.AXIS_HAT_X, +1),     FuseNative.JOYSTICK_RIGHT },
        { axis(MotionEvent.AXIS_HAT_Y, -1),     FuseNative.JOYSTICK_UP },
        { axis(MotionEvent.AXIS_HAT_Y, +1),     FuseNative.JOYSTICK_DOWN },

        { button(KeyEvent.KEYCODE_BUTTON_A),    FuseNative.JOYSTICK_FIRE },
        { button(KeyEvent.KEYCODE_DPAD_CENTER), FuseNative.JOYSTICK_FIRE },

        { button(KeyEvent.KEYCODE_BUTTON_B),    ControlProfiles.BUTTON_1 },
        { button(KeyEvent.KEYCODE_BUTTON_X),    ControlProfiles.BUTTON_2 },
        { button(KeyEvent.KEYCODE_BUTTON_Y),    ControlProfiles.BUTTON_3 },
    };

    /** Binding to slot, already resolved. Read for every event, so it is flat. */
    private final Map<Integer, Integer> effective;

    private PadMap(Map<Integer, Integer> effective) {
        this.effective = effective;
    }

    /** What every pad does until somebody says otherwise. */
    public static PadMap defaults() {
        Map<Integer, Integer> table = new HashMap<>();

        for (int[] entry : DEFAULTS) table.put(entry[0], entry[1]);

        return new PadMap(table);
    }

    /** The control this button drives, or {@link #NONE}. */
    public int slotFor(int keycode) {
        Integer slot = effective.get(button(keycode));
        return slot == null ? NONE : slot;
    }

    /** The control this axis drives when pushed this way, or {@link #NONE}. */
    public int slotFor(int axisId, int sign) {
        Integer slot = effective.get(axis(axisId, sign));
        return slot == null ? NONE : slot;
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*PadMapTest*'
```

Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/input/PadMap.java \
        app/src/test/java/dev/ldlab/zedex/input/PadMapTest.java
git commit -m "feat: PadMap, holding what Gamepad's switch decided

The table only, asserted against the switch that is still there, so the
next commit can delete it against a test rather than against a reading."
```

---

### Task 3: `Gamepad` reads the map; the `switch` goes

Behaviour must not change. That is the point of doing it as its own task: the
refactor lands proved before the feature is built on it.

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/input/Gamepad.java` (the `key`
  method, around lines 128-172; the `motion` method, around lines 245-274)

**Interfaces:**
- Consumes: `PadMap.defaults()`, `slotFor(int)`, `slotFor(int, int)`,
  `PadMap.NONE`.
- Produces: `Gamepad.Maps` (one method, `PadMap forDevice(int)`);
  `Gamepad.setMaps(Maps)`.

- [ ] **Step 1: Add the field and the setter**

In `Gamepad`, beside `keys`:

```java
    /**
     * Where a device's mapping comes from.
     *
     * A lookup and not one map, because two pads can be connected at once -
     * measured, with a Bluetooth pad and a USB pad both reporting GAMEPAD and
     * JOYSTICK - and each has its own. Every event carries the device id that
     * answers this, so nothing has to guess which pad is "the" pad.
     */
    public interface Maps {
        /** Never null: the defaults for a pad nobody has changed. */
        PadMap forDevice(int deviceId);
    }

    private Maps maps = deviceId -> PadMap.defaults();

    /**
     * A different pad, or the same pad remapped.
     *
     * Everything down is let go first, for the reason {@link #setHotkeys} ends
     * its hold: whatever was pressed was pressed under the old arrangement, and
     * its release would land on whatever now holds that button.
     */
    public void setMaps(Maps maps) {
        releaseAll();
        this.maps = maps;
    }
```

- [ ] **Step 2: Replace the `switch` in `key`**

Delete the whole `switch (event.getKeyCode())` block and put this in its place,
leaving everything above it - including the `hotkey(...)` call, which must stay
first - exactly as it is:

```java
        PadMap map = maps.forDevice(event.getDeviceId());
        int slot = map.slotFor(event.getKeyCode());

        if (slot == PadMap.NONE) {
            // Enter as itself, not as whatever Button 1 happens to hold: a game
            // that says PRESS ENTER wants Enter. Past the map on purpose - it
            // is not one of the eight controls, so there is no slot for it to
            // be, and nothing else a pad sends means anything here.
            if (event.getKeyCode() != KeyEvent.KEYCODE_BUTTON_START) return false;

            FuseNative.key(KeyEvent.KEYCODE_ENTER, pressed);
            return true;
        }

        // The four directions go through way() rather than send(): many pads
        // report the hat as both an axis and a D-pad key, and the two paths are
        // tracked apart and combined so that a release down one cannot cancel a
        // press that came down the other.
        if (slot <= FuseNative.JOYSTICK_DOWN) return way(slot, pressed);

        send(slot, pressed);
        return true;
```

`way(int index, boolean)` already indexes 0-3 in exactly the order
`JOYSTICK_LEFT`, `RIGHT`, `UP`, `DOWN`, so the slot is the index. Leave `way`
and `send` alone.

- [ ] **Step 3: Replace the four hard-coded axis comparisons in `motion`**

Delete these four lines:

```java
        fromAxes[0] = x <= -DEAD_ZONE;
        fromAxes[1] = x >= DEAD_ZONE;
        fromAxes[2] = y <= -DEAD_ZONE;
        fromAxes[3] = y >= DEAD_ZONE;
```

and put this in their place, keeping the `Mouse.enabled()` block above it
untouched - the mouse reads the stick as a stick, and how far it is pushed is
how fast the pointer goes, which four on-or-off directions cannot say:

```java
        // Every axis the device actually has, rather than the two pairs this
        // used to read: a pad whose directions arrive on some other axis can be
        // bound to it, and one that has no such axis simply reports none.
        for (int i = 0; i < fromAxes.length; i++) fromAxes[i] = false;

        InputDevice device = event.getDevice();
        if (device != null) {
            for (InputDevice.MotionRange range : device.getMotionRanges()) {
                int axisId = range.getAxis();
                float value = event.getAxisValue(axisId);

                if (Math.abs(value) < DEAD_ZONE) continue;

                int slot = maps.forDevice(event.getDeviceId())
                              .slotFor(axisId, value < 0 ? -1 : +1);
                if (slot != PadMap.NONE && slot <= FuseNative.JOYSTICK_DOWN) {
                    fromAxes[slot] = true;
                }
            }
        }
```

The local `x` and `y` are still wanted by the `Mouse` block above; leave the two
`axis(event, ...)` calls that produce them where they are.

- [ ] **Step 4: Build and run every JVM test**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest
```

Expected: PASS. Nothing here changes what any existing test asserts; a failure
means the refactor was not behaviour-neutral.

- [ ] **Step 5: Build the app**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. `Gamepad` now imports `android.view.InputDevice`,
which it already did, and nothing else new.

- [ ] **Step 6: Check on the device that nothing changed**

With the pad connected, open a game and confirm the D-pad and stick steer, A
fires, B, X and Y are the three key buttons, and Start is Enter. This is the
promise the README makes and the thing most easily broken by accident.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/input/Gamepad.java
git commit -m "refactor: Gamepad asks PadMap instead of switching

Behaviour-neutral by construction and by PadMapTest, which asserted the
table against this switch before it went. The axis path now walks the
device's own motion ranges rather than two hard-coded pairs, which is
what lets a later commit bind an axis nothing here watched."
```

---

### Task 4: Overrides and the conflict rule

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/input/PadMap.java`
- Modify: `app/src/test/java/dev/ldlab/zedex/input/PadMapTest.java`

**Interfaces:**
- Produces: `PadMap.Binding` (a small value with `isAxis`, `code`, `sign`);
  `PadMap with(int slot, Binding binding)`; `Binding bindingFor(int slot)`;
  `boolean isDefault(int slot)`.

- [ ] **Step 1: Write the failing tests**

Append to `PadMapTest`:

```java
    /**
     * Binding a button to a slot takes it off whatever else held it.
     *
     * The rule that surprises, so it is asserted from both ends: B drives Fire
     * now, and Button 1 - whose default B was - has nothing, rather than
     * quietly still answering to it. One press doing two things is the bug this
     * exists to prevent.
     */
    @Test
    public void aCaptureTakesItsButtonOffTheSlotThatHadIt() {
        PadMap map = PadMap.defaults()
                .with(FuseNative.JOYSTICK_FIRE,
                      PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B));

        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(PadMap.NONE, map.slotFor(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(PadMap.NONE, map.slotFor(KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals(ControlProfiles.BUTTON_2, map.slotFor(KeyEvent.KEYCODE_BUTTON_X));
        assertEquals(ControlProfiles.BUTTON_3, map.slotFor(KeyEvent.KEYCODE_BUTTON_Y));
    }

    /**
     * A captured direction is the only one.
     *
     * A direction has three defaults - the D-pad, the stick and the hat - and a
     * capture replaces all of them, because somebody who has just said "left is
     * this" does not mean "left is this as well".
     */
    @Test
    public void aCapturedDirectionReplacesEveryDefaultForIt() {
        PadMap map = PadMap.defaults()
                .with(FuseNative.JOYSTICK_LEFT,
                      PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_L1));

        assertEquals(FuseNative.JOYSTICK_LEFT, map.slotFor(KeyEvent.KEYCODE_BUTTON_L1));
        assertEquals(PadMap.NONE, map.slotFor(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(PadMap.NONE, map.slotFor(MotionEvent.AXIS_X, -1));
        assertEquals(PadMap.NONE, map.slotFor(MotionEvent.AXIS_HAT_X, -1));

        // And the other three directions are untouched.
        assertEquals(FuseNative.JOYSTICK_RIGHT, map.slotFor(MotionEvent.AXIS_X, +1));
        assertEquals(FuseNative.JOYSTICK_UP,    map.slotFor(KeyEvent.KEYCODE_DPAD_UP));
    }

    /** An axis binds as readily as a button, and takes the slot the same way. */
    @Test
    public void anAxisCanBeCaptured() {
        PadMap map = PadMap.defaults()
                .with(FuseNative.JOYSTICK_FIRE,
                      PadMap.Binding.axis(MotionEvent.AXIS_RZ, +1));

        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(MotionEvent.AXIS_RZ, +1));
        assertEquals(PadMap.NONE, map.slotFor(MotionEvent.AXIS_RZ, -1));
        assertEquals(PadMap.NONE, map.slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    /** Two captures in a row both hold, and the second does not undo the first. */
    @Test
    public void capturesAccumulate() {
        PadMap map = PadMap.defaults()
                .with(FuseNative.JOYSTICK_FIRE,
                      PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B))
                .with(ControlProfiles.BUTTON_1,
                      PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_A));

        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(ControlProfiles.BUTTON_1, map.slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    /** What the screen draws in a row, and whether it is a choice or a default. */
    @Test
    public void aRowCanSayWhatItIsOnAndWhetherItWasChosen() {
        PadMap map = PadMap.defaults();

        assertEquals(KeyEvent.KEYCODE_BUTTON_A,
                     map.bindingFor(FuseNative.JOYSTICK_FIRE).code);
        assertTrue(map.isDefault(FuseNative.JOYSTICK_FIRE));

        PadMap changed = map.with(FuseNative.JOYSTICK_FIRE,
                                  PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B));

        assertEquals(KeyEvent.KEYCODE_BUTTON_B,
                     changed.bindingFor(FuseNative.JOYSTICK_FIRE).code);
        assertFalse(changed.isDefault(FuseNative.JOYSTICK_FIRE));

        // A slot whose binding was taken away has none to show.
        assertNull(changed.bindingFor(ControlProfiles.BUTTON_1));
    }
```

Add these imports at the top of the test file:

```java
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
```

- [ ] **Step 2: Run and watch it fail**

Expected: compilation failure, `cannot find symbol: method with`.

- [ ] **Step 3: Add `Binding`, `with`, `bindingFor` and `isDefault`**

Add to `PadMap`, and change the private constructor to carry the overrides:

```java
    /** A button, or one direction of one axis. */
    public static final class Binding {
        public final boolean isAxis;

        /** The keycode, or the axis id. */
        public final int code;

        /** -1 or +1 for an axis; 0 for a button. */
        public final int sign;

        private Binding(boolean isAxis, int code, int sign) {
            this.isAxis = isAxis;
            this.code = code;
            this.sign = sign;
        }

        public static Binding button(int keycode) {
            return new Binding(false, keycode, 0);
        }

        public static Binding axis(int axisId, int sign) {
            return new Binding(true, axisId, sign < 0 ? -1 : +1);
        }

        int key() {
            return isAxis ? PadMap.axis(code, sign) : PadMap.button(code);
        }
    }

    /** What was captured, slot to binding. Empty on a pad nobody has changed. */
    private final Map<Integer, Binding> chosen;
```

Replace `defaults()` and the constructor with:

```java
    private PadMap(Map<Integer, Binding> chosen) {
        this.chosen = chosen;
        this.effective = resolve(chosen);
    }

    public static PadMap defaults() {
        return new PadMap(new HashMap<>());
    }

    /**
     * The defaults, then each capture laid over them.
     *
     * Two removals per capture, and both are needed. Every entry pointing at
     * the slot goes, or a captured Left would be a third Left beside the stick
     * and the hat rather than instead of them. The entry keyed by the binding
     * goes, or B would be Fire and Button 1 at once and one press would do two
     * things.
     */
    private static Map<Integer, Integer> resolve(Map<Integer, Binding> chosen) {
        Map<Integer, Integer> table = new HashMap<>();

        for (int[] entry : DEFAULTS) table.put(entry[0], entry[1]);

        for (Map.Entry<Integer, Binding> capture : chosen.entrySet()) {
            int slot = capture.getKey();
            int binding = capture.getValue().key();

            table.values().removeIf(where -> where == slot);
            table.remove(binding);
            table.put(binding, slot);
        }

        return table;
    }

    /** This map with one slot moved to a binding, and that binding taken off
     *  whatever else held it. The original is unchanged. */
    public PadMap with(int slot, Binding binding) {
        Map<Integer, Binding> next = new HashMap<>(chosen);
        next.put(slot, binding);
        return new PadMap(next);
    }

    /** What drives this slot, for the screen to draw. Null when nothing does. */
    public Binding bindingFor(int slot) {
        Binding captured = chosen.get(slot);
        if (captured != null) return captured;

        for (Map.Entry<Integer, Integer> entry : effective.entrySet()) {
            if (entry.getValue() == slot) return fromKey(entry.getKey());
        }
        return null;
    }

    /** Whether this slot is still on what it was born with. */
    public boolean isDefault(int slot) {
        return !chosen.containsKey(slot);
    }

    private static Binding fromKey(int key) {
        if (key < AXIS_BASE) return Binding.button(key);

        int packed = key - AXIS_BASE;
        return Binding.axis(packed / 2, (packed % 2) == 0 ? -1 : +1);
    }
```

Note `chosen.entrySet()` is iterated to build `effective`, so **`with` must keep
insertion order irrelevant** - it is: each capture's two removals and one insert
are independent of the others, because a slot appears at most once in `chosen`
and a binding captured twice would be two captures of two different slots, the
second of which removes the first's entry. `capturesAccumulate` asserts that.

- [ ] **Step 4: Run and watch it pass**

Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/input/PadMap.java \
        app/src/test/java/dev/ldlab/zedex/input/PadMapTest.java
git commit -m "feat: captures over the defaults, and the rule that a binding drives one slot"
```

---

### Task 5: Storage, one mapping per pad

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/input/PadMap.java` (JSON in and out)
- Modify: `app/src/main/java/dev/ldlab/zedex/storage/Prefs.java` (the key)
- Create: `app/src/main/java/dev/ldlab/zedex/input/PadMaps.java` (the store)
- Modify: `app/src/test/java/dev/ldlab/zedex/input/PadMapTest.java`
- Create: `app/src/test/java/dev/ldlab/zedex/input/PadMapsTest.java`

**Interfaces:**
- Consumes: `dev.ldlab.zedex.FakePreferences` (already in `app/src/test`).
- Produces: `PadMap.fromJson(String)`, `String PadMap.toJson()`;
  `PadMaps.KEY` = `"padMappings"`; `PadMaps.keyFor(InputDevice)`;
  `PadMaps.load(SharedPreferences, String deviceKey)`;
  `PadMaps.save(SharedPreferences, String deviceKey, String name, PadMap)`;
  `PadMaps.forget(SharedPreferences, String deviceKey)`;
  `PadMaps.known(SharedPreferences)` returning `Map<String, String>` of device
  key to name.

- [ ] **Step 1: Write the failing tests for the JSON**

Append to `PadMapTest`:

```java
    /** A capture survives being written down and read back. */
    @Test
    public void aMapRoundTripsThroughJson() {
        PadMap map = PadMap.defaults()
                .with(FuseNative.JOYSTICK_FIRE,
                      PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B))
                .with(FuseNative.JOYSTICK_LEFT,
                      PadMap.Binding.axis(MotionEvent.AXIS_RZ, -1));

        PadMap back = PadMap.fromJson(map.toJson());

        assertEquals(FuseNative.JOYSTICK_FIRE, back.slotFor(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(FuseNative.JOYSTICK_LEFT, back.slotFor(MotionEvent.AXIS_RZ, -1));
        assertEquals(PadMap.NONE, back.slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    /**
     * One bad row does not cost the others.
     *
     * A mapping is worth more than the row somebody's future version wrote
     * into it: an unknown slot name and an unparseable binding are both
     * skipped, and what was understood still applies.
     */
    @Test
    public void whatCannotBeUnderstoodIsSkippedAndTheRestStands() {
        PadMap map = PadMap.fromJson(
                "{\"FIRE\":\"k97\",\"WARP_DRIVE\":\"k42\",\"BUTTON_1\":\"nonsense\"}");

        // FIRE was understood: B (97) is Fire, and A has lost it the way any
        // capture takes a binding away from the slot that held it.
        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(PadMap.NONE, map.slotFor(KeyEvent.KEYCODE_BUTTON_A));

        // WARP_DRIVE is not a slot and BUTTON_1's value is not a binding, so
        // neither was applied - and the rest of the defaults are untouched.
        assertEquals(ControlProfiles.BUTTON_2, map.slotFor(KeyEvent.KEYCODE_BUTTON_X));
        assertEquals(ControlProfiles.BUTTON_3, map.slotFor(KeyEvent.KEYCODE_BUTTON_Y));
    }

    /** Malformed JSON is a pad with no mapping, not a pad with no controls. */
    @Test
    public void malformedJsonFallsBackToTheDefaults() {
        PadMap map = PadMap.fromJson("{not json");

        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(ControlProfiles.BUTTON_1, map.slotFor(KeyEvent.KEYCODE_BUTTON_B));
    }
```

- [ ] **Step 2: Run and watch it fail**

Expected: `cannot find symbol: method fromJson`.

- [ ] **Step 3: Add the JSON to `PadMap`**

```java
    /**
     * The slot names as stored.
     *
     * By name and not by index, for the reason Hotkeys gives about its own
     * bindings: the constants can be reordered without spoiling what anyone
     * saved, and a name that means nothing here is skipped rather than shifting
     * everything after it.
     */
    private static final String[] NAMES = new String[ControlProfiles.SLOTS];

    static {
        NAMES[FuseNative.JOYSTICK_LEFT]  = "LEFT";
        NAMES[FuseNative.JOYSTICK_RIGHT] = "RIGHT";
        NAMES[FuseNative.JOYSTICK_UP]    = "UP";
        NAMES[FuseNative.JOYSTICK_DOWN]  = "DOWN";
        NAMES[FuseNative.JOYSTICK_FIRE]  = "FIRE";
        NAMES[ControlProfiles.BUTTON_1]  = "BUTTON_1";
        NAMES[ControlProfiles.BUTTON_2]  = "BUTTON_2";
        NAMES[ControlProfiles.BUTTON_3]  = "BUTTON_3";
    }

    /**
     * A binding as a short string: {@code k96} for a button, {@code a15-} for
     * an axis pushed one way.
     *
     * A string and not a tagged integer because it has two shapes, and because
     * this ends up in a bug report - which is where "my pad is wrong" gets
     * answered, and a report nobody can read answers nothing.
     */
    private static String encode(Binding binding) {
        if (!binding.isAxis) return "k" + binding.code;
        return "a" + binding.code + (binding.sign < 0 ? "-" : "+");
    }

    private static Binding decode(String text) {
        if (text == null || text.length() < 2) return null;

        try {
            if (text.charAt(0) == 'k') {
                return Binding.button(Integer.parseInt(text.substring(1)));
            }
            if (text.charAt(0) == 'a') {
                char sign = text.charAt(text.length() - 1);
                if (sign != '-' && sign != '+') return null;

                int axisId = Integer.parseInt(text.substring(1, text.length() - 1));
                return Binding.axis(axisId, sign == '-' ? -1 : +1);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    public String toJson() {
        JSONObject object = new JSONObject();

        for (Map.Entry<Integer, Binding> entry : chosen.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= NAMES.length) continue;

            try {
                object.put(NAMES[slot], encode(entry.getValue()));
            } catch (JSONException e) {
                // A slot that will not serialise is one row of a mapping. The
                // rest is still worth writing.
            }
        }

        return object.toString();
    }

    /** A stored mapping, or the defaults where it cannot be read. */
    public static PadMap fromJson(String stored) {
        if (stored == null || stored.isEmpty()) return defaults();

        Map<Integer, Binding> chosen = new HashMap<>();

        try {
            JSONObject object = new JSONObject(stored);

            for (int slot = 0; slot < NAMES.length; slot++) {
                String text = object.optString(NAMES[slot], null);
                if (text == null) continue;

                Binding binding = decode(text);
                if (binding != null) chosen.put(slot, binding);
            }
        } catch (JSONException e) {
            // A mapping that will not parse is a pad with no mapping, which is
            // a pad that works - not a pad with no controls.
            return defaults();
        }

        return new PadMap(chosen);
    }
```

Add the imports `org.json.JSONException` and `org.json.JSONObject`.

- [ ] **Step 4: Run and watch the JSON tests pass**

Expected: PASS, 12 tests.

- [ ] **Step 5: Write the failing test for the store**

`app/src/test/java/dev/ldlab/zedex/input/PadMapsTest.java`:

```java
package dev.ldlab.zedex.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import dev.ldlab.zedex.FakePreferences;
import dev.ldlab.zedex.FuseNative;

import org.junit.Test;

/**
 * One mapping per pad, which is the whole reason this is a store and not a
 * preference.
 *
 * The case it exists for is a handheld with a built-in pad and a Bluetooth pad
 * beside it: a single mapping would mean correcting one breaks the other, and
 * the person would never find out which.
 */
public class PadMapsTest {

    private static final String ONE = "descriptor-one";
    private static final String TWO = "descriptor-two";

    @Test
    public void aPadNobodyHasTouchedGetsTheDefaults() {
        PadMap map = PadMaps.load(new FakePreferences(), ONE);

        assertEquals(FuseNative.JOYSTICK_FIRE, map.slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    @Test
    public void aSavedMappingComesBack() {
        FakePreferences preferences = new FakePreferences();

        PadMaps.save(preferences, ONE, "8BitDo SN30 Pro",
                     PadMap.defaults().with(FuseNative.JOYSTICK_FIRE,
                             PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B)));

        assertEquals(FuseNative.JOYSTICK_FIRE,
                     PadMaps.load(preferences, ONE).slotFor(KeyEvent.KEYCODE_BUTTON_B));
    }

    @Test
    public void twoPadsKeepSeparateMappings() {
        FakePreferences preferences = new FakePreferences();

        PadMaps.save(preferences, ONE, "one",
                     PadMap.defaults().with(FuseNative.JOYSTICK_FIRE,
                             PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B)));

        assertEquals(FuseNative.JOYSTICK_FIRE,
                     PadMaps.load(preferences, ONE).slotFor(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(FuseNative.JOYSTICK_FIRE,
                     PadMaps.load(preferences, TWO).slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    @Test
    public void aForgottenPadIsBackOnTheDefaults() {
        FakePreferences preferences = new FakePreferences();

        PadMaps.save(preferences, ONE, "one",
                     PadMap.defaults().with(FuseNative.JOYSTICK_FIRE,
                             PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B)));
        PadMaps.forget(preferences, ONE);

        assertEquals(FuseNative.JOYSTICK_FIRE,
                     PadMaps.load(preferences, ONE).slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    /** The picker lists a pad that is not plugged in, so the name is stored. */
    @Test
    public void aStoredPadIsListedByName() {
        FakePreferences preferences = new FakePreferences();

        PadMaps.save(preferences, ONE, "8BitDo SN30 Pro", PadMap.defaults()
                .with(FuseNative.JOYSTICK_FIRE,
                      PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B)));

        assertEquals("8BitDo SN30 Pro", PadMaps.known(preferences).get(ONE));
    }

    /** A store that will not parse is every pad on its defaults, and no crash. */
    @Test
    public void malformedStorageIsSurvived() {
        FakePreferences preferences = new FakePreferences();
        preferences.edit().putString(PadMaps.KEY, "{not json").apply();

        assertEquals(FuseNative.JOYSTICK_FIRE,
                     PadMaps.load(preferences, ONE).slotFor(KeyEvent.KEYCODE_BUTTON_A));
        assertTrue(PadMaps.known(preferences).isEmpty());
    }
}
```

- [ ] **Step 6: Write `PadMaps`**

`app/src/main/java/dev/ldlab/zedex/input/PadMaps.java`:

```java
package dev.ldlab.zedex.input;

import android.content.SharedPreferences;
import android.view.InputDevice;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Every pad's mapping, kept apart.
 *
 * Per pad rather than one for all of them because the case this feature exists
 * for is a handheld with a built-in controller and a Bluetooth one beside it:
 * a single mapping means correcting the second breaks the first, silently, and
 * the person finds out in the middle of a game.
 *
 * The name is stored beside the mapping so that a pad which is not connected
 * can still be listed and corrected - and so that a bug report says which pad
 * it was. The device key is not in the report; see Diagnostics.
 */
public final class PadMaps {

    private PadMaps() {
    }

    /** A JSON object of device key to that pad's mapping. A String. */
    public static final String KEY = "padMappings";

    private static final String NAME = "name";

    /**
     * How a pad is told from another pad.
     *
     * The name is in the fallback and not optional: measured on a
     * GameSir-Cyclone Pro, one physical pad is three input devices - the pad,
     * a consumer-control endpoint and a keyboard - sharing one vendor, one
     * product and one Bluetooth address, and differing only in the name. A
     * fallback of vendor:product would hand all three one mapping.
     *
     * Measured: the descriptor survived a power cycle, a full forget-and-
     * re-pair and a reboot, on a Bluetooth pad and a USB pad both, while every
     * kernel id moved in three of the four readings. See the spec's
     * "The device key".
     */
    public static String keyFor(InputDevice device) {
        String descriptor = device.getDescriptor();

        return descriptor == null || descriptor.isEmpty()
                ? device.getVendorId() + ":" + device.getProductId() + ":" + device.getName()
                : descriptor;
    }

    private static JSONObject all(SharedPreferences preferences) {
        String stored = preferences.getString(KEY, null);
        if (stored == null || stored.isEmpty()) return new JSONObject();

        try {
            return new JSONObject(stored);
        } catch (JSONException e) {
            // Every pad back on its defaults, which is a working app. Kept
            // rather than cleared: a later version may understand it.
            return new JSONObject();
        }
    }

    /** This pad's mapping, or the defaults. */
    public static PadMap load(SharedPreferences preferences, String deviceKey) {
        JSONObject entry = all(preferences).optJSONObject(deviceKey);
        return entry == null ? PadMap.defaults() : PadMap.fromJson(entry.toString());
    }

    public static void save(SharedPreferences preferences, String deviceKey,
                            String name, PadMap map) {
        JSONObject everything = all(preferences);

        try {
            JSONObject entry = new JSONObject(map.toJson());
            entry.put(NAME, name);
            everything.put(deviceKey, entry);
        } catch (JSONException e) {
            return;
        }

        preferences.edit().putString(KEY, everything.toString()).apply();
    }

    /** Back to the defaults for one pad, leaving the others alone. */
    public static void forget(SharedPreferences preferences, String deviceKey) {
        JSONObject everything = all(preferences);
        everything.remove(deviceKey);
        preferences.edit().putString(KEY, everything.toString()).apply();
    }

    /** Every pad with a stored mapping, device key to name, for the picker. */
    public static Map<String, String> known(SharedPreferences preferences) {
        Map<String, String> names = new HashMap<>();
        JSONObject everything = all(preferences);

        for (Iterator<String> keys = everything.keys(); keys.hasNext(); ) {
            String deviceKey = keys.next();
            JSONObject entry = everything.optJSONObject(deviceKey);
            if (entry == null) continue;

            names.put(deviceKey, entry.optString(NAME, deviceKey));
        }

        return names;
    }
}
```

`PadMap.fromJson` ignores the `name` member because it is not a slot name, which
is the same skipping rule an unknown slot gets.

- [ ] **Step 7: Add the key to `Prefs`**

In `app/src/main/java/dev/ldlab/zedex/storage/Prefs.java`, beside the other
control keys:

```java
    /** Every pad's control mapping. A JSON String; see input/PadMaps. */
    public static final String KEY_PAD_MAPPINGS = PadMaps.KEY;
```

If `Prefs` does not import `input`, declare the literal `"padMappings"` there
instead and have `PadMaps.KEY` read `Prefs.KEY_PAD_MAPPINGS`, so the string
exists once.

- [ ] **Step 8: Run the tests and the preference check**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*PadMap*'
scripts/check-prefs.py
```

Expected: PASS, 18 tests; `check-prefs.py` exits 0.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/input/PadMap.java \
        app/src/main/java/dev/ldlab/zedex/input/PadMaps.java \
        app/src/main/java/dev/ldlab/zedex/storage/Prefs.java \
        app/src/test/java/dev/ldlab/zedex/input/PadMapTest.java \
        app/src/test/java/dev/ldlab/zedex/input/PadMapsTest.java
git commit -m "feat: a stored mapping per pad"
```

---

### Task 6: The machine resolves each pad's mapping

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/input/PadMapCache.java`
- Create: `app/src/test/java/dev/ldlab/zedex/input/PadMapCacheTest.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/EmulatorActivity.java` (wherever
  `setHotkeys` is called - `grep -n setHotkeys` to find every site)

**Interfaces:**
- Consumes: `Gamepad.Maps`, `PadMaps.load`, `PadMaps.keyFor`,
  `Gamepad.setMaps`.
- Produces: `PadMapCache implements Gamepad.Maps`, constructed with a
  `SharedPreferences` and a `PadMapCache.Devices` lookup;
  `PadMapCache.forget()` to drop what it has cached.

**Why a cache and not a call:** `forDevice` runs for every button press and
every motion event. Resolving a device and parsing JSON per press would be
absurd, and holding one map per pad is three lines.

**Why an indirection for `InputDevice`:** `InputDevice.getDevice` is static and
answers null under the stub `android.jar`, so a cache that called it directly
would be untestable for the same reason `SparseArray` is. One interface, and the
test supplies its own devices.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/ldlab/zedex/input/PadMapCacheTest.java`:

```java
package dev.ldlab.zedex.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.view.KeyEvent;

import dev.ldlab.zedex.FakePreferences;
import dev.ldlab.zedex.FuseNative;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Two pads at once, each on its own mapping.
 *
 * Not hypothetical: measured on a Realme RMX5061 with a GameSir-Cyclone Pro on
 * Bluetooth and an X-Box 360 pad on USB, both reporting GAMEPAD and JOYSTICK at
 * the same time. An earlier draft of this took "the first pad in the device
 * list", which would have applied one pad's mapping to the other's buttons.
 */
public class PadMapCacheTest {

    private static final String GAMESIR = "f5c2919f";
    private static final String XBOX = "dc4619ee";

    /** The device table an event's id is resolved against. */
    private static final class Devices implements PadMapCache.Devices {
        final Map<Integer, String> keys = new HashMap<>();
        int lookups;

        @Override
        public String keyFor(int deviceId) {
            lookups++;
            return keys.get(deviceId);
        }
    }

    @Test
    public void eachPadGetsItsOwnMapping() {
        FakePreferences preferences = new FakePreferences();

        PadMaps.save(preferences, GAMESIR, "GameSir-Cyclone Pro",
                     PadMap.defaults().with(FuseNative.JOYSTICK_FIRE,
                             PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B)));

        Devices devices = new Devices();
        devices.keys.put(19, GAMESIR);
        devices.keys.put(22, XBOX);

        PadMapCache cache = new PadMapCache(preferences, devices);

        // The GameSir has Fire on B; the X-Box, untouched, still has it on A.
        assertEquals(FuseNative.JOYSTICK_FIRE,
                     cache.forDevice(19).slotFor(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(PadMap.NONE,
                     cache.forDevice(19).slotFor(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(FuseNative.JOYSTICK_FIRE,
                     cache.forDevice(22).slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    /** A device that has gone away drives the defaults, not nothing. */
    @Test
    public void anUnknownDeviceGetsTheDefaults() {
        PadMapCache cache = new PadMapCache(new FakePreferences(), new Devices());

        assertEquals(FuseNative.JOYSTICK_FIRE,
                     cache.forDevice(99).slotFor(KeyEvent.KEYCODE_BUTTON_A));
    }

    /** Asked twice, resolved once: this runs for every button press. */
    @Test
    public void aMappingIsResolvedOncePerDevice() {
        Devices devices = new Devices();
        devices.keys.put(19, GAMESIR);

        PadMapCache cache = new PadMapCache(new FakePreferences(), devices);

        assertSame(cache.forDevice(19), cache.forDevice(19));
        assertEquals(1, devices.lookups);
    }

    /** An edit is seen after forget(), which is what the editor calls. */
    @Test
    public void forgettingPicksUpAnEdit() {
        FakePreferences preferences = new FakePreferences();
        Devices devices = new Devices();
        devices.keys.put(19, GAMESIR);

        PadMapCache cache = new PadMapCache(preferences, devices);
        assertEquals(FuseNative.JOYSTICK_FIRE,
                     cache.forDevice(19).slotFor(KeyEvent.KEYCODE_BUTTON_A));

        PadMaps.save(preferences, GAMESIR, "GameSir-Cyclone Pro",
                     PadMap.defaults().with(FuseNative.JOYSTICK_FIRE,
                             PadMap.Binding.button(KeyEvent.KEYCODE_BUTTON_B)));
        cache.forget();

        assertEquals(FuseNative.JOYSTICK_FIRE,
                     cache.forDevice(19).slotFor(KeyEvent.KEYCODE_BUTTON_B));
    }
}
```

- [ ] **Step 2: Run and watch it fail**

Expected: `cannot find symbol: class PadMapCache`.

- [ ] **Step 3: Write `PadMapCache`**

```java
package dev.ldlab.zedex.input;

import android.content.SharedPreferences;
import android.view.InputDevice;

import java.util.HashMap;
import java.util.Map;

/**
 * Which mapping belongs to the device an event came from.
 *
 * Per device and not per app, because two pads can be connected at once -
 * measured, a Bluetooth pad and a USB pad both reporting GAMEPAD and JOYSTICK -
 * and each carries its own. Every event already says which device it came from,
 * so there is nothing to guess.
 *
 * Cached because this answers a question asked for every button press and every
 * motion event, and the honest answer costs a device lookup and a JSON parse.
 *
 * {@link Devices} exists so this is testable: {@code InputDevice.getDevice} is
 * static and answers null under the stub android.jar, so anything calling it
 * directly is out of reach on the JVM tier - the same trap as SparseArray, one
 * layer up.
 */
public final class PadMapCache implements Gamepad.Maps {

    /** A device id to the key its mapping is stored under. */
    public interface Devices {
        /** Null when there is no such device, or it is not a pad. */
        String keyFor(int deviceId);
    }

    /** The real one: Android's device table. */
    public static final Devices ANDROID = deviceId -> {
        InputDevice device = InputDevice.getDevice(deviceId);
        return device == null ? null : PadMaps.keyFor(device);
    };

    private final SharedPreferences preferences;
    private final Devices devices;
    private final Map<Integer, PadMap> resolved = new HashMap<>();

    public PadMapCache(SharedPreferences preferences, Devices devices) {
        this.preferences = preferences;
        this.devices = devices;
    }

    @Override
    public PadMap forDevice(int deviceId) {
        PadMap known = resolved.get(deviceId);
        if (known != null) return known;

        String key = devices.keyFor(deviceId);

        // A device that has gone away, or was never a pad, drives the defaults
        // rather than nothing: a pad unplugged mid-press still has to be able
        // to let go of what it was holding.
        PadMap map = key == null ? PadMap.defaults() : PadMaps.load(preferences, key);

        resolved.put(deviceId, map);
        return map;
    }

    /** Drop everything cached: a pad was added or removed, or one was edited. */
    public void forget() {
        resolved.clear();
    }
}
```

- [ ] **Step 4: Run and watch it pass**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*PadMap*'
```

Expected: PASS, 22 tests.

- [ ] **Step 5: Hand it to the pad**

In `EmulatorActivity`, build one cache in `onCreate` beside the other
collaborators - **not as a field initialiser**, which runs before `preferences`
is assigned and hands it null:

```java
        padMaps = new PadMapCache(preferences, PadMapCache.ANDROID);
        gamepad.setMaps(padMaps);
```

Then `padMaps.forget()` wherever the hotkeys are already reloaded
(`grep -n setHotkeys`), and from an `InputManager.InputDeviceListener`
registered in `onResume` and unregistered in `onPause` - both
`onInputDeviceAdded` and `onInputDeviceRemoved`, because a device id is reused
and a stale entry would be another pad's mapping.

Note `setMaps` is called once and the cache is mutable, so nothing needs
re-setting; `forget()` is the whole of the invalidation.

- [ ] **Step 6: Build and check on the device**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Nothing is captured yet, so both pads must behave exactly as in Task 3 - with
both connected at once, and with each on its own. Plumbing that changes
behaviour is plumbing that is wrong.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/input/PadMapCache.java         app/src/test/java/dev/ldlab/zedex/input/PadMapCacheTest.java         app/src/main/java/dev/ldlab/zedex/EmulatorActivity.java
git commit -m "feat: each connected pad resolves its own mapping"
```

---

### Task 7: Words for the eight slots

**Decision taken here, which the spec left open:** the eight get real string
resources, and `ControlProfiles.slotName` is changed to read them. Its current
returns are hardcoded English, which is why the profile editor's rows read in
English on a Polish phone; reusing it would have spread that to a second screen,
and fixing it fixes both.

`slotName` is called from a context-free static today. Give it a `Context`
parameter and update its callers.

**Files:**
- Modify: `app/src/main/res/values/strings.xml` and the eight translations
- Modify: `app/src/main/java/dev/ldlab/zedex/input/ControlProfiles.java`
- Modify: every caller of `slotName` (`grep -rn "slotName"`)

- [ ] **Step 1: Add the strings**

To `app/src/main/res/values/strings.xml`:

```xml
    <!-- The eight controls a pad or the on-screen joystick can drive, named in
         the key profile editor and in the pad mapping rows. -->
    <string name="slot_left">Left</string>
    <string name="slot_right">Right</string>
    <string name="slot_up">Up</string>
    <string name="slot_down">Down</string>
    <string name="slot_fire">Fire</string>
    <string name="slot_button_1">Button 1</string>
    <string name="slot_button_2">Button 2</string>
    <string name="slot_button_3">Button 3</string>
```

- [ ] **Step 2: Translate them into the eight**

`values-de`, `values-es`, `values-fr`, `values-it`, `values-pl`, `values-cs`,
`values-ru`, `values-uk`. These are single words and a translation is better
than an English fallback, but **do not guess**: if any language is uncertain,
leave that file alone. `check-strings.py` counts a missing key rather than
failing it, precisely so a translation can be in progress.

- [ ] **Step 3: Read them in `ControlProfiles`**

```java
    /** What a slot is called in the editor, in slot order. */
    public static String slotName(Context context, int slot) {
        switch (slot) {
            case FuseNative.JOYSTICK_LEFT:  return context.getString(R.string.slot_left);
            case FuseNative.JOYSTICK_RIGHT: return context.getString(R.string.slot_right);
            case FuseNative.JOYSTICK_UP:    return context.getString(R.string.slot_up);
            case FuseNative.JOYSTICK_DOWN:  return context.getString(R.string.slot_down);
            case FuseNative.JOYSTICK_FIRE:  return context.getString(R.string.slot_fire);
            case BUTTON_1:                  return context.getString(R.string.slot_button_1);
            case BUTTON_2:                  return context.getString(R.string.slot_button_2);
            default:                        return context.getString(R.string.slot_button_3);
        }
    }
```

- [ ] **Step 4: Fix the callers**

```sh
grep -rn "slotName" app/src/main app/src/test app/src/androidTest
```

Each gets the nearest `Context`. A test asserting on the English word will now
need one; if that is awkward, assert on the slot rather than the word.

- [ ] **Step 5: Check and build**

```sh
scripts/check-strings.py
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug testDebugUnitTest
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/values*/strings.xml \
        app/src/main/java/dev/ldlab/zedex/input/ControlProfiles.java
git commit -m "fix: the eight control names are resources, not English constants

The profile editor has been showing Left, Right, Fire in English on every
phone whatever its language. The mapping rows would have been a second
screen doing it."
```

---

### Task 8: The mapping rows on `GamepadActivity`, buttons only

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/GamepadActivity.java`
- Modify: `app/src/main/res/values/strings.xml` and the eight translations

- [ ] **Step 1: Read what is already there**

```sh
sed -n '1,263p' app/src/main/java/dev/ldlab/zedex/screen/GamepadActivity.java
```

It already builds a scrolling column of rows, captures a press through a
dialog, refuses to capture with no pad connected, and rewrites every row after a
capture. All of that is reused; nothing about it is copied.

- [ ] **Step 2: Add the strings**

```xml
    <string name="gamepad_machine_section">The machine</string>
    <string name="gamepad_app_section">The app</string>
    <string name="gamepad_default_marker">%1$s (default)</string>
    <string name="gamepad_reset_pad">Reset this pad</string>
    <string name="gamepad_press_control">Press a button for %1$s</string>
```

`%1$s` in both formatted strings, in every language - `check-strings.py` fails a
disagreeing specifier, which is a `ClassCastException` only a non-English reader
would ever see.

- [ ] **Step 3: Build the section**

Above the existing hotkey rows, a section heading and eight rows, one per slot
in slot order. Each row's label is `ControlProfiles.slotName(this, slot)`; its
value is the binding, drawn from `PadMap.bindingFor(slot)` through
`KeyEvent.keyCodeToString`, and marked with `gamepad_default_marker` when
`PadMap.isDefault(slot)` says it was never chosen. A slot with no binding shows
the same "nothing" the hotkey rows already use for an unbound action.

The machine section goes first: it is what most people come to this screen for.

- [ ] **Step 4: Capture into a slot**

Reuse the existing capture dialog. On a `KeyEvent` while a slot is waiting:

```java
        PadMap updated = map.with(slot, PadMap.Binding.button(event.getKeyCode()));
        PadMaps.save(preferences, deviceKey, deviceName, updated);
        map = updated;
        showBindings();

The machine's own `PadMapCache` is in another activity and does not see this.
`EmulatorActivity` already calls `padMaps.forget()` where it reloads the
hotkeys, and it reloads those on resume - so coming back from this screen picks
the edit up. Confirm that on the device rather than assuming it: a capture that
only takes effect after a restart is the "a setting has to be applied as well as
stored" bug wearing a new hat.
```

`showBindings()` must redraw **every** row, not the one captured - the conflict
rule means a capture changes another row, and a screen that does not show that
having happened is the single most confusing thing this feature can do.

- [ ] **Step 5: Add *Reset this pad***

A row at the foot of the section calling `PadMaps.forget`, then reloading and
redrawing.

- [ ] **Step 6: Build, install, and use it**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

With the pad connected: swap Fire onto B, confirm the Button 1 row goes blank in
the same redraw, open a game, confirm B fires. Reset, confirm A fires again.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/screen/GamepadActivity.java \
        app/src/main/res/values*/strings.xml
git commit -m "feat: bind the machine's eight controls from the gamepad screen"
```

---

### Task 9: Capturing an axis

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/GamepadActivity.java`

- [ ] **Step 1: Add `onGenericMotionEvent`**

```java
    /** Past this a push is a capture. Well above Gamepad's own 0.4, because a
     *  binding is meant, and a play threshold would take a lean. */
    private static final float CAPTURE = 0.7f;

    /** And under this before the next one will arm - a worn stick rests off
     *  centre, and without this it binds itself the moment a row is tapped. */
    private static final float RELEASED = 0.2f;

    private boolean axisArmed = true;

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (capturingSlot < 0 || !Gamepad.isFrom(event)) {
            return super.onGenericMotionEvent(event);
        }

        InputDevice device = event.getDevice();
        if (device == null) return true;

        int furthest = -1;
        float most = 0f;

        for (InputDevice.MotionRange range : device.getMotionRanges()) {
            float value = event.getAxisValue(range.getAxis());
            if (Math.abs(value) > Math.abs(most)) {
                most = value;
                furthest = range.getAxis();
            }
        }

        if (Math.abs(most) < RELEASED) {
            axisArmed = true;
            return true;
        }

        if (!axisArmed || Math.abs(most) < CAPTURE || furthest < 0) return true;

        axisArmed = false;
        bind(PadMap.Binding.axis(furthest, most < 0 ? -1 : +1));
        return true;
    }
```

where `bind(Binding)` is the save-and-redraw from Task 8 Step 4, pulled out so
the key path and the axis path share it, and `capturingSlot` is the slot
awaiting a capture or `-1`.

- [ ] **Step 2: Arm on opening a row**

Set `axisArmed = false` when a row starts waiting, so the stick has to come back
to rest before it can bind. Otherwise a stick already off centre binds instantly.

- [ ] **Step 3: Say both are accepted**

Change `gamepad_press_control` to `Press a button or push a direction for %1$s`
in every language.

- [ ] **Step 4: Build, install, and bind an axis**

Bind Left to the right stick, confirm in a game that the right stick steers left
and the left stick no longer does - a captured direction replaces every default
for it, which is `aCapturedDirectionReplacesEveryDefaultForIt` seen from the
outside.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/screen/GamepadActivity.java \
        app/src/main/res/values*/strings.xml
git commit -m "feat: a direction can be captured from an axis"
```

---

### Task 10: The pad picker, and the report

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/GamepadActivity.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/feedback/Diagnostics.java`
- Modify: `app/src/main/res/values/strings.xml` and the eight translations

- [ ] **Step 1: The picker**

At the head of the machine section, a row naming the pad being edited. Tapping
it lists the connected pad and every entry in `PadMaps.known(preferences)`, so a
mapping can be corrected with the pad away. With one pad and nothing stored,
draw no picker at all - a chooser with one entry is a row that teaches you not
to press it.

- [ ] **Step 2: The report**

In `Diagnostics`, beside the input devices it already lists, add each pad's
**name** and its mapping.

**Not the device key.** `DiagnosticsTest` asserts the report carries no
identifier, and Android's descriptor is MAC-derived on some devices. Include it
only if Task 1 measured two pads of the same model producing the same string,
and say in the commit that it did.

- [ ] **Step 3: Run the diagnostics test**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*Diagnostics*'
```

Expected: PASS. A failure here means an identifier reached the report.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/ldlab/zedex/screen/GamepadActivity.java \
        app/src/main/java/dev/ldlab/zedex/feedback/Diagnostics.java \
        app/src/main/res/values*/strings.xml
git commit -m "feat: choose which pad to map, and say so in a report"
```

---

### Task 11: Say it exists

**Files:**
- Modify: `README.md` (the *Controllers* section)
- Modify: `docs/USING.md` (the *Controller hotkeys* section)

- [ ] **Step 1: README**

Two or three sentences in *Controllers*, no more - the README takes a line or
two per feature and reasoning goes elsewhere. It must still open with "plug one
in and it works", because that is still true and is the better half of the
story.

- [ ] **Step 2: `docs/USING.md`**

A subsection under *Controller hotkeys* covering: the eight rows, that a capture
takes its button off whatever else held it, that a direction can be an axis, and
that each pad keeps its own. Icons from `docs/icons/` where a row has one.

- [ ] **Step 3: Commit**

```bash
git add README.md docs/USING.md
git commit -m "docs: the pad's controls can be remapped"
```

---

## Done

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest
scripts/check-prefs.py
scripts/check-strings.py
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
```

Then, with the real pad, the device list from the spec's *Testing* section: an
unmapped pad behaves as before; a remapped Fire works in a real game; the
built-in and Bluetooth pads keep separate mappings; an axis-bound direction
works; and a pad unplugged mid-press leaves nothing held.

Open the pull request against `main` when all of that is done.
