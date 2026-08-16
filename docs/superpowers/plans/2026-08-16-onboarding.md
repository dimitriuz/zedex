# Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A guided first run — eight skippable pages covering language, folders, machine, controls, screen, library and scraping — plus coach marks that explain the quick bar, the library and the archive the first time each is seen, and a fix for the first-run gate that has never fired.

**Architecture:** A new `welcome/` layer holds the page vocabulary (`Page`), the pure ordering rule (`Steps`), the page interface (`Step`), and the coach-mark machinery (`Coach`, `Tour`). One activity, `screen/WelcomeActivity`, hosts the pages and owns chrome and paging only. The wizard runs *before* Fuse starts, so every page writes a preference and nothing pushes into a running emulator. `StartPanel` sheds its first-run half and keeps its ROM half.

**Tech Stack:** Java 8 source level, Android `minSdk 30` / `targetSdk 36`, no view XML for these screens (views are built in code, as `StartPanel` and `ProfileActivity` already do), JUnit 4 on the JVM tier with the project's own `FakePreferences` (no Robolectric, no mocking library), UI Automator on the device tier.

**Spec:** `docs/superpowers/specs/2026-08-16-onboarding-design.md`

## Global Constraints

- **Never modify `vendor/`.** Nothing in this plan goes near the Fuse core.
- **The wizard runs before Fuse starts.** `FuseNative.machineIds()` is empty until Fuse is running; `FuseNative.joystickTypeNames()` is not. Never call `Machine.showChooser`, `ControlsUi.chooseJoystickType`, or any `FuseNative` setter from wizard code.
- **A preference's type is whatever wrote it.** `joystickType` is `putInt`; `machine`, `keyboardSkin`, `filter`, `border`, `language`, `catalogueProvider`, `scrapers`, `scraperUser`, `scraperPassword` are `putString`; `library` is `putBoolean`. Run `scripts/check-prefs.py` before every commit that touches preferences.
- **A new string is nine files.** English goes in `app/src/main/res/values/strings.xml`; the eight translations are Task 14. `scripts/check-strings.py` fails on an unknown key or a disagreeing format specifier.
- **Every activity needs `attachBaseContext`** and sets its title in `onCreate`, not via `android:label`. `WelcomeActivity` gets both by extending `ZedexActivity`.
- **Everything of ours stays inside the safe area** — call `fitToSafeArea()`, as `AboutActivity` does.
- **Every screen claims Back.** `ZedexActivity.claimBack` handles it; `WelcomeActivity` overrides what the callback does.
- **Nothing on screen may change its `contentDescription` continuously.** Coach captions are set once per mark and never animated.
- **Build order is native-then-Gradle**, but nothing in this plan touches C, so `env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug` alone is enough.
- **Model names are literals** (`ZX Spectrum +2A`, `Pentagon 128` — product names, like Kempston). Every other user-visible word is a string resource.

## Commands

```sh
# JVM tier — fast, run constantly
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*WelcomeGateTest'

# Build and install by hand — NOT connectedDebugAndroidTest, which uninstalls
# first and wipes the SAF content-folder grant that only the picker can restore
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Device tier — -r reports ASSUMPTION_FAILURE by name instead of folding it into OK
adb shell am instrument -w -r -e class dev.ldlab.zedex.WelcomeTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner

scripts/check-strings.py
scripts/check-prefs.py
```

## File structure

| file | responsibility |
|---|---|
| `storage/Prefs.java` (modify) | `isUpdate`, `welcomeNeeded` — the first-run question |
| `welcome/Page.java` (create) | the eight pages, as an enum |
| `welcome/Steps.java` (create) | pure ordering: which pages apply, given answers and build |
| `welcome/Step.java` (create) | one page's view contract |
| `welcome/Coach.java` (create) | the coach-mark overlay |
| `welcome/Tour.java` (create) | a named sequence of marks and its flag |
| `welcome/pages/*.java` (create) | one class per page body |
| `screen/WelcomeActivity.java` (create) | chrome, paging, the finish path, routing |
| `screen/Links.java` (create) | the four URLs, one copy each |
| `view/Cards.java` (create) | the card builders `StartPanel` and the wizard share |
| `screen/StartPanel.java` (modify) | loses its first-run half, keeps ROMs |
| `screen/LibraryActivity.java` (modify) | asks the gate; hosts two tours |
| `EmulatorActivity.java` (modify) | asks the gate; hosts one tour; holds the bar up for it |
| `screen/SettingsActivity.java` (modify) | two `Getting started` rows; uses `Prefs.isUpdate` |
| `screen/AboutActivity.java` (modify) | uses `Links`; its comment corrected |

---

### Task 1: The gate — a first-run question a launcher cannot answer for it

`StartPanel.setupNeeded` asks `preferences.getAll().isEmpty()`. `LibraryActivity` is the launcher, and its `onCreate` writes `libraryMigrated` before `EmulatorActivity` ever asks — so the answer is always no and the folders panel has never appeared on a fresh install.

The replacement asks whether this install has ever been updated. Split in two so the JVM tier can test the rule: a pure function over a boolean, plus a `Context` overload that supplies it — the same shape as `EsDe.esdeIn(volumes)`, and for the same reason.

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/storage/Prefs.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/StartPanel.java:566-580` (`setupNeeded`)
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/SettingsActivity.java:118-155` (`migrateLibraryDefault`, `isUpdate`)
- Test: `app/src/test/java/dev/ldlab/zedex/storage/WelcomeGateTest.java`

**Interfaces:**
- Produces: `Prefs.welcomeNeeded(boolean everUpdated, SharedPreferences prefs) -> boolean`, `Prefs.welcomeNeeded(Context context, SharedPreferences prefs) -> boolean`, `Prefs.isUpdate(Context context) -> boolean`
- Consumes: `Storage.KEY_SETUP_DONE` (`"setupDone"`)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/ldlab/zedex/storage/WelcomeGateTest.java`:

```java
package dev.ldlab.zedex.storage;

import dev.ldlab.zedex.FakePreferences;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The question the first run turns on.
 *
 * It used to be "is the preferences file empty", which LibraryActivity - the
 * launcher - falsifies in its own onCreate by writing libraryMigrated before
 * EmulatorActivity ever asks. So the folders question was never put on a
 * fresh install, silently, and the app settled where it kept things without
 * asking. Each case below is one of the states that made that possible.
 */
public class WelcomeGateTest {

    @Test
    public void aFreshInstallIsAsked() {
        assertTrue(Prefs.welcomeNeeded(false, new FakePreferences()));
    }

    /** The case the old test got wrong: the launcher has already written
     *  something by the time anyone asks, and that is not an answer. */
    @Test
    public void aFreshInstallIsAskedEvenAfterTheLauncherHasWritten() {
        assertTrue(Prefs.welcomeNeeded(false,
                new FakePreferences().with(Prefs.KEY_LIBRARY_MIGRATED, true)));
    }

    @Test
    public void anAnsweredInstallIsNotAskedAgain() {
        assertFalse(Prefs.welcomeNeeded(false,
                new FakePreferences().with(Storage.KEY_SETUP_DONE, true)));
    }

    /** Somebody who has been playing for a month is not interrogated because
     *  a version arrived with a wizard in it. */
    @Test
    public void anUpdatedInstallIsNeverAsked() {
        assertFalse(Prefs.welcomeNeeded(true, new FakePreferences()));
    }

