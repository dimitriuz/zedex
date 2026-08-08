package dev.ldlab.zedex;

import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.screen.SettingsActivity;
import dev.ldlab.zedex.storage.Storage;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;

/**
 * Drives the app for the tests: menus by the words in them, and the emulated
 * machine by the keys on its keyboard.
 *
 * Nothing here knows a coordinate. The menus are ordinary views, and every
 * key of the on-screen keyboard is published as an accessibility node named
 * the way the Spectrum names it, so a test can ask for "ENTER" or "CAPS
 * SHIFT" and stay right when the artwork moves or a menu grows an item.
 *
 * What cannot be observed is the emulated screen — it is a GL surface with
 * no view structure — so waits on the machine are elapsed time, and the
 * assertions are made against the files it produces.
 */
final class Emulator {

    static final long SECOND = 1000;

    /** Long enough for Fuse to start and a machine to reach its boot menu. */
    static final long BOOT = 15 * SECOND;

    /** Fuse reads the keyboard once a frame; this leaves room for several. */
    private static final long AFTER_KEY = 150;

    /** Steps of about 5ms each: comfortably past the 400ms latch. */
    private static final int HOLD_STEPS = 150;

    /**
     * Where the ROMs are. Gradle uninstalls the app after every run, so the
     * folder the app was pointed at is gone by the next one and the test has
     * to say again. Override with
     * {@code -Pandroid.testInstrumentationRunnerArguments.dataFolder=…}.
     */
    private static final String DATA_FOLDER_ARGUMENT = "dataFolder";
    private static final String DEFAULT_DATA_FOLDER = "/sdcard/Download/Spectrum";

    private static final long FIND = 10 * SECOND;
    private static final long GLANCE = SECOND;

    private final UiDevice device =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    UiDevice device() {
        return device;
    }

    /**
     * Points the app at a folder that has ROMs in it, and grants the access
     * it needs to read them by path.
     *
     * Fuse opens ROMs with plain stdio, so this cannot be a document tree:
     * it needs All files access, which only the shell can hand out.
     */
    void useDataFolder() {
        String folder = InstrumentationRegistry.getArguments()
                .getString(DATA_FOLDER_ARGUMENT, DEFAULT_DATA_FOLDER);

        shell("appops set " + context().getPackageName()
              + " MANAGE_EXTERNAL_STORAGE allow");

        SharedPreferences preferences = context().getSharedPreferences(
                SettingsActivity.PREFS, Context.MODE_PRIVATE);
        preferences.edit()
                .putString(Storage.KEY_STATES_ROOT, folder)
                .commit();
    }

    String romFolder() {
        return InstrumentationRegistry.getArguments()
                .getString(DATA_FOLDER_ARGUMENT, DEFAULT_DATA_FOLDER) + "/roms";
    }

    private void shell(String command) {
        try {
            device.executeShellCommand(command);
        } catch (java.io.IOException e) {
            throw new AssertionError("shell: " + command, e);
        }
    }