    /** setupDone still wins over everything, so a hand-set flag settles it. */
    @Test
    public void anAnsweredUpdatedInstallIsNotAsked() {
        assertFalse(Prefs.welcomeNeeded(true,
                new FakePreferences().with(Storage.KEY_SETUP_DONE, true)));
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*WelcomeGateTest'
```

Expected: compile failure — `cannot find symbol: method welcomeNeeded`.

- [ ] **Step 3: Add the question to `Prefs`**

Add to `app/src/main/java/dev/ldlab/zedex/storage/Prefs.java`, with the imports `android.content.Context`, `android.content.SharedPreferences` and `android.content.pm.PackageInfo`:

```java
    /**
     * Whether the first run is still to be answered.
     *
     * <b>Not "are the preferences empty".</b> That is what this used to ask,
     * from {@code StartPanel.setupNeeded}, and it was answered for it: {@code
     * LibraryActivity} is the launcher and writes {@code libraryMigrated} in
     * its own {@code onCreate} before {@code EmulatorActivity} ever gets here,
     * so the file was never empty and the folders question was never put. The
     * app settled where it kept everything without asking, and nothing said
     * so. {@code SettingsActivity} had already learned this for its own
     * migration and written it down; this is the caller that did not get the
     * memo.
     *
     * The honest question is whether this install has ever been updated, which
     * nothing this process does to its own preferences can change.
     *
     * @param everUpdated {@link #isUpdate}, passed rather than asked, so the
     *                    rule can be tested on the JVM tier - a device test
     *                    cannot pose "an install that has been updated" either
     */
    public static boolean welcomeNeeded(boolean everUpdated,
                                        SharedPreferences preferences) {
        if (preferences.getBoolean(Storage.KEY_SETUP_DONE, false)) return false;
        return !everUpdated;
    }

    /** {@link #welcomeNeeded(boolean, SharedPreferences)}, asking the package
     *  manager the question it needs. */
    public static boolean welcomeNeeded(Context context,
                                        SharedPreferences preferences) {
        return welcomeNeeded(isUpdate(context), preferences);
    }

    /**
     * Whether this install has ever replaced itself.
     *
     * {@code firstInstallTime} and {@code lastUpdateTime} are the same instant
     * for exactly as long as an install has never been updated, and differ from
     * the first update on. Unlike the preferences file, that is unaffected by
     * anything this process has written.
     *
     * Any failure to read it answers "yes, this is an update" - the
     * conservative direction, since it is an existing user who must not be
     * interrogated, never a new one who must not be asked.
     */
    public static boolean isUpdate(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.firstInstallTime != info.lastUpdateTime;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return true;
        }
    }
```

- [ ] **Step 4: Run the test and watch it pass**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*WelcomeGateTest'
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Point `StartPanel` at it**

In `app/src/main/java/dev/ldlab/zedex/screen/StartPanel.java`, replace the body of `setupNeeded` (keeping the method, which `EmulatorActivity` calls in two places):

```java
    /**
     * Whether this is the first start, and so whether the folders have been
     * asked about.
     *
     * The rule lives in {@link Prefs#welcomeNeeded} now, and is no longer "the
     * preferences file is empty" - see that method for what was wrong with it
     * and how long it had been wrong.
     */
    public static boolean setupNeeded(Activity activity) {
        return Prefs.welcomeNeeded(activity, activity.getSharedPreferences(
                Prefs.PREFS, Activity.MODE_PRIVATE));
    }
```

Delete the now-unused `SharedPreferences preferences` local and the comment block above the old `getAll().isEmpty()` return.

- [ ] **Step 6: Delete `SettingsActivity`'s copy of `isUpdate`**

In `app/src/main/java/dev/ldlab/zedex/screen/SettingsActivity.java`, delete the private `isUpdate(Context)` method (lines 145-155) and change its one caller inside `migrateLibraryDefault`:

```java
        if (Prefs.isUpdate(context)) {
            edit.putBoolean(KEY_LIBRARY, false);
        }
```

Update `migrateLibraryDefault`'s javadoc reference from `{@link #isUpdate}` to `{@link Prefs#isUpdate}`.

- [ ] **Step 7: Build, and check the whole JVM tier still passes**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug testDebugUnitTest
scripts/check-prefs.py
```

Expected: BUILD SUCCESSFUL, no new failures.

- [ ] **Step 8: Prove it on a device**

The gate cannot be tested by instrumentation — it turns on install times — so verify by hand, once, and record what you saw:

```sh
adb uninstall dev.ldlab.zedex.debug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set --uid dev.ldlab.zedex.debug MANAGE_EXTERNAL_STORAGE allow
adb shell am start -n dev.ldlab.zedex.debug/dev.ldlab.zedex.screen.LibraryActivity
adb shell dumpsys activity | grep -i ResumedActivity
scripts/ui-tap.py list
```

Expected: `ui-tap.py list` shows *Where should Zedex keep things?* — the panel that has not been appearing. Note in the commit message that this was checked by hand and what was seen.

Restoring the bench afterwards: the uninstall takes the SAF content-folder grant with it, and only the app's own folder picker can give it back — Settings › Library › Content folder.

- [ ] **Step 9: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/storage/Prefs.java \
        app/src/main/java/dev/ldlab/zedex/screen/StartPanel.java \
        app/src/main/java/dev/ldlab/zedex/screen/SettingsActivity.java \
        app/src/test/java/dev/ldlab/zedex/storage/WelcomeGateTest.java
git commit -m "fix: ask the first-run question again

StartPanel.setupNeeded decided this was a first run with
preferences.getAll().isEmpty(). LibraryActivity is the launcher and its
onCreate calls migrateIfNeeded, which writes libraryMigrated before
EmulatorActivity asks - so the file was never empty, the answer was
always no, and the folders question has never been put on a fresh
install. The app settled where it kept everything without asking.

Prefs.welcomeNeeded asks whether this install has ever been updated
instead, which nothing this process writes can change. Split into a pure
rule plus a Context overload so the JVM tier can pose the cases; a device
test cannot pose 'an install that has been updated' either.

isUpdate moves from SettingsActivity, which had learned this lesson for
its own migration and written it down. Verified by hand on a clean
install: the panel appears."
```

---

### Task 2: The page vocabulary and the ordering rule

Which pages a wizard shows depends on the build and on what earlier pages answered. That rule is worth having on its own, as a pure function, because it is the part with cases in it.

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/welcome/Page.java`
- Create: `app/src/main/java/dev/ldlab/zedex/welcome/Steps.java`
- Test: `app/src/test/java/dev/ldlab/zedex/welcome/StepsTest.java`

**Interfaces:**
- Produces: `enum Page { WELCOME, FOLDERS, MACHINE, CONTROLS, SCREEN, LIBRARY, SCRAPING, DONE }`; `Steps.applicable(SharedPreferences prefs, boolean hasCatalogue) -> List<Page>`; `Steps.after(Page current, SharedPreferences prefs, boolean hasCatalogue) -> Page` (null when `current` is the last); `Steps.before(Page current, SharedPreferences prefs, boolean hasCatalogue) -> Page` (null when first)
- Consumes: `Storage.KEY_CONTENT_TREE` (`"contentTree"`)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/ldlab/zedex/welcome/StepsTest.java`:

```java
package dev.ldlab.zedex.welcome;

import dev.ldlab.zedex.FakePreferences;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * Which pages the wizard shows, and in what order.
 *
 * Asked afresh on every move rather than settled at the start: whether the
 * archive page applies depends on what the folders page answered, and a list
 * built before that was answered cannot know.
 */
public class StepsTest {

    private static final boolean CATALOGUE = true;
    private static final boolean NO_CATALOGUE = false;

    private FakePreferences withFolder() {
        return new FakePreferences().with(Storage.KEY_CONTENT_TREE,
                "content://com.android.externalstorage.documents/tree/primary%3AROMs");
    }

    @Test
    public void everythingApplies() {
        List<Page> pages = Steps.applicable(withFolder(), CATALOGUE);

        assertEquals(java.util.Arrays.asList(
                Page.WELCOME, Page.FOLDERS, Page.MACHINE, Page.CONTROLS,
                Page.SCREEN, Page.LIBRARY, Page.SCRAPING, Page.DONE), pages);
    }

    /** Nothing to browse means nothing to offer: startsInLibrary is false
     *  without a content folder whatever the switch says, so the switch would
     *  be a setting that cannot take effect. */
    @Test
    public void noContentFolderDropsTheLibraryPage() {
        List<Page> pages = Steps.applicable(new FakePreferences(), CATALOGUE);

        assertFalse(pages.contains(Page.LIBRARY));
        assertTrue(pages.contains(Page.SCRAPING));
    }

    @Test
    public void noCatalogueDropsTheLibraryPage() {
        List<Page> pages = Steps.applicable(withFolder(), NO_CATALOGUE);

        assertFalse(pages.contains(Page.LIBRARY));
    }

    /** Whatever is dropped, the two ends stay: something has to ask the
     *  language and something has to finish. */
    @Test
    public void theFirstAndLastPagesAlwaysApply() {
        List<Page> pages = Steps.applicable(new FakePreferences(), NO_CATALOGUE);

        assertEquals(Page.WELCOME, pages.get(0));
        assertEquals(Page.DONE, pages.get(pages.size() - 1));
    }

    @Test
    public void afterSkipsWhatDoesNotApply() {
        FakePreferences none = new FakePreferences();

        assertEquals(Page.SCRAPING, Steps.after(Page.SCREEN, none, CATALOGUE));
        assertEquals(Page.LIBRARY,
                     Steps.after(Page.SCREEN, withFolder(), CATALOGUE));
    }

    @Test
    public void afterTheLastPageIsNothing() {
        assertNull(Steps.after(Page.DONE, withFolder(), CATALOGUE));
    }

    @Test
    public void beforeWalksBackThroughWhatApplies() {
        FakePreferences none = new FakePreferences();

        assertEquals(Page.SCREEN, Steps.before(Page.SCRAPING, none, CATALOGUE));
        assertEquals(Page.LIBRARY,
                     Steps.before(Page.SCRAPING, withFolder(), CATALOGUE));
    }

    @Test
    public void beforeTheFirstPageIsNothing() {
        assertNull(Steps.before(Page.WELCOME, withFolder(), CATALOGUE));
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*StepsTest'
```

Expected: compile failure — `package dev.ldlab.zedex.welcome does not exist`.

- [ ] **Step 3: Write `Page`**

Create `app/src/main/java/dev/ldlab/zedex/welcome/Page.java`:

```java
package dev.ldlab.zedex.welcome;

/**
 * The wizard's pages, in the order they are asked.
 *
 * An enum and not a list of view classes: which pages apply is a rule with
 * cases in it - see {@link Steps} - and a rule about views is a rule no JVM
 * test can pose. The activity maps one of these to a {@code Step} when it is
 * time to draw it.
 *
 * The order here <em>is</em> the order of the wizard. Nothing else states it.
 */
public enum Page {
    /** Welcome, and the language - one page, so that choosing a language and
     *  seeing the page redraw in it is the same gesture. */
    WELCOME,
    FOLDERS,
    MACHINE,
    CONTROLS,
    SCREEN,
    LIBRARY,
    SCRAPING,
    /** The summary, the intro tape, and the way out. */
    DONE
}
```

- [ ] **Step 4: Write `Steps`**

Create `app/src/main/java/dev/ldlab/zedex/welcome/Steps.java`:

```java
package dev.ldlab.zedex.welcome;

import dev.ldlab.zedex.storage.Storage;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of the {@link Page}s apply, given what has been answered so far.
 *
 * <b>Asked afresh on every move, never settled at the start.</b> Whether the
 * archive page applies depends on whether the folders page chose a content
 * folder, and a list built before the folders page was answered cannot know
 * that. So the activity holds only which page it is on and asks this what
 * comes next - which also means Back walks the pages that were actually
 * shown rather than the ones that were expected.
 *
 * A pure function over the preferences and one capability flag, so the whole
 * rule is testable on the JVM tier. {@code Catalogues.any} needs a Context and
 * is therefore passed in rather than asked here.
 */
public final class Steps {

    private Steps() {
    }

    /**
     * Every page that applies, in order.
     *
     * @param hasCatalogue {@code Catalogues.any(context)} - whether this build
     *                     has an archive to browse at all
     */
    public static List<Page> applicable(SharedPreferences preferences,
                                        boolean hasCatalogue) {
        List<Page> pages = new ArrayList<>();

        for (Page page : Page.values()) {
            if (applies(page, preferences, hasCatalogue)) pages.add(page);
        }

        return pages;
    }

    /** The next page after {@code current}, or null when it is the last. */
    public static Page after(Page current, SharedPreferences preferences,
                             boolean hasCatalogue) {
        List<Page> pages = applicable(preferences, hasCatalogue);
        int at = pages.indexOf(current);

        return at >= 0 && at + 1 < pages.size() ? pages.get(at + 1) : null;
    }

    /** The page before {@code current}, or null when it is the first. */
    public static Page before(Page current, SharedPreferences preferences,
                              boolean hasCatalogue) {
        List<Page> pages = applicable(preferences, hasCatalogue);
        int at = pages.indexOf(current);

        return at > 0 ? pages.get(at - 1) : null;
    }

    private static boolean applies(Page page, SharedPreferences preferences,
                                   boolean hasCatalogue) {
        if (page != Page.LIBRARY) return true;

        // Both halves are needed. Without an archive there is nothing for the
        // provider row to choose between; without a content folder
        // startsInLibrary is false whatever the switch says, so offering the
        // switch would be offering a setting that cannot take effect.
        return hasCatalogue
                && preferences.getString(Storage.KEY_CONTENT_TREE, null) != null;
    }
}
```

- [ ] **Step 5: Run the test and watch it pass**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*StepsTest'
```

Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/welcome/ \
        app/src/test/java/dev/ldlab/zedex/welcome/
git commit -m "feat: the wizard's pages, and the rule for which apply

An enum for the pages and a pure function over the preferences for which
of them apply, asked afresh on every move: whether the archive page
applies depends on what the folders page answered, so a list settled at
the start cannot know. Keeping the rule off the views is what lets the
JVM tier pose the cases."
```

---

### Task 3: The card builders, shared

`StartPanel` builds its rows with `panelChoice`, `folderCard`, `spaced`, `unit`, `stripe` and a private `Column`. The wizard wants the same visual language, and `StartPanel` keeps using them for its ROM half, so they move somewhere both can reach rather than being copied.

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/view/Cards.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/StartPanel.java` (delete the moved helpers, call `Cards`)

**Interfaces:**

Every later task builds its page out of these, so the whole surface is fixed here rather than grown an overload at a time:

```java
public final class Cards {
    public static final int BACK = 0xff0e0f13;
    public static final int CARD = 0xff1b1d24;
    public static final int CYAN = 0xff00b0c8;

    /** dp, as StartPanel's own unit() computed it. */
    public static int unit(Context context, int steps);

    /** A button with a line under it saying what it does. `leading` draws it
     *  in CYAN - the row that gets on with it, or the choice already in
     *  force. */
    public static View choiceOf(Context context, CharSequence label,
                                int description, View.OnClickListener action,
                                boolean leading);

    /** {@link #choiceOf} with a label that is a string resource. Delegates. */
    public static View choice(Context context, int label, int description,
                              View.OnClickListener action, boolean leading);

    /** A row whose button carries the answer rather than the action - what a
     *  folder is called matters more there than what the row would do to it. */
    public static View valueCard(Context context, TextView value,
                                 int description, View.OnClickListener action);

    /** Quiet text on a card with the icon's cyan down the near edge: the row
     *  that asks for nothing should not look like the ones that do. */
    public static TextView note(Context context);

    /** {@link #note} under a heading, for a page with two sections in it. */
    public static View note(Context context, int heading);

    public static View spaced(Context context, View row);

    /** The readable-width column every one of these screens is built in. */
    public static LinearLayout column(Context context, int maxWidthUnits);
}
```

`choiceOf` takes a `CharSequence` because language names, joystick names and machine models all arrive as strings rather than resource ids; `choice` is the resource-id convenience `StartPanel` already wants. Both exist from this task — do not add either later as an overload.

- [ ] **Step 1: Read what is being moved**

Read `app/src/main/java/dev/ldlab/zedex/screen/StartPanel.java` lines 340-540 in full — `panelChoice` (both overloads), `folderCard`, `unit`, `spaced`, `stripe`, the colour constants, and the private `Column` class. Move them verbatim; this task is a move, not a redesign, and changing how a row looks while moving it makes the diff unreadable.

- [ ] **Step 2: Create `view/Cards.java`**

Create the class in `dev.ldlab.zedex.view` with the surface above. Each one comes from a named place in `StartPanel`, so this is a move and not a rewrite:

| `Cards` member | from `StartPanel` |
|---|---|
| `BACK`, `CARD`, `CYAN` | lines 449-452 |
| `unit` | line 434 |
| `choiceOf`, `choice` | `panelChoice`, both overloads, lines 356 and 369 |
| `valueCard` | `folderCard`, line 397 |
| `note`, `note(heading)` | the `demoNote` builder inside `build()` (the `stripe(CARD, CYAN)` block), plus `stripe` itself |
| `spaced` | line 483 |
| `column` | `Column`, line 502, as a `public static final class` nested inside `Cards` |

Take a `Context` where the originals used the field `activity`. **Copy each method's javadoc across unchanged** — those comments explain choices (the readable maximum width, why the one card that asks for nothing gets the cyan stripe) that are not recoverable from the code and do not survive a retyping. `note(Context, int heading)` is the one genuinely new member: a `TextView` heading above a `note`, for the pages that have two sections.

- [ ] **Step 3: Point `StartPanel` at `Cards` and delete its copies**

Replace every `panelChoice(...)` call with `Cards.choice(activity, ...)`, `folderCard(...)` with `Cards.valueCard(activity, ...)`, `spaced(...)` with `Cards.spaced(activity, ...)`, `unit(n)` with `Cards.unit(activity, n)`, and `new Column(activity, unit(150))` with `Cards.column(activity, 150)`. Delete the private originals and the three colour constants, importing `Cards` instead.

- [ ] **Step 4: Build**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Check the ROMs panel still draws**

`StartPanel`'s remaining screen is the one that appears when ROMs are missing, so pose that:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell "run-as dev.ldlab.zedex.debug ls" >/dev/null   # confirm the debug package
adb shell mv /storage/emulated/0/Download/Spectrum/roms /storage/emulated/0/Download/Spectrum/roms-away
adb shell am start -n dev.ldlab.zedex.debug/dev.ldlab.zedex.EmulatorActivity
scripts/ui-tap.py list
adb shell mv /storage/emulated/0/Download/Spectrum/roms-away /storage/emulated/0/Download/Spectrum/roms
```

Expected: the three ways of getting ROMs are listed, drawn as before. Put the folder back — it is the bench's, and the next task needs it.

- [ ] **Step 6: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/view/Cards.java \
        app/src/main/java/dev/ldlab/zedex/screen/StartPanel.java
git commit -m "refactor: move StartPanel's card builders into view/Cards

The wizard wants the same rows, and StartPanel keeps them for its ROM
half, so they move rather than being copied. A verbatim move: the
comments come with the code, since what they explain - the readable
maximum width, the cyan stripe on the one card that asks for nothing -
is not recoverable from the code itself.

Checked by hand that the ROMs panel still draws."
```

---

### Task 4: `WelcomeActivity` — chrome, paging, the finish path, routing

The shell, plus the two pages that make it a wizard end to end: page 0 (welcome and language) and page 7 (done). The middle pages arrive in Tasks 5-9; until then the wizard is two pages and works.

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/welcome/Step.java`
- Create: `app/src/main/java/dev/ldlab/zedex/welcome/pages/LanguagePage.java`
- Create: `app/src/main/java/dev/ldlab/zedex/welcome/pages/DonePage.java`
- Create: `app/src/main/java/dev/ldlab/zedex/screen/WelcomeActivity.java`
- Create: `app/src/main/java/dev/ldlab/zedex/screen/Links.java`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/LibraryActivity.java:513-525`
- Modify: `app/src/main/java/dev/ldlab/zedex/EmulatorActivity.java:2213-2220`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/dev/ldlab/zedex/WelcomeTest.java`

**Interfaces:**
- Consumes: `Page`, `Steps.after`, `Steps.before`, `Prefs.welcomeNeeded`, `Cards.*`
- Produces: `Step` (`int title()`, `int blurb()`, `View body(Context, SharedPreferences)`, `void apply(SharedPreferences)`); `WelcomeActivity.EXTRA_RETURN` = `"welcomeReturn"`; `WelcomeActivity.start(Activity from, boolean returnHere)`; `Links.SOURCE`, `Links.ISSUES`, `Links.KO_FI`, `Links.COFFEE`

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/dev/ldlab/zedex/WelcomeTest.java`. It drives the real activity, so it sets the world it needs rather than inheriting the bench's:

```java
package dev.ldlab.zedex;

import dev.ldlab.zedex.screen.Language;
import dev.ldlab.zedex.screen.WelcomeActivity;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The wizard, walked.
 *
 * Every test here sets setupDone back to what it found: this flag decides
 * whether the app asks anything on the next launch, and a test that leaves it
 * flipped has changed the bench for everything after it.
 */
@RunWith(AndroidJUnit4.class)
public class WelcomeTest {

    private static final long WAIT = 5000;

    private UiDevice device;
    private Context context;
    private SharedPreferences preferences;
    private boolean wasDone;
    private String wasLanguage;

    @Before
    public void setUp() {
        device = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation());
        context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        preferences = context.getSharedPreferences(Prefs.PREFS,
                                                   Context.MODE_PRIVATE);

        wasDone = preferences.getBoolean(Storage.KEY_SETUP_DONE, false);
        wasLanguage = preferences.getString(Language.KEY_LANGUAGE, "");
    }

    @After
    public void tearDown() {
        preferences.edit()
                .putBoolean(Storage.KEY_SETUP_DONE, wasDone)
                .putString(Language.KEY_LANGUAGE, wasLanguage)
                .apply();
    }

    private void launch() {
        Intent intent = new Intent(context, WelcomeActivity.class);
        intent.putExtra(WelcomeActivity.EXTRA_RETURN, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                      | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent, Screen.here());

        assertNotNull("the wizard never appeared",
                device.wait(Until.findObject(
                        By.text(context.getString(R.string.welcome_title))),
                        WAIT));
    }

    @Test
    public void theFirstPageOffersAWayStraightPast() {
        launch();

        assertNotNull("no way past the wizard",
                device.findObject(By.text(
                        context.getString(R.string.welcome_later))));
    }

    /** Set it up later is a real answer, and answering means never being
     *  asked again. */
    @Test
    public void skippingItAllStillFinishesTheSetup() {
        preferences.edit().putBoolean(Storage.KEY_SETUP_DONE, false).apply();
        launch();

        device.findObject(By.text(
                context.getString(R.string.welcome_later))).click();
        device.wait(Until.gone(By.text(
                context.getString(R.string.welcome_title))), WAIT);

        assertTrue("the setup was not recorded as answered",
                   preferences.getBoolean(Storage.KEY_SETUP_DONE, false));
    }

    /** Choosing a language redraws the page in it, which is the only proof a
     *  language choice can offer on the page that makes it. */
    @Test
    public void choosingALanguageRedrawsThePageInIt() {
        launch();

        device.findObject(By.text("Polski")).click();

        assertNotNull("the page did not come back in Polish",
                device.wait(Until.findObject(By.text("Dalej")), WAIT));
        assertEquals("pl", preferences.getString(Language.KEY_LANGUAGE, ""));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug assembleDebugAndroidTest
```

Expected: compile failure — `cannot find symbol: class WelcomeActivity`.

- [ ] **Step 3: Add the English strings**

Add to `app/src/main/res/values/strings.xml`:

```xml
    <!-- The first run. Every page is skippable and the first page offers a way
         past all of them: a screen between somebody and the machine they came
         for is a toll booth, and this one has to earn each page it keeps. -->
    <string name="welcome_title">Welcome to Zedex</string>
    <string name="welcome_message">Seven quick questions — language, where files go, which Spectrum, controls, the picture, your library and the archive. All of them can be changed later, in Settings.</string>
    <string name="welcome_later">Set it up later</string>
    <string name="welcome_later_hint">Go straight to the Spectrum. Everything keeps its usual default.</string>
    <string name="welcome_next">Next</string>
    <string name="welcome_skip">Skip</string>
    <string name="welcome_language">Language</string>
    <string name="welcome_language_hint">Zedex speaks nine. Leave it on the system language and it follows your phone.</string>
    <string name="welcome_done_title">That\'s everything</string>
    <string name="welcome_done_message">All of it lives in Settings if you want to change your mind.</string>
    <string name="welcome_start">Start the machine</string>
    <string name="welcome_start_hint">Done — boot the Spectrum.</string>
    <string name="welcome_issues">Feedback and issues</string>
    <string name="welcome_issues_hint">Anything broken, missing or wrong — on GitHub.</string>
```

- [ ] **Step 4: Write `Step`**

Create `app/src/main/java/dev/ldlab/zedex/welcome/Step.java`:

```java
package dev.ldlab.zedex.welcome;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

/**
 * One page of the wizard.
 *
 * <b>Skip writes nothing.</b> A page writes as it is answered - tapping a
 * language sets the language - so a page that is skipped, and a page that is
 * never reached because it does not apply, leave exactly the same state: the
 * app's own defaults. Nothing downstream has to know how far the wizard got.
 * {@link #apply} exists for the one page that has something to settle on the
 * way out rather than as it is touched; most implementations do nothing.
 */
public interface Step {

    /** The heading, as a string resource. */
    int title();

    /** The one line under it. */
    int blurb();

    /** The page's own controls. Built once, when the page is shown. */
    View body(Context context, SharedPreferences preferences);

    /** Called when the page is left forwards. Most pages need nothing here. */
    default void apply(SharedPreferences preferences) {
    }
}
```

- [ ] **Step 5: Write `Links`**

Create `app/src/main/java/dev/ldlab/zedex/screen/Links.java`:

```java
package dev.ldlab.zedex.screen;

/**
 * The project's own addresses, in one place.
 *
 * They were constants in {@link AboutActivity} until the wizard's last page
 * wanted two of them. One copy, so a moved repository is one edit.
 *
 * <b>Not a {@code releases} url anywhere.</b> CI greps every Play dex for the
 * literal {@code github.com/dimitriuz/zedex/releases} and fails the build if it
 * finds one - the Play build cannot update itself and must not look as though
 * it could. {@code Updater} builds that path at runtime for exactly this
 * reason, and nothing here may spell it out.
 */
public final class Links {

    private Links() {
    }

    public static final String SOURCE = "https://github.com/dimitriuz/zedex";
    public static final String ISSUES = "https://github.com/dimitriuz/zedex/issues";

    /**
     * Where to put something in the hat, if this was worth anything to you.
     *
     * Two of them because the two services reach different people - one of
     * them is unavailable in whole countries. They appear on the About screen
     * and on the last page of the first-run wizard, and nowhere else; nothing
     * is gated behind them and nothing is counted.
     */
    public static final String KO_FI = "https://ko-fi.com/W3Q224VFOR";
    public static final String COFFEE = "https://www.buymeacoffee.com/dmitriileshchenko";
}
```

- [ ] **Step 6: Write the two pages**

Create `app/src/main/java/dev/ldlab/zedex/welcome/pages/LanguagePage.java`. It builds one `Cards.choice` per language from `@array/language_names` and `@array/language_values`, writing `Language.KEY_LANGUAGE` on a tap and calling a `Runnable` the activity passes in so it can `recreate()`:

```java
package dev.ldlab.zedex.welcome.pages;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.screen.Language;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.welcome.Step;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Welcome, and the language, on one page.
 *
 * Together deliberately: choosing a language recreates the activity onto this
 * same page, so the proof that the choice took is the page you are standing
 * on. A language page anywhere else in the wizard would prove it by changing
 * the <em>next</em> page, which is a worse demonstration and a slower one.
 */
public final class LanguagePage implements Step {

    /** What the activity does when a language is chosen: recreate itself. */
    private final Runnable chosen;

    public LanguagePage(Runnable chosen) {
        this.chosen = chosen;
    }

    @Override
    public int title() {
        return R.string.welcome_title;
    }

    @Override
    public int blurb() {
        return R.string.welcome_message;
    }

    @Override
    public View body(Context context, SharedPreferences preferences) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        String[] names = context.getResources()
                .getStringArray(R.array.language_names);
        String[] values = context.getResources()
                .getStringArray(R.array.language_values);

        String current = preferences.getString(Language.KEY_LANGUAGE, "");

        for (int i = 0; i < names.length && i < values.length; i++) {
            String value = values[i];

            // The one already in force leads, in the icon's cyan, so the page
            // says what it is set to as well as what it could be set to.
            column.addView(Cards.choiceOf(context, names[i],
                    R.string.welcome_language_hint,
                    v -> {
                        preferences.edit()
                                .putString(Language.KEY_LANGUAGE, value)
                                .apply();
                        chosen.run();
                    },
                    value.equals(current)));
        }

        return column;
    }
}
```

This needs one addition to `Cards` from Task 3 — `choiceOf(Context, CharSequence label, int description, OnClickListener, boolean leading)`, the same builder as `choice` but taking a label that is already a string rather than a resource id, because language names come from an array. Add it beside `choice`, with `choice` delegating to it.

Create `app/src/main/java/dev/ldlab/zedex/welcome/pages/DonePage.java` the same way: a `Cards.note` summary, then `Cards.choiceOf` rows for `Links.ISSUES`, `Links.KO_FI` and `Links.COFFEE`, each firing `ACTION_VIEW` on the url, and the intro-tape note copied from `StartPanel.describeFolders`'s `setup_demo` line:

```java
        note.setText(context.getString(R.string.setup_demo,
                Storage.demoTape(context).getAbsolutePath()));
```

`https` needs no `<queries>` entry — a web intent carries its own exemption from package-visibility filtering, measured both ways, unlike `mailto`.

- [ ] **Step 7: Write `WelcomeActivity`**

Create `app/src/main/java/dev/ldlab/zedex/screen/WelcomeActivity.java`. This is the whole class bar the imports and the body-building details of `show`:

```java
package dev.ldlab.zedex.screen;

/**
 * The first run: a page at a time, every one of them skippable.
 *
 * <b>Skippable is the point.</b> A screen between somebody and the machine
 * they came for is a toll booth - StartPanel's own note on the demo tape says
 * so - and seven questions is a long one. Page one offers a way straight past
 * all of it, every page offers a way past itself, and skipping writes nothing,
 * so the app's own defaults stay in force.
 *
 * <b>It runs before Fuse starts</b>, and that is a simplification rather than a
 * limitation: Machine.arguments() puts --machine and --joystick-1-output on
 * Fuse's command line out of these very preferences, and FuseSettings reads
 * them after start. So a page writes a preference and stops. Nothing here may
 * call into FuseNative - see MachinePage for the one place that costs
 * something.
 */
public final class WelcomeActivity extends ZedexActivity {

    /** Whether the caller is staying alive behind this - see {@link #start}. */
    public static final String EXTRA_RETURN = "welcomeReturn";

    private static final String STATE_PAGE = "page";

    private Page page;
    private Step step;

    /** Asked once: it needs a Context, and Steps is a pure function. */
    private boolean hasCatalogue;

    /**
     * Held in a field, not passed as a lambda.
     *
     * The platform keeps a back callback in a WeakReference, so an unheld one
     * is collected and every later press lands on nothing - which reads from
     * outside as "Trying to call onBackInvoked() on a null callback
     * reference", and was in this project's own logs from another process
     * making exactly this mistake.
     */
    private final OnBackInvokedCallback back = this::back;

    @Override
    protected int title() {
        return R.string.welcome_title;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        hasCatalogue = Catalogues.any(this);

        page = state != null && state.containsKey(STATE_PAGE)
                ? Page.valueOf(state.getString(STATE_PAGE))
                : Page.WELCOME;

        show(page);
        fitToSafeArea();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_PAGE, page.name());
    }

    /** Which page this is, and the whole of what the wizard knows. */
    private void show(Page which) {
        page = which;
        step = stepFor(which);

        LinearLayout column = Cards.column(this, 150);

        TextView title = new TextView(this);
        title.setTextSize(26);
        title.setTextColor(Palette.TEXT);
        title.setText(step.title());
        column.addView(title);

        TextView blurb = new TextView(this);
        blurb.setTextSize(15);
        blurb.setTextColor(Palette.MUTED);
        blurb.setText(step.blurb());
        column.addView(blurb);

        column.addView(step.body(this, preferences));

        // The way on, and the ways past. DONE has neither: its only button
        // starts the machine, because there is nothing left to skip.
        if (page == Page.DONE) {
            column.addView(Cards.choice(this, R.string.welcome_start,
                    R.string.welcome_start_hint, v -> finishSetup(), true));
        } else {
            column.addView(Cards.choice(this, R.string.welcome_next, 0,
                    v -> next(), true));
            column.addView(Cards.choice(this, R.string.welcome_skip, 0,
                    v -> skip(), false));
        }

        // Only on page one: one offer to leave the whole thing, where it can
        // be taken before any of it has been read.
        if (page == Page.WELCOME) {
            column.addView(Cards.choice(this, R.string.welcome_later,
                    R.string.welcome_later_hint, v -> finishSetup(), false));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Cards.BACK);
        scroll.addView(column);
        setContentView(scroll);
    }

    private Step stepFor(Page which) {
        switch (which) {
            case WELCOME: return new LanguagePage(this::recreate);
            case DONE:    return new DonePage();
            default:
                throw new IllegalStateException("no page for " + which);
        }
    }

    /** Forwards: the page settles whatever it was holding, then on. */
    private void next() {
        step.apply(preferences);
        go(Steps.after(page, preferences, hasCatalogue));
    }

    /** Past: apply is not called, so a skipped page writes nothing. */
    private void skip() {
        go(Steps.after(page, preferences, hasCatalogue));
    }

    /**
     * Back walks the pages that were actually shown, and leaves from the
     * first one - so there is no exit that skips the storage work below, and
     * no way to leave by accident from the middle.
     */
    private void back() {
        Page previous = Steps.before(page, preferences, hasCatalogue);

        if (previous == null) finishSetup();
        else show(previous);
    }

    private void go(Page nextPage) {
        if (nextPage == null) finishSetup();
        else show(nextPage);
    }

    /**
     * Done asking, however few pages were answered.
     *
     * The ROMs go into whatever folder was settled on, which is why this is
     * the moment for it and not onCreate. pinRoot writes statesRoot even when
     * the folders page was skipped, deliberately: leaving the default
     * unrecorded would let a permission granted later silently move where the
     * app looks.
     */
    private void finishSetup() {
        Storage.pinRoot(this);
        Storage.createFolders(this);
        Storage.installRoms(this);

        // A tape like any other from here on; the summary said where it is.
        Storage.installDemo(this);

        preferences.edit().putBoolean(Storage.KEY_SETUP_DONE, true).apply();

        // Where to go is this screen's to decide, because it is the thing that
        // has just settled the two preferences startsInLibrary reads.
        if (!getIntent().getBooleanExtra(EXTRA_RETURN, false)) {
            startActivity(new Intent(this,
                    SettingsActivity.startsInLibrary(preferences)
                            ? LibraryActivity.class
                            : EmulatorActivity.class));
        }

        finish();
    }

    /**
     * @param returnHere whether the caller is staying alive behind this. True
     *                   for EmulatorActivity reached by a file manager's
     *                   ACTION_VIEW - it is singleInstance, so it is still
     *                   there with its original intent and its onResume runs
     *                   startEmulator again - and for the Settings row. False
     *                   for LibraryActivity's launcher path, which finishes
     *                   itself: it cannot make its own hand-over decision,
     *                   since the answer it depends on is what this screen is
     *                   about to write.
     */
    public static void start(Activity from, boolean returnHere) {
        Intent intent = new Intent(from, WelcomeActivity.class);
        intent.putExtra(EXTRA_RETURN, returnHere);
        from.startActivity(intent);
    }
}
```

Two things to check against the real code before compiling. **`title()`** — `ZedexActivity` resolves it in the app's language rather than the phone's, which is why `android:label` is not used; confirm the override's exact signature. **The back callback** — read `ZedexActivity.claimBack` and copy how `SettingsActivity` substitutes its own; the field above is the shape, not necessarily the type, and what matters is that it is a field and registers at `PRIORITY_DEFAULT`.

`stepFor` throws for the pages that do not exist yet. Tasks 5-9 each add one `case` and delete nothing — when the last lands, the `default` is unreachable and the throw is dead code that documents the invariant, which is the right place for it to end up.

- [ ] **Step 8: Declare it in the manifest**

Add to `app/src/main/AndroidManifest.xml`, beside the other `.screen.*` entries:

```xml
        <!-- The first run: where things are kept, which Spectrum, and the rest
             - see screen/WelcomeActivity.java. Its label is set in onCreate
             like every other screen's, because android:label is resolved in
             the phone's language rather than the app's. -->
        <activity
            android:name=".screen.WelcomeActivity"
            android:label="@string/app_name"
            android:theme="@android:style/Theme.DeviceDefault"
            android:exported="false" />
```

- [ ] **Step 9: Ask the gate from both entry activities**

In `LibraryActivity.onCreate`, immediately after the `migrateIfNeeded` call and **before** the `startsInLibrary` hand-over:

```java
        // Before the hand-over, not after: the answer that decides where this
        // app opens is exactly what the wizard is about to write, so this
        // screen cannot make that decision yet. It hands over and goes; the
        // wizard routes when it is done.
        if (Prefs.welcomeNeeded(this, preferences)) {
            WelcomeActivity.start(this, false);
            finish();
            handedOver = true;
            return;
        }
```

In `EmulatorActivity.startEmulator`, replace the `StartPanel.setupNeeded(this) || roms.asking()` branch with:

```java
        // The first run asks where things are kept before anything is kept
        // anywhere: the ROMs are unpacked into the answer, so the question
        // comes before the machine. This activity is singleInstance and stays
        // alive behind the wizard, so it keeps whatever file it was opened
        // with and onResume tries again when the wizard is done.
        if (Prefs.welcomeNeeded(this, preferences)) {
            WelcomeActivity.start(this, true);
            return;
        }
```

Delete the `StartPanel.setupNeeded` call in `EmulatorActivity.onCreate` (line 432) — `Storage.installDemo` is the wizard's job now — and the `roms.asking()` disjunct.

- [ ] **Step 10: Build, install, and run the test**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class dev.ldlab.zedex.WelcomeTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (3 tests)`. Read the count against the three `@Test` methods — `-r` names any `ASSUMPTION_FAILURE` rather than folding it into `OK`.

- [ ] **Step 11: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/ app/src/main/java/dev/ldlab/zedex/screen/ \
        app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml \
        app/src/androidTest/java/dev/ldlab/zedex/WelcomeTest.java
git commit -m "feat: a first run that walks, with a way past it on page one

WelcomeActivity, the Step contract, and the two pages that make it a
wizard end to end: welcome-and-language, and the summary. The middle
pages follow.

Language sits on the welcome page so that choosing one recreates the
activity onto the page that made the choice - the shortest possible proof
it took.

The wizard routes when it finishes, because it is the thing that has just
settled the two preferences startsInLibrary reads. LibraryActivity
therefore hands over and finishes rather than deciding first;
EmulatorActivity, which is singleInstance and keeps whatever file it was
opened with, stays alive behind it and tries again on resume."
```

---

### Task 5: The folders page, and `StartPanel`'s split

`StartPanel` describes two unrelated jobs under two headings in its own javadoc. The first-run half moves; the ROM half stays, because "there are no ROMs" can happen at any time.

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/welcome/pages/FoldersPage.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/StartPanel.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/WelcomeActivity.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/EmulatorActivity.java`
- Test: `app/src/androidTest/java/dev/ldlab/zedex/WelcomeTest.java` (add)

**Interfaces:**
- Consumes: `Step`, `Cards`, `Storage.root`, `Storage.roots`, `Storage.sharedRoot`, `Storage.label`, `Storage.describe`, `Storage.canAskForAnyFolder`, `Storage.canUseAnyFolder`, `Storage.needsAllFilesFor`, `Storage.KEY_CONTENT_TREE`, `Prefs.KEY_STATES_ROOT`
- Produces: `FoldersPage.onActivityResult(int request, int result, Intent data)`, `FoldersPage.onResumed()`, `FoldersPage.REQUEST_DATA_TREE` = 8, `FoldersPage.REQUEST_CONTENT_TREE` = 9

- [ ] **Step 1: Write the failing test**

Add to `WelcomeTest`:

```java
    /** The folders page says where things will go, and both rows carry the
     *  answer on the button - what a folder is called matters more here than
     *  what the row would do to it. */
    @Test
    public void theFoldersPageNamesBothFolders() {
        launch();

        device.findObject(By.text(
                context.getString(R.string.welcome_next))).click();

        assertNotNull("the data folder row never appeared",
                device.wait(Until.findObject(By.textStartsWith(
                        context.getString(R.string.setup_data, ""))), WAIT));
        assertNotNull("the content folder row never appeared",
                device.findObject(By.textStartsWith(
                        context.getString(R.string.setup_content, ""))));
    }
```

- [ ] **Step 2: Run it and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class dev.ldlab.zedex.WelcomeTest#theFoldersPageNamesBothFolders \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: FAIL — *the data folder row never appeared*. Page 1 does not exist yet, so Next goes straight to the summary.

- [ ] **Step 3: Move the folder half out of `StartPanel`**

Cut these members from `StartPanel` into a new `FoldersPage implements Step`: `dataFolder`, `contentFolder`, `demoNote`, `folders`, `asking`, `pending`, `showSetup`, `describeFolders`, `chooseDataFolder`, `useDataFolder`, `chooseContentFolder`, `askForAllFiles`, `onResumed`, `REQUEST_DATA_TREE`, `REQUEST_CONTENT_TREE`, and the folder branches of `onActivityResult`.

Leave in `StartPanel`: everything about ROMs, `setupNeeded` (Task 1 made it a one-liner), `show`, `hide`, `runNow`, `offerRomsDownload`, `importRomsFolder`, `importRomFiles`, `REQUEST_IMPORT_ROMS`, `REQUEST_IMPORT_ROMS_TREE`, and the `Host` interface minus nothing — `setTakeover` still serves the ROMs panel.

Rewrite `StartPanel`'s class javadoc: the **First run** paragraph goes, replaced by one line pointing at `WelcomeActivity`. Read what is left behind before committing — comments do not move with the code, and eleven were left lying after the last refactor of this kind.

- [ ] **Step 4: Delete `asking()` from `EmulatorActivity`**

`StartPanel.asking()` no longer exists. Remove the disjunct from `startEmulator` (Task 4 already did) and any other reference; the compiler will name them.

- [ ] **Step 5: Wire the page's result callbacks**

`WelcomeActivity` forwards to the current step:

```java
    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);

        if (step instanceof FoldersPage) {
            ((FoldersPage) step).onActivityResult(request, result, data);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // A settings-page permission has no onActivityResult - only a resume
        // can notice the answer, or a folder picked before the permission
        // existed is never applied and the row stalls until a restart.
        if (step instanceof FoldersPage) ((FoldersPage) step).onResumed();
    }
```

- [ ] **Step 6: Build, install, run the test**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class dev.ldlab.zedex.WelcomeTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (4 tests)`.

- [ ] **Step 7: Check the picker round trip by hand**

The one thing instrumentation cannot cover cheaply is the content-folder picker, which belongs to another app:

```sh
adb shell am start -n dev.ldlab.zedex.debug/dev.ldlab.zedex.screen.WelcomeActivity
scripts/ui-tap.py "Next" 
scripts/ui-tap.py list
```

Tap the content-folder row by hand, choose a folder, and confirm the row's text changes to name it. A picker left open outlives `am force-stop` and every later reading is of the wrong screen — close it before moving on.

- [ ] **Step 8: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/welcome/pages/FoldersPage.java \
        app/src/main/java/dev/ldlab/zedex/screen/StartPanel.java \
        app/src/main/java/dev/ldlab/zedex/screen/WelcomeActivity.java \
        app/src/main/java/dev/ldlab/zedex/EmulatorActivity.java \
        app/src/androidTest/java/dev/ldlab/zedex/WelcomeTest.java
git commit -m "feat: the folders question becomes a page of the wizard

StartPanel described two unrelated jobs under two headings in its own
javadoc, and now it has one. The first run moves into FoldersPage; the
ROM half stays, because a data folder pointed somewhere the ROMs are not
is a thing that can happen on any day, not only the first.

The All-files round trip keeps its onResume: a settings-page permission
has no onActivityResult, and only a resume can notice the answer.

Checked the content-folder picker by hand."
```

---

### Task 6: The machine page

The one page that cannot read Fuse's own list, because Fuse is not running.

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/welcome/pages/MachinePage.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/WelcomeActivity.java`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/dev/ldlab/zedex/MachineIdsContractTest.java`

**Interfaces:**
- Produces: `MachinePage.MACHINES` — `String[][]`, `{id, name}` pairs
- Consumes: `Prefs.KEY_MACHINE`, `FuseNative.machineIds()` (in the test only)

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/dev/ldlab/zedex/MachineIdsContractTest.java`:

```java
package dev.ldlab.zedex;

import dev.ldlab.zedex.welcome.pages.MachinePage;

import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

/**
 * The wizard's machine list against Fuse's own.
 *
 * The wizard runs before Fuse starts, so it cannot ask
 * FuseNative.machineIds() what exists - that array is empty until the
 * emulation thread is up, which is why SettingsActivity disables its machine
 * row when it comes back empty. The list is therefore written down, and this
 * is what keeps it honest.
 *
 * <b>Asserted, never skipped on an empty array.</b> A test that reads
 * machineIds(), finds it empty and calls assumeTrue reports OK having
 * asserted nothing - which is how a mapping pointing at an id Fuse does not
 * have once passed review. So the emulator is launched first and the array
 * itself is an assertion.
 */
@RunWith(AndroidJUnit4.class)
public class MachineIdsContractTest {

    @Rule
    public final Emulator emulator = new Emulator();

    @Test
    public void everyOfferedMachineIsOneFuseHas() {
        emulator.launch();

        String[] ids = FuseNative.machineIds();

        assertTrue("Fuse has not started: machineIds() is empty, so this test "
                 + "would assert nothing", ids.length > 0);

        List<String> known = Arrays.asList(ids);

        for (MachinePage.Model machine : MachinePage.MACHINES) {
            assertTrue("the wizard offers a machine Fuse does not have: "
                     + machine.id + " (" + machine.name + "), Fuse has "
                     + known, known.contains(machine.id));
        }
    }
}
```

Check `Emulator`'s shape before writing this — if it is not a JUnit `@Rule` in this codebase, drive it the way `CaptureTest` does and match that.

- [ ] **Step 2: Run it and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebugAndroidTest
```

Expected: compile failure — `cannot find symbol: class MachinePage`.

- [ ] **Step 3: Add the strings**

```xml
    <string name="welcome_machine">Which Spectrum?</string>
    <string name="welcome_machine_hint">The one a game expects. 128K runs most things; the full list, and every other machine Fuse knows, is in Settings › Machine.</string>
    <string name="welcome_machine_48">The original, 48K of memory and the beeper.</string>
    <string name="welcome_machine_128">128K and the AY sound chip. What most games want.</string>
    <string name="welcome_machine_plus2">The 128K with a tape deck built in.</string>
    <string name="welcome_machine_plus2a">The +3 without the disk drive.</string>
    <string name="welcome_machine_plus3">128K with a 3-inch disk drive.</string>
    <string name="welcome_machine_pentagon">The Russian clone, and most of what arrives as .trd or .scl.</string>
```

- [ ] **Step 4: Write `MachinePage`**

```java
package dev.ldlab.zedex.welcome.pages;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.welcome.Step;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Which Spectrum to start.
 *
 * <b>Written down rather than asked of Fuse.</b> FuseNative.machineIds() is
 * empty until the emulation thread is up - SettingsActivity disables its own
 * machine row for exactly that reason - and this wizard runs before the
 * machine starts by design, since the ROMs are unpacked into a folder the
 * wizard has not settled yet. So the six that matter are here, and
 * MachineIdsContractTest asserts every one of them against Fuse's real list
 * after launching the emulator.
 *
 * Six and not thirty-two: this is the page that gets somebody started, and
 * the whole list is one tap away in Settings › Machine. The model names are
 * literals because they are product names, like Kempston; the line under each
 * is a resource.
 */
public final class MachinePage implements Step {

    /** One offered machine: Fuse's own id, this app's spelling of the model,
     *  and the line under it. */
    public static final class Model {
        public final String id;
        public final String name;
        public final int description;

        Model(String id, String name, int description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }
    }

    public static final Model[] MACHINES = {
        new Model("48",       "ZX Spectrum 48K",  R.string.welcome_machine_48),
        new Model("128",      "ZX Spectrum 128K", R.string.welcome_machine_128),
        new Model("plus2",    "ZX Spectrum +2",   R.string.welcome_machine_plus2),
        new Model("plus2a",   "ZX Spectrum +2A",  R.string.welcome_machine_plus2a),
        new Model("plus3",    "ZX Spectrum +3",   R.string.welcome_machine_plus3),
        new Model("pentagon", "Pentagon 128",     R.string.welcome_machine_pentagon),
    };

    @Override
    public int title() {
        return R.string.welcome_machine;
    }

    @Override
    public int blurb() {
        return R.string.welcome_machine_hint;
    }

    @Override
    public View body(Context context, SharedPreferences preferences) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        // The same default Machine.DEFAULT_MACHINE and settings_machine.xml
        // both state; checked, they agree, so a skipped page is safe.
        String current = preferences.getString(Prefs.KEY_MACHINE, "128");

        for (Model machine : MACHINES) {
            column.addView(Cards.choiceOf(context, machine.name,
                    machine.description,
                    v -> preferences.edit()
                            .putString(Prefs.KEY_MACHINE, machine.id).apply(),
                    machine.id.equals(current)));
        }

        return column;
    }
}
```

- [ ] **Step 5: Add the page to `WelcomeActivity`'s `stepFor(Page)`**

```java
            case MACHINE: return new MachinePage();
```

- [ ] **Step 6: Build, install, run both tests**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class dev.ldlab.zedex.MachineIdsContractTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class dev.ldlab.zedex.WelcomeTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (1 test)` and `OK (4 tests)`. If the contract test fails naming an id, fix the table — Fuse's list is the authority, not this one.

- [ ] **Step 7: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/welcome/pages/MachinePage.java \
        app/src/main/java/dev/ldlab/zedex/screen/WelcomeActivity.java \
        app/src/main/res/values/strings.xml \
        app/src/androidTest/java/dev/ldlab/zedex/MachineIdsContractTest.java
git commit -m "feat: the wizard asks which Spectrum

Written down rather than asked of Fuse: machineIds() is empty until the
emulation thread is up, and this wizard runs before it by design, since
the ROMs go into a folder the wizard has not settled yet.

So the list is a table, and MachineIdsContractTest asserts every id in it
against Fuse's own after launching the emulator - asserted and never
skipped on an empty array, which is how a table pointing at an id Fuse
does not have once passed review."
```

---

### Task 7: The controls page — a keyboard you can actually see

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/welcome/pages/ControlsPage.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/WelcomeActivity.java`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/dev/ldlab/zedex/WelcomeTest.java` (add)

**Interfaces:**
- Consumes: `SpectrumKeyboardView`, `SpectrumKeyboardView.Skin`, `FuseNative.joystickTypeNames()`, `Controls.JOYSTICK_KEYBOARD` (1000), `Prefs.KEY_KEYBOARD_SKIN`, `Prefs.KEY_JOYSTICK_TYPE`

- [ ] **Step 1: Write the failing test**

Add to `WelcomeTest`:

```java
    /** The pad's keyboard mode is ours, not Fuse's, and is appended after
     *  Fuse's eight. Looking it up by name in joystickTypeNames() finds
     *  nothing and never will, which is how the setup dialog once shipped
     *  with the option missing. */
    @Test
    public void theControlsPageOffersTheKeyboardJoystick() {
        launch();

        for (int i = 0; i < 3; i++) {
            device.findObject(By.text(
                    context.getString(R.string.welcome_next))).click();
            device.waitForIdle();
        }

        assertNotNull("the keyboard joystick is missing from the list",
                device.wait(Until.findObject(By.text(
                        context.getString(R.string.joystick_keyboard))), WAIT));
    }
```

- [ ] **Step 2: Run it and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class dev.ldlab.zedex.WelcomeTest#theControlsPageOffersTheKeyboardJoystick \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: FAIL — the page does not exist.

- [ ] **Step 3: Add the strings**

```xml
    <string name="welcome_controls">Keyboard and joystick</string>
    <string name="welcome_controls_hint">Which keyboard to draw over the picture, and what a game should think is plugged in.</string>
    <string name="welcome_controls_keyboard">Keyboard</string>
    <string name="welcome_controls_joystick">Joystick</string>
    <string name="welcome_controls_system_note">Your phone\'s own keyboard types instead — nothing is drawn here.</string>
```

- [ ] **Step 4: Write `ControlsPage`**

Two sections in one column: a live plate, and a joystick list.

```java
    @Override
    public View body(Context context, SharedPreferences preferences) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        // A real keyboard, drawn. SpectrumKeyboardView touches FuseNative only
        // on a key press, never to draw - which is why ProfileActivity can
        // already show a plate with no machine running - so this is the actual
        // skin rather than a picture of it. Touch is off, so no key is ever
        // sent to a Fuse that is not there.
        preview = new SpectrumKeyboardView(context);
        preview.setEnabled(false);
        preview.setClickable(false);
        column.addView(preview);

        column.addView(Cards.note(context, R.string.welcome_controls_keyboard));

        for (SpectrumKeyboardView.Skin skin : SpectrumKeyboardView.Skin.values()) {
            column.addView(Cards.choiceOf(context, context.getString(skin.title),
                    R.string.welcome_controls_hint,
                    v -> {
                        preferences.edit()
                                .putString(Prefs.KEY_KEYBOARD_SKIN, skin.value)
                                .apply();
                        showSkin(context, skin);
                    },
                    skin == current(preferences)));
        }

        column.addView(Cards.note(context, R.string.welcome_controls_joystick));

        // Fuse's own list, which - unlike machineIds() - is populated before
        // Fuse runs. Then ours after it: Fuse has no joystick called the
        // keyboard, and looking one up by name in this array finds nothing.
        String[] names = FuseNative.joystickTypeNames();

        for (int i = 0; i < names.length; i++) {
            int type = i;
            column.addView(Cards.choiceOf(context, names[i], 0,
                    v -> preferences.edit()
                            // putInt: the wrong getter on this key throws only
                            // when the key is present, so it passes every
                            // fresh-install test and crashes on the first
                            // device where the setting has been touched.
                            .putInt(Prefs.KEY_JOYSTICK_TYPE, type).apply(),
                    type == stored(preferences)));
        }

        column.addView(Cards.choiceOf(context,
                context.getString(R.string.joystick_keyboard), 0,
                v -> preferences.edit()
                        .putInt(Prefs.KEY_JOYSTICK_TYPE,
                                Controls.JOYSTICK_KEYBOARD).apply(),
                stored(preferences) == Controls.JOYSTICK_KEYBOARD));

        return column;
    }
```

`showSkin` calls `preview.setSkin(skin)` and swaps the plate for a `Cards.note(context, R.string.welcome_controls_system_note)` when the skin is `SYSTEM`, which has no keys of its own to draw. Read `SpectrumKeyboardView`'s constructor and skin setter first and match their real names; `ControlsUi.keyboardSkin()` shows how the preference is read (`SpectrumKeyboardView.Skin.of(...)`).

`Cards.note(Context, int)` is a new overload of the note builder taking a heading resource — add it beside the existing `note(Context)`.

- [ ] **Step 5: Add the page to `stepFor(Page)`, build, install, run**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class dev.ldlab.zedex.WelcomeTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
scripts/check-prefs.py
```

Expected: `OK (5 tests)`, and `check-prefs.py` clean — it is the guard against `joystickType` being written as anything but an int.

- [ ] **Step 6: Look at it**

```sh
adb shell am start -n dev.ldlab.zedex.debug/dev.ldlab.zedex.screen.WelcomeActivity
scripts/ui-tap.py "Next" "Next" "Next"
adb exec-out screencap -p > /tmp/claude-1000/-home-dimitrius-repo-Zedex/9441909d-2a9f-4342-bf3e-51fc02690a31/scratchpad/controls.png
```

Look at the screenshot: the plate should be a real Spectrum keyboard, legible, and should change when a different skin is tapped. A plate measuring zero height is the failure to expect — check what `SpectrumKeyboardView` needs told about its width.

- [ ] **Step 7: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/welcome/pages/ControlsPage.java \
        app/src/main/java/dev/ldlab/zedex/view/Cards.java \
        app/src/main/java/dev/ldlab/zedex/screen/WelcomeActivity.java \
        app/src/main/res/values/strings.xml \
        app/src/androidTest/java/dev/ldlab/zedex/WelcomeTest.java
git commit -m "feat: the controls page, with a keyboard you can see

A real SpectrumKeyboardView at the chosen skin, not a picture of one:
that view touches FuseNative only on a key press, never to draw, which is
why ProfileActivity can already show a plate with no machine running.
Touch off, so nothing is ever sent to a Fuse that is not there.

Joysticks come from Fuse's own list, which unlike machineIds() is
populated before it runs, with the pad's keyboard mode appended after -
that mode is ours, and looking it up by name in Fuse's array finds
nothing and never will."
```

---

### Task 8: The screen page, and the stills

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/welcome/pages/ScreenPage.java`
- Create: `app/src/main/res/drawable-nodpi/filter_off.png`, `filter_scanlines.png`, `filter_crt.png`, `filter_both.png`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/WelcomeActivity.java`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `docs/DEVELOPING.md`

**Interfaces:**
- Consumes: `Filter` (`OFF`, `SCANLINES`, `CRT`, `BOTH`; `Filter.store(SharedPreferences)`, `Filter.of(SharedPreferences)`), `Border` (`FULL`, `SLIM`, `NONE`), `Prefs.KEY_BORDER`

- [ ] **Step 1: Capture the four stills**

There is no way to preview the filter live — it is a GL shader in `native/ui/android/android_gl.c` running on Fuse's framebuffer, and there is no Fuse when the wizard runs. So capture the real thing, once, from the running emulator. Load the demo tape, let it draw, then for each filter:

```sh
scripts/ui-tap.py "Display" "Filter" "Off"
adb exec-out screencap -p > /tmp/claude-1000/-home-dimitrius-repo-Zedex/9441909d-2a9f-4342-bf3e-51fc02690a31/scratchpad/filter_off.png
```

Crop each to the picture only (the 4:3 quad — `Emulator.borderColour()` computes the same rectangle and shows how), scale to about 480px wide, and put them in `res/drawable-nodpi/`. `nodpi` because they are reference pictures at a fixed size, not icons that should be resampled per density.

- [ ] **Step 2: Record the gap in `docs/DEVELOPING.md`**

Add, under whichever section covers assets:

```markdown
### The wizard's filter stills

`res/drawable-nodpi/filter_*.png` are captures of the real emulator with each
filter on, used by the first-run wizard's screen page — there is no way to
preview the filter without Fuse, since it is a GL shader in
`native/ui/android/android_gl.c` running on its framebuffer.

**Nothing recaptures them when the shader changes.** If you change the filter,
recapture: load the demo tape, set each filter from ☰ › Display, `screencap`,
crop to the 4:3 picture, scale to ~480px wide. They live here rather than in a
README beside the files because `res/drawable` takes resources and nothing else
— a `.md` dropped in there is a build failure.
```

- [ ] **Step 3: Add the strings**

```xml
    <string name="welcome_screen">The picture</string>
    <string name="welcome_screen_hint">What the screen should look like. These are photographs of the real thing, and it is all live in ☰ › Display later.</string>
    <string name="welcome_screen_border">Border</string>
```

- [ ] **Step 4: Write `ScreenPage`**

```java
package dev.ldlab.zedex.welcome.pages;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.machine.Border;
import dev.ldlab.zedex.machine.Filter;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.welcome.Step;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * What the picture should look like.
 *
 * <b>Photographs, not a preview.</b> The filter is a GL shader in
 * native/ui/android/android_gl.c running on Fuse's framebuffer, and there is
 * no Fuse when this page is shown - the wizard runs before the machine starts,
 * because the ROMs go into a folder the wizard has not settled yet. A Java
 * reimplementation for preview purposes would be a second copy of the shader
 * that nothing keeps in step with the first, so these are captures of the real
 * emulator instead. They cannot flatter a filter into looking like something
 * it is not, and nothing recaptures them when the shader changes - that gap is
 * recorded in docs/DEVELOPING.md.
 */
public final class ScreenPage implements Step {

    @Override
    public int title() {
        return R.string.welcome_screen;
    }

    @Override
    public int blurb() {
        return R.string.welcome_screen_hint;
    }

    @Override
    public View body(Context context, SharedPreferences preferences) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        Filter chosen = Filter.of(preferences);

        for (Filter filter : Filter.values()) {
            ImageView still = new ImageView(context);
            still.setImageResource(stillFor(filter));
            still.setAdjustViewBounds(true);
            column.addView(still);

            column.addView(Cards.choiceOf(context,
                    context.getString(filter.title), 0,
                    v -> filter.store(preferences),
                    filter == chosen));
        }

        column.addView(Cards.note(context, R.string.welcome_screen_border));

        Border border = Border.of(preferences);

        for (Border edge : Border.values()) {
            column.addView(Cards.choiceOf(context,
                    context.getString(edge.title), 0,
                    v -> preferences.edit()
                            .putString(Prefs.KEY_BORDER, edge.value).apply(),
                    edge == border));
        }

        return column;
    }

    /**
     * Which capture goes with which filter.
     *
     * A switch here rather than a field on the enum: Filter lives in
     * machine/, which has no business knowing that a drawable of it exists.
     */
    private static int stillFor(Filter filter) {
        switch (filter) {
            case SCANLINES: return R.drawable.filter_scanlines;
            case CRT:       return R.drawable.filter_crt;
            case BOTH:      return R.drawable.filter_both;
            default:        return R.drawable.filter_off;
        }
    }
}
```

`Filter.store(SharedPreferences)` and `Filter.of(SharedPreferences)` already exist (lines 82 and 67 of `Filter.java`); `Border` has `of` but no `store`, hence the explicit `putString` on `Prefs.KEY_BORDER`. Check `Filter.title` and `Border.title` are the public `int` fields this assumes — both are, at `Filter.java:38` and `Border.java:44`.

- [ ] **Step 5: Add to `stepFor(Page)`, build, install, look**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.ldlab.zedex.debug/dev.ldlab.zedex.screen.WelcomeActivity
scripts/ui-tap.py "Next" "Next" "Next" "Next"
adb exec-out screencap -p > /tmp/claude-1000/-home-dimitrius-repo-Zedex/9441909d-2a9f-4342-bf3e-51fc02690a31/scratchpad/screenpage.png
```

Look at it: four visibly different pictures, and the difference between scanlines and CRT should be readable at the size they are drawn. If it is not, they are too small to be worth shipping — scale up, or drop to two.

- [ ] **Step 6: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/welcome/pages/ScreenPage.java \
        app/src/main/res/drawable-nodpi/ \
        app/src/main/java/dev/ldlab/zedex/screen/WelcomeActivity.java \
        app/src/main/res/values/strings.xml docs/DEVELOPING.md
git commit -m "feat: the screen page, with photographs of the filters

The filter is a GL shader on Fuse's framebuffer and there is no Fuse when
the wizard runs, so there is no live preview to be had. These are
captures of the real emulator rather than drawings of it, so they cannot
flatter a filter into looking like something it is not.

Nothing recaptures them when the shader changes. That gap is recorded in
docs/DEVELOPING.md and not beside the files, because res/drawable takes
resources and nothing else."
```

---

### Task 9: The library and scraping pages

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/welcome/pages/LibraryPage.java`
- Create: `app/src/main/java/dev/ldlab/zedex/welcome/pages/ScrapingPage.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/WelcomeActivity.java`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/dev/ldlab/zedex/WelcomeTest.java` (add)

**Interfaces:**
- Consumes: `Prefs.KEY_LIBRARY`, `Prefs.KEY_CATALOGUE`, `Prefs.KEY_SCRAPERS`, `Prefs.KEY_SCRAPER_USER`, `Prefs.KEY_SCRAPER_PASSWORD`, `Catalogues.all(Context)`, `Scrapers`, `ScraperOrder.show(Activity, List<String> available, List<String> enabled, Chosen)`

- [ ] **Step 1: Write the failing test**

```java
    /** The archive page is dropped when there is no content folder: without
     *  one startsInLibrary is false whatever the switch says, so the switch
     *  would be a setting that cannot take effect. */
    @Test
    public void theLibraryPageIsDroppedWithoutAContentFolder() {
        String folder = preferences.getString(Storage.KEY_CONTENT_TREE, null);
        preferences.edit().remove(Storage.KEY_CONTENT_TREE).apply();

        try {
            launch();
            for (int i = 0; i < 5; i++) {
                device.findObject(By.text(
                        context.getString(R.string.welcome_next))).click();
                device.waitForIdle();
            }

            assertNotNull("scraping should follow the screen page when there "
                        + "is no library page",
                    device.wait(Until.findObject(By.text(
                            context.getString(R.string.welcome_scraping))),
                            WAIT));
        } finally {
            if (folder != null) {
                preferences.edit()
                        .putString(Storage.KEY_CONTENT_TREE, folder).apply();
            }
        }
    }
```

Restoring the folder in a `finally` matters: it is the bench's real grant, and a test that drops it leaves every library test after it skipping silently on an `assumeTrue`.

- [ ] **Step 2: Run it and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class dev.ldlab.zedex.WelcomeTest#theLibraryPageIsDroppedWithoutAContentFolder \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: FAIL — neither page exists.

- [ ] **Step 3: Add the strings**

```xml
    <string name="welcome_library">Your library</string>
    <string name="welcome_library_hint">Zedex can open on your own games rather than on a bare Spectrum, and browse an archive of about forty thousand more.</string>
    <string name="welcome_library_start">Open on the library</string>
    <string name="welcome_library_start_hint">Start on your games. The machine is one tap away either way.</string>
    <string name="welcome_library_archive">Which archive</string>
    <string name="welcome_scraping">Covers and descriptions</string>
    <string name="welcome_scraping_hint">Zedex can look up what a game is, who wrote it and what its cover looked like. Nothing is fetched until you ask for it.</string>
    <string name="welcome_scraping_sources">Where to look, and in what order</string>
    <string name="welcome_scraping_account">Your own ScreenScraper account</string>
    <string name="welcome_scraping_account_hint">Optional. The shared account is in every copy of the app and readable by anyone who wants it, so scraping a whole collection is better done on your own — and it is what keeps the shared one working for everybody else.</string>
```

- [ ] **Step 4: Write `LibraryPage`**

```java
    @Override
    public View body(Context context, SharedPreferences preferences) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        Switch open = new Switch(context);
        open.setText(R.string.welcome_library_start);
        open.setChecked(preferences.getBoolean(Prefs.KEY_LIBRARY, true));
        open.setOnCheckedChangeListener((button, on) ->
                preferences.edit().putBoolean(Prefs.KEY_LIBRARY, on).apply());
        column.addView(open);

        column.addView(Cards.note(context, R.string.welcome_library_archive));

        String chosen = preferences.getString(Prefs.KEY_CATALOGUE, null);

        for (Catalogue catalogue : Catalogues.all(context)) {
            String name = catalogue.name();

            column.addView(Cards.choiceOf(context, name, 0,
                    v -> preferences.edit()
                            .putString(Prefs.KEY_CATALOGUE, name).apply(),
                    name.equals(chosen)));
        }

        return column;
    }
```

Check `Catalogue`'s own accessor before compiling — `Catalogues.preferred` reads `Prefs.KEY_CATALOGUE` and matches it against something, and this must write whatever that comparison expects. If it matches on a lower-cased id rather than the display name, write the id and label the row with the name.

- [ ] **Step 5: Write `ScrapingPage`**

`ScraperOrder.show` is package-private in `dev.ldlab.zedex.screen`, so this page needs the `Activity` rather than a bare `Context` — the same shape as `LanguagePage` taking a `Runnable`.

```java
public final class ScrapingPage implements Step {

    private final Activity activity;

    private EditText user;
    private EditText password;

    public ScrapingPage(Activity activity) {
        this.activity = activity;
    }

    @Override
    public View body(Context context, SharedPreferences preferences) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        column.addView(Cards.choice(context,
                R.string.welcome_scraping_sources, 0,
                v -> ScraperOrder.show(activity, Scrapers.names(context),
                        Scrapers.enabledNames(preferences),
                        chosen -> preferences.edit()
                                .putString(Prefs.KEY_SCRAPERS,
                                           String.join("\n", chosen)).apply()),
                false));

        column.addView(Cards.note(context, R.string.welcome_scraping_account));

        user = new EditText(context);
        user.setHint(R.string.settings_scraper_user);
        user.setText(preferences.getString(Prefs.KEY_SCRAPER_USER, ""));
        column.addView(user);

        password = new EditText(context);
        password.setHint(R.string.settings_scraper_password);
        password.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(preferences.getString(Prefs.KEY_SCRAPER_PASSWORD, ""));
        column.addView(password);

        TextView why = Cards.note(context);
        why.setText(R.string.welcome_scraping_account_hint);
        column.addView(why);

        return column;
    }

    /**
     * The account, settled on the way out rather than per keystroke.
     *
     * This is the one page with something to apply: every other page writes as
     * it is touched, so skipping it writes nothing. Skipping this one writes
     * nothing either, which is why the fields are read here and not in a
     * TextWatcher.
     */
    @Override
    public void apply(SharedPreferences preferences) {
        preferences.edit()
                .putString(Prefs.KEY_SCRAPER_USER,
                           user.getText().toString().trim())
                .putString(Prefs.KEY_SCRAPER_PASSWORD,
                           password.getText().toString())
                .apply();
    }
}
```

`Scrapers.names` / `Scrapers.enabledNames` are guesses at that class's real accessors — read `Scrapers` and `SettingsActivity`'s own scraper-order row, which already calls `ScraperOrder.show`, and copy exactly what it passes. Do not invent an accessor; if the shape there is different, match it.

Nothing on this page starts a scrape. It sets up who would be asked.

- [ ] **Step 6: Add both to `stepFor(Page)`, build, install, run the whole class**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class dev.ldlab.zedex.WelcomeTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
scripts/check-prefs.py
```

Expected: `OK (7 tests)`.

- [ ] **Step 7: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/welcome/pages/ \
        app/src/main/java/dev/ldlab/zedex/screen/WelcomeActivity.java \
        app/src/main/res/values/strings.xml \
        app/src/androidTest/java/dev/ldlab/zedex/WelcomeTest.java
git commit -m "feat: the library and scraping pages

The library page is dropped without a content folder or a catalogue:
startsInLibrary is false without a folder whatever the switch says, so
offering the switch would be offering a setting that cannot take effect.

The scraping page sets up who would be asked and starts nothing. The
account note says why one of your own is better than the shared one - the
app's own credentials are in the APK and readable by anyone who wants
them, and an account of your own is the mitigation that works."
```

---

### Task 10: `Coach` — the overlay

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/welcome/Coach.java`
- Test: `app/src/test/java/dev/ldlab/zedex/welcome/CoachPlacementTest.java`

**Interfaces:**
- Produces: `Coach.above(Rect hole, int captionHeight, int windowHeight) -> boolean`; `Coach.show(Activity, View target, CharSequence caption, boolean last, Runnable onNext)`; `Coach.dismiss(Activity)`

- [ ] **Step 1: Write the failing test**

The placement rule is the part with cases in it, so it comes out as a pure function and is tested on the JVM tier:

```java
package dev.ldlab.zedex.welcome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

/**
 * Where the caption goes relative to the thing it is about.
 *
 * Below by default - a caption under the control reads in the direction the
 * eye is already travelling - and above only when there is not room below.
 * The quick bar is at the very top of the window and the library's rail at
 * the very bottom, so both cases are real on the first screen anybody sees.
 */
public class CoachPlacementTest {