    /**
     * Brings the app up and waits for the keyboard.
     *
     * Not a cold start, and it cannot be one: instrumentation runs inside
     * the app's own process, so stopping the app kills the test with it.
     * Fuse keeps running between tests, which is why each test resets the
     * machine and puts the media it needs where it wants it rather than
     * assuming anything.
     */
    void launch() {
        Context context = context();
        String pkg = context.getPackageName();

        // The machine by name, not the launcher intent. Since the library
        // arrived, getLaunchIntentForPackage() resolves to LibraryActivity,
        // which hands over to this one only when there is no content folder to
        // browse - true on a run that began with an uninstall, which is every
        // Gradle run, and false on a device somebody has set up by hand. Every
        // test here is about the machine, so it asks for the machine; the
        // library has tests of its own.
        Intent intent = new Intent(context, EmulatorActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        // On the display this test can see - see Screen. Left to itself the
        // picture ends up on a bench's second display and borderColour() reads
        // the other screen's launcher: white, where the test wanted a border.
        context.startActivity(intent, Screen.here());

        device.wait(Until.hasObject(By.pkg(pkg).depth(0)), BOOT);

        // The first start asks where things are kept, and its panel sits over
        // the quick bar - so ☰ is unreachable until it is answered, and every
        // test that opens a menu fails with "the ☰ button never appeared" on a
        // run that began with a fresh install. Which is every run: Gradle
        // uninstalls the app first. Its defaults are what a test wants.
        // By the app's own string rather than the English of it: measured to
        // resolve through the app's resources from here, so it keeps working on
        // a device whose language is not English.
        tapIfPresent(context.getString(R.string.setup_start));

        assertNotNull("the keyboard never appeared",
                      device.wait(Until.findObject(By.desc("ENTER")), BOOT));

        Screen.assertHere();

        // A dialog an earlier test left open would swallow the first taps.
        for (int i = 0; i < 4 && tapIfPresent("Cancel"); i++) {
            // Closing them one at a time.
        }

        // Last, and not before the keyboard has appeared: a machine has to be
        // running before it can be asked to become another one. Placed at the
        // top of this method it was silently a no-op - the command reached a
        // Fuse that had not finished starting, and the test ran on whatever
        // the previous class had left behind.
        useSpectrum48();
    }

    /** True when the app is asking for ROMs, which means it cannot run. */
    boolean needsRoms() {
        return device.wait(Until.findObject(By.textContains("ROM")), GLANCE) != null;
    }

    // --- menus ------------------------------------------------------------

    /** Opens the ☰ menu and follows a path of items through it. */
    void menu(String... path) {
        openMenu();
        for (String item : path) tap(item);
    }

    /**
     * ☰ fades out three seconds after it was last used, and a test spends far
     * longer than that between menus, so it is usually gone. A tap on the
     * picture brings it back — which is what the app tells the user to do.
     *
     * The tap comes first every time, even when the button appears to be
     * there already: finding it and clicking it are two steps, and three
     * seconds is short enough that it can vanish between them. Revealing
     * first restarts the clock, so the click that follows always lands.
     *
     * Found by description rather than through {@link #tap}, which tries
     * three selectors in turn and could spend the three seconds doing it.
     */
    private void openMenu() {
        device.click(device.getDisplayWidth() / 2, device.getDisplayHeight() / 8);
        SystemClock.sleep(200);

        UiObject2 button = device.wait(Until.findObject(By.desc("Menu")), FIND);
        assertNotNull("the ☰ button never appeared", button);

        button.click();
        SystemClock.sleep(500);
    }

    /**
     * Shuts the sheet from wherever in it you are, by tapping the screen
     * beside it — which is how it is meant to be dismissed, and takes one tap
     * however many pages deep the menu has gone.
     */
    void closeMenu() {
        device.click(device.getDisplayWidth() / 8, device.getDisplayHeight() / 2);
        SystemClock.sleep(500);
    }

    /** Clicks whatever carries this text, waiting for it to turn up. */
    void tap(String text) {
        UiObject2 target = find(text);
        assertNotNull("nothing on screen says " + text, target);
        target.click();
        SystemClock.sleep(500);
    }

    /** Clicks it only if it is there, for dialogs that appear conditionally. */
    boolean tapIfPresent(String text) {
        UiObject2 target = device.wait(
                Until.findObject(By.clickable(true).textContains(text)), GLANCE);
        if (target == null) return false;

        target.click();
        SystemClock.sleep(500);
        return true;
    }

    boolean isShowing(String text) {
        return device.wait(Until.findObject(By.textContains(text)), GLANCE) != null;
    }

    /**
     * A clickable match wins: a confirmation dialog whose message repeats the
     * word on its button would otherwise be tapped in the middle of the
     * sentence. List rows in an AlertDialog are not clickable themselves, so
     * anything at all will do as a fallback.
     */
    private UiObject2 find(String text) {
        UiObject2 target = device.wait(
                Until.findObject(By.clickable(true).textContains(text)), GLANCE);
        if (target != null) return target;

        target = device.wait(Until.findObject(By.textContains(text)), GLANCE);
        if (target != null) return target;

        // The ☰ button and the cards' own buttons have no text worth matching,
        // only a description - and those are quoted, "Rename “Tujad”", so the
        // match has to be a contains and not an equals.
        target = device.wait(Until.findObject(By.descContains(text)), GLANCE);
        if (target != null) return target;

        scrollTo(text);
        return device.wait(Until.findObject(By.textContains(text)), FIND);
    }

    /** Long machine lists do not fit on one screen. */
    private void scrollTo(String text) {
        try {
            UiScrollable list = new UiScrollable(new UiSelector().scrollable(true));
            if (list.exists()) list.scrollTextIntoView(text);
        } catch (Exception e) {
            // Not scrollable, or not there at all; the caller reports it.
        }
    }

    /** Replaces the contents of the only text field on screen. */
    void enterText(String text) {
        UiObject2 field = device.wait(
                Until.findObject(By.clazz(android.widget.EditText.class)), FIND);
        assertNotNull("no text field on screen", field);
        field.setText(text);
    }

    // --- the machine's keyboard -------------------------------------------

    /** Presses a key by its Spectrum name: "Q", "7", "ENTER", "CAPS SHIFT". */
    void key(String name) {
        UiObject2 target = device.wait(Until.findObject(By.desc(name)), FIND);
        assertNotNull("no key called " + name, target);
        target.click();
        SystemClock.sleep(AFTER_KEY);
    }

    /**
     * Holds a shift down until it is tapped again.
     *
     * Not longClick(): that holds for exactly the platform's long-press
     * timeout, which is the same 400ms the keyboard latches at, so which of
     * the two happened first would be a coin toss. A swipe that goes nowhere
     * can be held for as long as it takes.
     */
    private void latch(String shift) {
        UiObject2 target = device.wait(Until.findObject(By.desc(shift)), FIND);
        assertNotNull("no key called " + shift, target);

        Rect bounds = target.getVisibleBounds();
        device.swipe(bounds.centerX(), bounds.centerY(),
                     bounds.centerX(), bounds.centerY(), HOLD_STEPS);
        SystemClock.sleep(AFTER_KEY);
    }

    /** Symbol Shift plus a key, which is how the punctuation is reached. */
    void symbolShift(String name) {
        latch("SYMBOL SHIFT");
        key(name);
        key("SYMBOL SHIFT");
    }

    /**
     * Extended mode: both shifts at once. The next key gives a token that
     * cannot be spelled out - FORMAT and CAT are keys, not words.
     */
    void extendedMode() {
        latch("CAPS SHIFT");
        key("SYMBOL SHIFT");
        key("CAPS SHIFT");
    }

    /** Caps Shift plus a key, for DELETE and the cursors. */
    void capsShift(String name) {
        latch("CAPS SHIFT");
        key(name);
        key("CAPS SHIFT");
    }

    /** Types characters one key at a time. Letters go in as they come. */
    void type(String text) {
        for (char c : text.toCharArray()) {
            if (c == ' ') {
                key("BREAK SPACE");
                continue;
            }

            String shifted = symbolKeyFor(c);
            if (shifted != null) {
                symbolShift(shifted);
            } else {
                key(String.valueOf(Character.toUpperCase(c)));
            }
        }
    }

    /** Which key carries a punctuation mark as its Symbol Shift legend. */
    private static String symbolKeyFor(char c) {
        switch (c) {
            case '"': return "P";
            case ':': return "Z";
            case ';': return "O";
            case ',': return "N";
            case '.': return "M";
            case '?': return "C";
            case '/': return "V";
            case '*': return "B";
            case '-': return "J";
            case '+': return "K";
            case '=': return "L";
            case '<': return "R";
            case '>': return "T";
            case '(': return "8";
            case ')': return "9";
            default:  return null;
        }
    }

    /**
     * Holds a control down for long enough to be certain of it, then lets go.
     *
     * Not longClick(): that holds for exactly the platform's long-press
     * timeout, which is the same 400ms the keyboard latches at.
     */
    void hold(String name) {
        latch(name);
    }

    /** Pins the window the way up a test expects to find things. */
    /**
     * Really portrait, not merely natural: on a tablet the natural orientation
     * <em>is</em> landscape, so setOrientationNatural() left every test that
     * wanted a tall window running in a wide one.
     */
    void portrait() {
        try {
            device.setOrientationPortrait();
        } catch (android.os.RemoteException e) {
            throw new AssertionError("cannot rotate the device", e);
        }
        SystemClock.sleep(SECOND);
    }

    void releaseOrientation() {
        try {
            device.unfreezeRotation();
        } catch (android.os.RemoteException e) {
            // Nothing worth failing a finished test over.
        }
    }

    // --- handing the machine media ------------------------------------------

    /**
     * Gives Fuse a file by path, which is what an intent from a file manager
     * comes down to. A tape autoloads, so a program written by
     * {@link TapeProgram} needs no keystrokes at all.
     *
     * Instrumentation runs inside the app's own process, so this is the same
     * call the activity makes rather than an imitation of it.
     */
    void open(java.io.File file) {
        FuseNative.openFile(file.getAbsolutePath());
        idle(LOADING);
    }

    /** Fast loading turns a tape into a moment, but autoload types first. */
    private static final long LOADING = 8 * SECOND;

    // --- reading the emulated screen ----------------------------------------

    /*
     * The picture is a GL surface with no view structure, so nothing in it can
     * be found by name. A screenshot of the device does capture it, though,
     * and one pixel of border is a whole answer when the program under test
     * says what it saw by changing the border colour.
     */

    /** Fuse's palette, in Spectrum colour order, at normal brightness. */
    private static final int[] PALETTE = {
        0x000000, 0x0000c0, 0xc00000, 0xc000c0,
        0x00c000, 0x00c0c0, 0xc0c000, 0xc0c0c0,
    };

    /**
     * Far enough in from the picture's corner to be past any rounding, and still
     * border: the Spectrum's is a good twenty pixels deep once scaled.
     */
    private static final int BORDER_SAMPLE = 8;

    /**
     * The Spectrum colour number the border is showing, 0 (black) to 7.
     *
     * Sampled inside the <b>picture</b>, which is not the same thing as the
     * SurfaceView's bounds and was the second version of this bug. A fixed
     * corner of the window was the first: the quick bar took a strip across the
     * top and the picture starts below it, so the corner read black and three
     * tests failed with the machine working perfectly. Asking the SurfaceView
     * fixed that in portrait, where the box it gets is exactly the height a 4:3
     * image uses - and left it broken sideways, where the box is the whole width
     * and the 4:3 picture is centred in it with black bars either side, so the
     * box's own left edge is window and not border. Same symptom, same "expected
     * 4 but was 0".
     *
     * So the quad is worked out here: the renderer centres 4:3 in whatever box
     * it is given, and this does the same sum. Any arrangement, either way up.
     */
    int borderColour() {
        java.io.File shot = new java.io.File(context().getCacheDir(), "border.png");
        assertTrue("could not screenshot the device", device.takeScreenshot(shot));

        android.graphics.Bitmap screen =
                android.graphics.BitmapFactory.decodeFile(shot.getAbsolutePath());
        assertNotNull("the screenshot did not decode", screen);

        Rect picture = pictureBounds();
        int pixel = screen.getPixel(picture.left + BORDER_SAMPLE,
                                    picture.top + BORDER_SAMPLE);
        screen.recycle();
        shot.delete();

        return nearestColour(pixel);
    }

    /**
     * Where the 4:3 picture actually is, which is a sum and not a view's bounds.
     *
     * The SurfaceView gets a box; the renderer centres the largest 4:3 quad that
     * fits inside it. Sideways the box is wider than 4:3 and the difference is
     * black window either side, so the box's corner is not the picture's.
     */
    private Rect pictureBounds() {
        UiObject2 screen = device.wait(
                Until.findObject(By.clazz("android.view.SurfaceView")), GLANCE);
        if (screen == null) return new Rect(0, 0, 1, 1);

        Rect box = screen.getVisibleBounds();

        int wide = Math.min(box.width(), Math.round(box.height() * ASPECT));
        int tall = Math.round(wide / ASPECT);
        int left = box.left + (box.width() - wide) / 2;
        int top = box.top + (box.height() - tall) / 2;

        return new Rect(left, top, left + wide, top + tall);
    }

    /** The emulated screen, whichever border is shown and whatever the machine. */
    private static final float ASPECT = 4.0f / 3.0f;

    /** Whichever of the eight the pixel is closest to; the shader is exact,
     *  but a nearest match survives a device that dithers or colour-manages. */
    private static int nearestColour(int pixel) {
        int best = 0;
        long closest = Long.MAX_VALUE;

        for (int colour = 0; colour < PALETTE.length; colour++) {
            long distance = squared(pixel, PALETTE[colour]);
            if (distance < closest) {
                closest = distance;
                best = colour;
            }
        }

        return best;
    }

    private static long squared(int a, int b) {
        long dr = ((a >> 16) & 0xff) - ((b >> 16) & 0xff);
        long dg = ((a >> 8) & 0xff) - ((b >> 8) & 0xff);
        long db = (a & 0xff) - (b & 0xff);

        return dr * dr + dg * dg + db * db;
    }

    /** Waiting on the emulated machine, which cannot be observed directly. */
    /**
     * A 48K, put back before every test runs.
     *
     * Two reasons, and the second was measured rather than assumed. Nothing
     * otherwise guarantees which machine a test gets: `NewDiskTest` switches to
     * a Scorpion for the TR-DOS disks only it has, and `Machine.remember()`
     * writes whatever is running into the startup preference every time the app
     * pauses - so from that test onwards every later one loaded its tape on a
     * Scorpion, which mostly means <em>R Tape loading error</em>. Six failures
     * over three classes, each of which passed perfectly well alone.
     *
     * And a 48K rather than the 128 the app itself starts on, because the poke
     * tests only pass there: the same three tests fail on a 128 and pass on a
     * 48 with nothing else changed. That is the machine the tests here were
     * written against - see the note in PokesTest about the printer buffer -
     * and a test that quietly depends on a machine should say which one.
     *
     * Cheap when it is already right, which is almost always: it asks Fuse what
     * is running and only switches when the answer is wrong.
     *
     */
    void useSpectrum48() {
        String[] ids = FuseNative.machineIds();
        int current = FuseNative.currentMachine();

        if (current >= 0 && current < ids.length && "128".equals(ids[current])) return;

        for (int i = 0; i < ids.length; i++) {
            if ("128".equals(ids[i])) {
                FuseNative.selectMachine(i);

                // Confirmed rather than assumed: the command is queued and
                // drained on the emulation thread, so this waits for Fuse's
                // own answer instead of a guessed number of milliseconds.
                for (int waited = 0; waited < 5 * SECOND; waited += 250) {
                    idle(250);
                    if (FuseNative.currentMachine() == i) return;
                }

                fail("the machine never became a 48K");
            }
        }
    }

    void idle(long millis) {
        SystemClock.sleep(millis);
    }
}