    private static final int WINDOW = 2000;
    private static final int CAPTION = 300;

    @Test
    public void aCaptionGoesBelowSomethingNearTheTop() {
        assertFalse(Coach.above(new Rect(0, 0, 400, 120), CAPTION, WINDOW));
    }

    @Test
    public void aCaptionGoesAboveSomethingNearTheBottom() {
        assertTrue(Coach.above(new Rect(0, 1850, 400, 1980), CAPTION, WINDOW));
    }

    /** Exactly enough room below is enough: the rule must not flip on the
     *  boundary, or a control one pixel from the edge behaves differently on
     *  two devices with the same layout. */
    @Test
    public void exactlyEnoughRoomBelowIsBelow() {
        assertFalse(Coach.above(new Rect(0, 1600, 400, 1700), CAPTION, WINDOW));
    }
}
```

`android.graphics.Rect` is a framework class with no behaviour beyond its four fields, and the JVM tier has it available unmocked as long as only the fields are touched. If it turns out to throw `RuntimeException: Stub!`, take four ints instead of a `Rect` — the rule is what matters, not the parameter shape.

- [ ] **Step 2: Run it and watch it fail**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*CoachPlacementTest'
```

Expected: compile failure — `cannot find symbol: class Coach`.

- [ ] **Step 3: Write `Coach`**

A `View` subclass added to `android.R.id.content`, `MATCH_PARENT` both ways.

```java
    @Override
    protected void onDraw(Canvas canvas) {
        // The scrim, then the hole punched out of it. CLEAR rather than a
        // second colour: anything else tints what it is meant to be showing.
        canvas.drawColor(SCRIM);
        canvas.drawRoundRect(hole, radius, radius, cut);   // cut has
                                                           // PorterDuff.Mode.CLEAR
    }
```

The caption is a `TextView` and *Next* an ordinary `Button`, both real children with real text, so a screen reader, a gamepad and `scripts/ui-tap.py` can all reach them. **Set the caption once, when the mark is shown, and never animate it** — nothing on screen may change its `contentDescription` continuously: each change is a window-content-changed event, the accessibility tree never settles, and UI Automator fails with *the ☰ button never appeared*.

`onTouchEvent` returns true unconditionally and `dispatchKeyEvent` swallows everything except the key that activates *Next*, so nothing behind can be half-pressed and no `GamepadCursor` steals focus mid-mark.

`above(...)` is the pure rule the test pins:

```java
    /** Whether the caption belongs above the hole rather than below it. */
    static boolean above(Rect hole, int captionHeight, int windowHeight) {
        return hole.bottom + captionHeight > windowHeight;
    }
```

- [ ] **Step 4: Run the test and watch it pass**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests '*CoachPlacementTest'
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/welcome/Coach.java \
        app/src/test/java/dev/ldlab/zedex/welcome/CoachPlacementTest.java
git commit -m "feat: the coach mark overlay

A scrim with a hole in it, a caption, and a Next - all real children with
real text, so a screen reader, a gamepad and ui-tap.py can each reach
them. The caption is set once per mark and never animated: a
contentDescription that changes continuously never lets the accessibility
tree settle, which has taken the whole UI Automator suite down before.

Where the caption goes is a pure function, so the two cases that matter -
the quick bar at the top of the window, the library's rail at the bottom
- are posed on the JVM tier."
```

---

### Task 11: `Tour`, and the machine's

**Files:**
- Create: `app/src/main/java/dev/ldlab/zedex/welcome/Tour.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/EmulatorActivity.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/storage/Prefs.java`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/androidTest/java/dev/ldlab/zedex/Emulator.java`
- Test: `app/src/androidTest/java/dev/ldlab/zedex/GuideTest.java`

**Interfaces:**
- Produces: `Prefs.KEY_GUIDE_MACHINE` = `"guideMachine"`, `Prefs.KEY_GUIDE_LIBRARY` = `"guideLibrary"`, `Prefs.KEY_GUIDE_CATALOGUE` = `"guideCatalogue"`, `Prefs.GUIDE_FLAGS` (a `String[]` of the three); `Tour.of(String flag)`, `Tour.mark(Supplier<View>, int caption)`, `Tour.arm(Activity)`
- Consumes: `Coach.show`, `Coach.dismiss`

- [ ] **Step 1: Write the failing test**

```java
package dev.ldlab.zedex;

import dev.ldlab.zedex.storage.Prefs;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The guide: once each, and only once.
 *
 * Every other test in this suite turns the guide off in setUp - a mark over
 * the quick bar swallows the taps every other test is trying to make - so
 * this is the one class that arms it, and it puts the flags back afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class GuideTest {

    @Test
    public void theMachinesGuideRunsOnceAndNotTwice() {
        preferences.edit()
                .putBoolean(Prefs.KEY_GUIDE_MACHINE, false).apply();

        emulator.launch();

        assertNotNull("the guide never appeared",
                device.wait(Until.findObject(By.text(
                        context.getString(R.string.guide_next))), WAIT));

        // Walk it to the end; the flag is set at the end and not the start,
        // so a guide abandoned half way is a guide that has not been given.
        while (device.findObject(By.text(
                context.getString(R.string.guide_next))) != null) {
            device.findObject(By.text(
                    context.getString(R.string.guide_next))).click();
            device.waitForIdle();
        }

        assertTrue("the guide did not record itself as given",
                preferences.getBoolean(Prefs.KEY_GUIDE_MACHINE, false));

        // Round again. Not force-stop: instrumentation runs inside the app's
        // process, so stopping the app kills this test with it. A fresh launch
        // of the same activity is enough - the flag is what is being tested,
        // not the process.
        emulator.launch();

        assertNull("the guide came back",
                device.wait(Until.findObject(By.text(
                        context.getString(R.string.guide_next))), 2000));
    }
}
```

The `@Before`/`@After` save and restore all three flags:

```java
    private final boolean[] were = new boolean[Prefs.GUIDE_FLAGS.length];

    @Before
    public void rememberTheFlags() {
        for (int i = 0; i < Prefs.GUIDE_FLAGS.length; i++) {
            were[i] = preferences.getBoolean(Prefs.GUIDE_FLAGS[i], false);
        }
    }

    /** It is the user's device, and the setting they left on is the one they
     *  were using. */
    @After
    public void putTheFlagsBack() {
        SharedPreferences.Editor edit = preferences.edit();
        for (int i = 0; i < Prefs.GUIDE_FLAGS.length; i++) {
            edit.putBoolean(Prefs.GUIDE_FLAGS[i], were[i]);
        }
        edit.commit();
    }
```

**And the ordering trap, which is solved here rather than in the test.** Step 6 makes `Emulator.launch` set all three flags to *true* before starting the activity — so a guide test calling `launch()` suppresses the very thing it is testing and then passes for the wrong reason, having asserted nothing. So `Emulator` grows a second entry point, and `GuideTest` uses it:

```java
    /** launch(), but leaving the guides armed - for the one class that is
     *  about them. Everything else wants launch(), which turns them off:
     *  a mark over the quick bar swallows the taps every other test makes. */
    void launchShowingGuides() {
        guides = true;
        try {
            launch();
        } finally {
            guides = false;
        }
    }
```

with `launch()`'s preference write reading that field. Every `emulator.launch()` in the `GuideTest` above becomes `emulator.launchShowingGuides()`.

- [ ] **Step 2: Run it and watch it fail**

Expected: compile failure — `cannot find symbol: KEY_GUIDE_MACHINE`.

- [ ] **Step 3: Add the flags and strings**

To `Prefs`:

```java
    /**
     * Whether each guide has been given. One flag per screen, not one for the
     * lot: somebody who has had the app for a year gets the archive's marks
     * the first time they open the archive, and is not handed a welcome they
     * have no use for. Somebody who skipped the wizard still gets them.
     */
    public static final String KEY_GUIDE_MACHINE = "guideMachine";
    public static final String KEY_GUIDE_LIBRARY = "guideLibrary";
    public static final String KEY_GUIDE_CATALOGUE = "guideCatalogue";

    /** All three, for the Settings row that re-arms them and for the tests
     *  that must turn them off before they can measure anything else. */
    public static final String[] GUIDE_FLAGS = {
        KEY_GUIDE_MACHINE, KEY_GUIDE_LIBRARY, KEY_GUIDE_CATALOGUE,
    };
```

To `strings.xml`:

```xml
    <string name="guide_next">Next</string>
    <string name="guide_done">Got it</string>
    <string name="guide_skip">Skip the guide</string>
    <string name="guide_picture">Tap the picture any time to bring these buttons back — they fade out so they are not over the game.</string>
    <string name="guide_files">Tapes, disks and anything else you want to load.</string>
    <string name="guide_controls">The keyboard, the joystick, and what your gamepad does.</string>
    <string name="guide_menu">Everything else lives in here — machines, saved states, cheats and settings.</string>
```

- [ ] **Step 4: Write `Tour`**

```java
package dev.ldlab.zedex.welcome;

/**
 * A named sequence of coach marks, and the flag that says it has been given.
 *
 * <b>Targets are suppliers, not views.</b> The view may not be laid out yet,
 * or may not exist in this configuration at all - the quick bar is borrowed by
 * the second screen's panel when one is showing, and a mark ringing a bar that
 * is not in this window rings empty space.
 *
 * <b>It declines quietly.</b> A missing target leaves the flag unset, so the
 * guide is given properly the next time rather than being spent on nothing.
 *
 * <b>And the flag is set at the end, never at the start.</b> A guide
 * interrupted by a rotation or a kill has not been given, and should not be
 * remembered as one that was.
 */
public final class Tour {

    /** One mark: what to ring, and what to say about it. */
    private static final class Mark {
        final Supplier<View> target;
        final int caption;

        Mark(Supplier<View> target, int caption) {
            this.target = target;
            this.caption = caption;
        }
    }

    private final String flag;
    private final List<Mark> marks = new ArrayList<>();

    /** What to do while this is running - the machine's guide holds the quick
     *  bar up with it, or the bar fades out from under its own explanation.
     *  Null for a guide with nothing to hold. */
    private Runnable hold;
    private Runnable release;

    private Tour(String flag) {
        this.flag = flag;
    }

    public static Tour of(String flag) {
        return new Tour(flag);
    }

    public Tour mark(Supplier<View> target, int caption) {
        marks.add(new Mark(target, caption));
        return this;
    }

    /** @param hold    run when the first mark goes up
     *  @param release run when the last is dismissed, or the guide is skipped */
    public Tour holding(Runnable hold, Runnable release) {
        this.hold = hold;
        this.release = release;
        return this;
    }

    /**
     * Give the guide, if it has not been given and everything it points at is
     * there to point at.
     */
    public void arm(Activity activity) {
        SharedPreferences preferences = activity.getSharedPreferences(
                Prefs.PREFS, Activity.MODE_PRIVATE);

        if (preferences.getBoolean(flag, false)) return;

        // Posted: what is visible is what is laid out, and the targets are not
        // laid out yet. Inline, this asks about the previous layout pass and
        // declines on every first showing, for ever.
        activity.getWindow().getDecorView().post(() -> {
            for (Mark mark : marks) {
                if (mark.target.get() == null) return;   // declines, flag unset
            }

            if (hold != null) hold.run();
            showFrom(activity, preferences, 0);
        });
    }

    private void showFrom(Activity activity, SharedPreferences preferences,
                          int at) {
        if (at >= marks.size()) {
            done(preferences);
            return;
        }

        Mark mark = marks.get(at);
        View target = mark.target.get();

        // Gone since the check above - a bar that faded, a row recycled. Not a
        // failure: stop, and leave the flag so the guide is given next time.
        if (target == null) {
            Coach.dismiss(activity);
            if (release != null) release.run();
            return;
        }

        Coach.show(activity, target, activity.getString(mark.caption),
                   at == marks.size() - 1,
                   () -> showFrom(activity, preferences, at + 1));
    }

    private void done(SharedPreferences preferences) {
        preferences.edit().putBoolean(flag, true).apply();
        if (release != null) release.run();
    }
}
```

`Coach.show`'s `last` parameter is what makes the final mark's button read *Got it* rather than *Next*, and its `onNext` is what walks the sequence. `Coach.dismiss` is idempotent — it is called from `showFrom`'s abandon path and again from the activity's `onPause`, and calling it on a window with no overlay must do nothing.

- [ ] **Step 5: Arm it from `EmulatorActivity`**

Built once, in `onCreate`, and armed after the machine is up — the same place `machine.start()` is followed by whatever runs next:

```java
    private final Tour machineTour = Tour.of(Prefs.KEY_GUIDE_MACHINE)
            // The picture first: the bar fading after three seconds with no
            // hint that a tap brings it back is the least discoverable thing
            // in the app, and every other mark here is about that bar.
            .mark(() -> layout.picture(), R.string.guide_picture)
            .mark(() -> filesGroup, R.string.guide_files)
            .mark(() -> controlsGroup, R.string.guide_controls)
            .mark(() -> menuAction, R.string.guide_menu)
            // Or the bar fades out from under its own explanation.
            .holding(() -> quickBar.removeCallbacks(fadeQuickBar),
                     () -> quickBar.postDelayed(fadeQuickBar, BAR_LINGER_MS));
```

`filesGroup` and `controlsGroup` do not exist as fields yet: `buildQuickBar` currently discards what `bar.addGroup` returns for those two (lines 1075 and 1085). Keep the returned views in fields, the way `menuAction` and `manualAction` already are.

`layout.picture()` is a guess at `EmulatorLayout`'s accessor for the `SurfaceView`; read the class and use its real one. If there is none, ring `layout` itself — the whole picture area is the right target for *tap here*, and it is what a finger would hit anyway.

Then, where the machine has just started:

```java
        // Not while the bar is on the panel: with a second screen the quick
        // bar is borrowed by a Presentation on the other display, and an
        // overlay in this window would ring a bar that is not here. Tour.arm
        // declines on a null target, so this needs no separate check - the
        // suppliers answer null when the bar is not in this window.
        machineTour.arm(this);
```

Make the suppliers do that work rather than adding a condition here: `() -> quickBar.getParent() == null ? null : filesGroup` states in one place, next to the target, why a target might be absent. A borrowed bar has been removed from this window's tree, so `getParent()` is the fact to read — verify that against `Panels`/`SecondScreen` before relying on it, and if the bar is moved some other way, read whatever that mechanism actually changes.

- [ ] **Step 6: Turn the guide off in the test harness**

In `Emulator.launch()`, replace the `tapIfPresent(context.getString(R.string.setup_start))` line and its comment with:

```java
        // A test sets the world it needs. The first run and the guide would
        // both sit over the quick bar, and every test that opens a menu would
        // fail with "the ☰ button never appeared" - which fifteen of
        // thirty-four once did. Turned off rather than tapped away: a
        // tapIfPresent races a posted overlay and passes or fails on timing.
        SharedPreferences.Editor edit = context.getSharedPreferences(
                Prefs.PREFS, Context.MODE_PRIVATE).edit();
        edit.putBoolean(Storage.KEY_SETUP_DONE, true);

        // Except for the one class that is about the guides; see
        // launchShowingGuides.
        for (String flag : Prefs.GUIDE_FLAGS) edit.putBoolean(flag, !guides);
        edit.commit();
```

with `private boolean guides;` as a field, and `launchShowingGuides()` as written in Step 1.

`commit()` and not `apply()`: the activity is about to be launched and must read the written values, not race them.

This has to run **before** `context.startActivity`, so move it above that call.

- [ ] **Step 7: Build, install, run the guide test and one existing class**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class dev.ldlab.zedex.GuideTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class dev.ldlab.zedex.CaptureTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: both `OK`. `CaptureTest` is the check that the harness change works — it opens menus, which is exactly what a stray mark would break.

- [ ] **Step 8: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/welcome/Tour.java \
        app/src/main/java/dev/ldlab/zedex/EmulatorActivity.java \
        app/src/main/java/dev/ldlab/zedex/storage/Prefs.java \
        app/src/main/res/values/strings.xml \
        app/src/androidTest/java/dev/ldlab/zedex/Emulator.java \
        app/src/androidTest/java/dev/ldlab/zedex/GuideTest.java
git commit -m "feat: the machine's guide, and a harness that turns it off

Four marks: the picture first, because the bar fading after three seconds
with no hint that a tap brings it back is the least discoverable thing in
the app. The tour holds the bar up while it runs, or it fades out from
under its own explanation.

It declines quietly rather than failing - a missing target, or a bar
borrowed by the second screen's panel, leaves the flag unset so the guide
is given properly next time. And the flag is set at the end, never the
start: a guide interrupted by a rotation has not been given.

Emulator.launch turns all three guides and the first run off before
starting the activity, rather than tapping them away afterwards. A
tapIfPresent races a posted overlay; setting the world a test needs does
not."
```

---

### Task 12: The library's and the archive's guides

**Files:**
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/LibraryActivity.java`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/androidTest/java/dev/ldlab/zedex/Screen.java` (or wherever library tests set up)
- Test: `app/src/androidTest/java/dev/ldlab/zedex/GuideTest.java` (add)

- [ ] **Step 1: Write the failing tests**

Add to `GuideTest`. One helper and two tests — the helper because the two differ only in which flag and which extra:

```java
    /**
     * Arms one guide, opens the library on the tab it belongs to, walks it to
     * the end, and answers whether the flag was set.
     *
     * EXTRA_FROM_MENU on both: it asks for the library whatever the start-in-
     * library setting says, which is exactly why every other library test uses
     * it too. Without it a bench with the setting off hands straight over to
     * the machine and the test measures the wrong screen.
     */
    private void walkTheGuide(String flag, String extra) {
        preferences.edit().putBoolean(flag, false).commit();

        Intent intent = new Intent(context, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        if (extra != null) intent.putExtra(extra, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                      | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent, Screen.here());

        assertNotNull("the guide never appeared",
                device.wait(Until.findObject(By.text(
                        context.getString(R.string.guide_next))), WAIT));

        // To the end: the flag is set there and not at the start, so a guide
        // abandoned half way is a guide that has not been given.
        while (device.findObject(By.text(
                context.getString(R.string.guide_next))) != null) {
            device.findObject(By.text(
                    context.getString(R.string.guide_next))).click();
            device.waitForIdle();
        }
        device.findObject(By.text(
                context.getString(R.string.guide_done))).click();
        device.waitForIdle();
    }

    @Test
    public void theLibrarysGuideIsGivenOnBrowse() {
        walkTheGuide(Prefs.KEY_GUIDE_LIBRARY, null);

        assertTrue("the library guide did not record itself as given",
                   preferences.getBoolean(Prefs.KEY_GUIDE_LIBRARY, false));
    }

    @Test
    public void theArchivesGuideIsGivenOnTheCatalogueTab() {
        assumeTrue("no catalogue in this build",
                   Catalogues.any(context));

        walkTheGuide(Prefs.KEY_GUIDE_CATALOGUE,
                     LibraryActivity.EXTRA_OPEN_CATALOGUE);

        assertTrue("the archive guide did not record itself as given",
                   preferences.getBoolean(Prefs.KEY_GUIDE_CATALOGUE, false));
    }
```

The `assumeTrue` on the second is honest rather than lazy — a build with no catalogue configured has no shelf to land on, and `wantsCatalogue` refuses the extra outright. Run with `-r`, which names an `ASSUMPTION_FAILURE` rather than folding it into `OK`, so a skip is visible instead of reading as a pass.

Both tests need the library's own precondition: a content folder still granted, or `LibraryActivity` shows nothing to ring. Check how the existing library tests guard that and use the same guard.

- [ ] **Step 2: Run them and watch them fail**

Expected: *the guide never appeared* for both.

- [ ] **Step 3: Add the strings**

```xml
    <string name="guide_tabs">Your games, your favourites, what you played last — and an archive of about forty thousand more.</string>
    <string name="guide_toolbar">Search, filter and sort. A filter stays until you clear it.</string>
    <string name="guide_row">Tap a game to see what is known about it; tap again to play.</string>
    <string name="guide_pane">Everything scraped about this game — the cover, who wrote it, the manual, and the way in.</string>
    <string name="guide_shelves">Shelves: by letter, by category, by year, or whatever is best rated.</string>
    <string name="guide_catalogue_search">Look for a game by name across the whole archive.</string>
    <string name="guide_catalogue_pane">Download it into your own library, or open it straight away.</string>
```

- [ ] **Step 4: Arm both from `show(Tab)`**

In `LibraryActivity.show(Tab)`, after the tab's views are in place:

```java
        // One guide per tab, each with its own flag, armed the first time that
        // tab is actually shown - so somebody who has had the app for a year
        // meets the archive's marks when they first open the archive.
        if (tab == Tab.BROWSE) browseTour.arm(this);
        if (tab == Tab.CATALOGUE) catalogueTour.arm(this);
```

Both decline if their targets are missing, so an empty library or an archive that has not loaded a row yet costs nothing and keeps the flag.

- [ ] **Step 5: Turn them off wherever library tests set up**

Find what the library's device tests use in place of `Emulator.launch` and give it the same treatment — all three flags to true, `commit()`, before the activity starts. `grep -rn "LibraryActivity.class" app/src/androidTest` finds them.

- [ ] **Step 6: Build, install, run `GuideTest` and the library suite**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class dev.ldlab.zedex.GuideTest \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e package dev.ldlab.zedex.library \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (3 tests)` for `GuideTest`, and the library package no worse than it was before this task. Note its count before you start, so "no worse" is a number and not an impression.

- [ ] **Step 7: Commit**

```sh
git add app/src/main/java/dev/ldlab/zedex/screen/LibraryActivity.java \
        app/src/main/res/values/strings.xml app/src/androidTest/
git commit -m "feat: guides for the library and the archive

Armed from show(Tab), one flag each, the first time that tab is actually
shown - so somebody who has had the app for a year meets the archive's
marks when they first open the archive, without being handed a welcome
they have no use for.

Both decline when their targets are missing, so an empty library or an
archive that has not loaded a row costs nothing and keeps its flag."
```

---

### Task 13: The way back in, and `AboutActivity`'s corrected comment

**Files:**
- Modify: `app/src/main/res/xml/settings_app.xml`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/SettingsActivity.java`
- Modify: `app/src/main/java/dev/ldlab/zedex/screen/AboutActivity.java`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add the strings**

```xml
    <string name="settings_group_start">Getting started</string>
    <string name="settings_welcome_again">Run the setup again</string>
    <string name="settings_welcome_again_summary">The questions from the first run — language, folders, machine, controls, the picture, your library and scraping. Nothing is cleared.</string>
    <string name="settings_guide_again">Show the guide again</string>
    <string name="settings_guide_again_summary">The pointers over the buttons, next time you open the machine, the library and the archive.</string>
    <string name="settings_guide_again_done">The guide will run again next time you open each screen.</string>
```

- [ ] **Step 2: Add the category**

To `app/src/main/res/xml/settings_app.xml`, above the language category:

```xml
    <!--
        Two rows and not one: re-running seven questions and re-arming three
        overlays are different wants, and somebody who has just been shown a
        guide they did not want should not have to walk a wizard to stop it.
    -->
    <PreferenceCategory android:title="@string/settings_group_start">
        <Preference
            android:key="welcomeAgain"
            android:title="@string/settings_welcome_again"
            android:summary="@string/settings_welcome_again_summary" />
        <Preference
            android:key="guideAgain"
            android:title="@string/settings_guide_again"
            android:summary="@string/settings_guide_again_summary" />
    </PreferenceCategory>
```

- [ ] **Step 3: Wire both rows**

In `SettingsActivity`'s click handling, beside the other `Preference` rows:

```java
        if ("welcomeAgain".equals(key)) {
            // returnHere: this screen stays behind it, and the wizard has no
            // routing to do - it was not the launcher that opened it.
            WelcomeActivity.start(getActivity(), true);
            return true;
        }

        if ("guideAgain".equals(key)) {
            SharedPreferences.Editor edit = preferences.edit();
            for (String flag : Prefs.GUIDE_FLAGS) edit.putBoolean(flag, false);
            edit.apply();

            Toast.makeText(getActivity(), R.string.settings_guide_again_done,
                           Toast.LENGTH_LONG).show();
            return true;
        }
```

Read how the neighbouring rows are dispatched before writing this — `esdeLink` and `statesRoot` show the shape this screen actually uses.

- [ ] **Step 4: Point `AboutActivity` at `Links` and fix its comment**

Delete `SOURCE`, `KO_FI` and `COFFEE` from `AboutActivity` and use `Links`. Rewrite the comment that stood over them, which is now false:

```java
    // The three addresses are in screen/Links now, because the first-run
    // wizard's last page offers two of them as well. That comment used to say
    // this screen was the only place they appeared, and it has stopped being
    // true - a changed policy is fine, a comment lying about it is not.
```

- [ ] **Step 5: Build, install, check both rows by hand**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
scripts/ui-tap.py "Settings" "App" "Run the setup again"
```

Expected: the wizard opens on page 0, and Back from it returns to Settings rather than launching the library or the machine — that is what `EXTRA_RETURN` is for. Then check *Show the guide again* toasts, and that the machine's marks come back on the next launch.

- [ ] **Step 6: Commit**

```sh
git add app/src/main/res/xml/settings_app.xml \
        app/src/main/java/dev/ldlab/zedex/screen/SettingsActivity.java \
        app/src/main/java/dev/ldlab/zedex/screen/AboutActivity.java \
        app/src/main/res/values/strings.xml
git commit -m "feat: run the setup again, and show the guide again

Two rows and not one: re-running seven questions and re-arming three
overlays are different wants.

Re-running clears nothing, so leaving it half-done cannot lose a setting,
and it opens with EXTRA_RETURN so Back goes to Settings rather than
routing to a launch screen.

AboutActivity's comment said its three addresses appeared on that screen
and nowhere else. Two of them are on the wizard's last page now, so the
comment is corrected rather than left lying."
```

---

### Task 14: The eight translations

**Files:**
- Modify: `app/src/main/res/values-de/strings.xml`, `values-es`, `values-fr`, `values-it`, `values-pl`, `values-cs`, `values-ru`, `values-uk`

- [ ] **Step 1: List what is missing**

```sh
scripts/check-strings.py
```

It counts missing keys rather than failing on them, so read the counts: every language should be short by the same number, and that number is the count of keys added in Tasks 4-13.

- [ ] **Step 2: Translate**

Work one language at a time, one file per commit is fine. Two rules the script enforces and one it cannot:

- **Format specifiers must agree with English.** `%1$s` against `%1$d` is a `ClassCastException` that only a non-English reader ever sees.
- **`*_values` arrays are Fuse's own words, compared with `strcmp` — never translate them.** Nothing added here is one, but the machine model names are the same kind of thing: `ZX Spectrum +2A` and `Pentagon 128` are product names and stay as they are in every language.
- What the script cannot check: whether the words are right. The wizard's tone is the app's — plain, specific, no exclamation marks.

- [ ] **Step 3: Check**

```sh
scripts/check-strings.py
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
```

Expected: no unknown keys, no specifier disagreements, zero missing.

- [ ] **Step 4: Look at one, in the language**

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd locale set-app-locales dev.ldlab.zedex.debug --locales pl-PL
adb shell am start -n dev.ldlab.zedex.debug/dev.ldlab.zedex.screen.WelcomeActivity
adb exec-out screencap -p > /tmp/claude-1000/-home-dimitrius-repo-Zedex/9441909d-2a9f-4342-bf3e-51fc02690a31/scratchpad/welcome-pl.png
adb shell cmd locale set-app-locales dev.ldlab.zedex.debug --locales ""
```

Look for text that has outgrown its row — German and Polish are the two that overflow, and the wizard's rows have a description line under every label.

- [ ] **Step 5: Commit**

```sh
git add app/src/main/res/values-*/strings.xml
git commit -m "feat: the wizard and the guide in the other eight languages

check-strings.py clean: no unknown keys, no disagreeing format
specifiers, nothing missing. Model names stay as they are in every
language - ZX Spectrum +2A is a product name, like Kempston.

Checked the layout in Polish, which along with German is where a row
overflows first."
```

---

### Task 15: The whole suite, and the documentation

The last task, and the only one that runs everything.

**Files:**
- Modify: `README.md`
- Modify: `docs/INTERNALS.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Run the whole instrumentation suite**

A large feature, so the whole suite rather than a class — the rule this project already states.

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
    dev.ldlab.zedex.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Read the **first** failure, not the count — one flake cascades into every later class failing the same way. Fix, rerun, and do not proceed with a red suite.

- [ ] **Step 2: Run the JVM tier**

```sh
env JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest
scripts/check-strings.py
scripts/check-prefs.py
```

- [ ] **Step 3: A cold first run, by hand, all the way through**

The one thing no test covers, because it turns on install times:

```sh
adb uninstall dev.ldlab.zedex.debug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set --uid dev.ldlab.zedex.debug MANAGE_EXTERNAL_STORAGE allow
adb shell monkey -p dev.ldlab.zedex.debug 1
```

Walk every page. Then uninstall, reinstall, and walk it again taking *Set it up later* on page one. Both must reach a working Spectrum, and the second must have installed the ROMs and the demo tape despite answering nothing:

```sh
adb shell ls /storage/emulated/0/Download/Spectrum/roms | head
adb shell ls /storage/emulated/0/Download/Spectrum/
```

Then restore the bench: the content-folder grant went with the uninstall, and only the app's own picker can give it back — Settings › Library › Content folder.

- [ ] **Step 4: README**

A line or two, no more — the README is for people using the app.

```markdown
The first start asks a few questions — language, where files go, which
Spectrum, controls, the picture, your library — and every one of them can be
skipped or changed later in Settings. The first time you open the machine, the
library and the archive, a few pointers say what the buttons do; *Settings ›
App* runs either again.
```

- [ ] **Step 5: `docs/INTERNALS.md`**

Add the `welcome/` layer to *How the code is laid out*, one line, in the same shape as its neighbours.

- [ ] **Step 6: `CLAUDE.md`**

Add the rules this work established that are expensive to rediscover. Candidates, each one line under a bold lead:

- The wizard runs before Fuse, so `machineIds()` is empty and `joystickTypeNames()` is not — the asymmetry decides which page can read its own list.
- A guide flag is set at the end and not the start, and a tour with a missing target declines rather than firing on nothing.
- `Emulator.launch` turns the first run and the guides off by writing preferences before the activity starts, not by tapping them away — a `tapIfPresent` races a posted overlay.
- The first-run gate is `Prefs.welcomeNeeded`, and it is not "the preferences file is empty": the launcher writes one before anything asks.

Follow the file's own voice — a bold claim, then what it cost when it was got wrong.

- [ ] **Step 7: Commit and push**

```sh
git add README.md docs/INTERNALS.md CLAUDE.md
git commit -m "docs: the first run, the guide, and what they cost to get wrong

README gets two sentences. INTERNALS gets the welcome layer. CLAUDE.md
gets the four things this work would be expensive to rediscover -
chiefly that the wizard runs before Fuse, so machineIds() is empty and
joystickTypeNames() is not, and that the first-run gate cannot be 'the
preferences file is empty' when the launcher writes one before anything
asks."
git push -u origin feature/onboarding
```

- [ ] **Step 8: Open the pull request**

```sh
gh pr create --fill
```

Then use `superpowers:finishing-a-development-branch` to decide how it integrates.
