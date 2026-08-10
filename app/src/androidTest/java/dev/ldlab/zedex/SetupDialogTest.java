package dev.ldlab.zedex;

import dev.ldlab.zedex.input.ControlProfiles;
import dev.ldlab.zedex.input.Controls;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.machine.Suggested;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * The question a scraped record raises: which machine, and which controls.
 *
 * The dialog itself is small; what is worth a device is everything it depends
 * on being true at the same moment. It shipped broken three ways at once and
 * every one of them was invisible from the JVM:
 *
 * <ul>
 *   <li>{@code FuseNative.machineIds()} is empty until the emulation thread
 *       has published a frame, and a game is opened before Fuse is started -
 *       so the Machine half was simply missing, which reads as the database
 *       not saying rather than as a question asked too early;</li>
 *   <li>the keyboard was looked for by name in Fuse's list of interfaces,
 *       where it has never been - it is this app's own choice;</li>
 *   <li>and the store does not read itself, so a game opened without the
 *       library having run first found no record at all.</li>
 * </ul>
 *
 * So this opens a document the way a file manager does, with a record in the
 * store for it, and asks the screen what it is offering. The bench's own store
 * and answers file are moved aside and put back: they are somebody's real
 * collection and their real decisions.
 *
 * <b>The timing half is not covered, and cannot be here.</b> Removing the
 * wait for Fuse leaves this passing, even run first in a fresh process:
 * staging the document takes longer than starting the emulator, so by the
 * time the question is asked there is always a machine to name. That is why
 * the wait is a guarantee rather than the fix - the fix was moving the
 * question to after the open. The race it guards against is real (a small
 * file can be staged before the surface exists, and Fuse is not started until
 * there is one) and reachable from no test that runs inside this process,
 * since Fuse cannot be stopped once anything has started it.
 *
 * The other three are covered, and were checked by mutation: dropping the
 * keyboard from the choices, dropping the store's load, and putting the
 * question back on the intent alone each make this fail.
 */
@RunWith(AndroidJUnit4.class)
public class SetupDialogTest {

    private static final String NAME = "uitest-setup.tap";
    private static final String PATH = "./" + NAME;

    /** What the record says. Two machines because that is the commonest value
     *  in ZXDB and the one that makes the question worth asking; the keymap
     *  because without one the keyboard is deliberately not offered. */
    private static final String MACHINE = "ZX-Spectrum 48K/128K";
    /** A real p2k layout, shaped like the one recorded from ScreenScraper:
     *  four directions, a fire button and a start key. */
    private static final String KEYMAP = "# Setup Test\\n0:left = z ;; left\\n"
            + "0:right = x\\n0:up = k\\n0:down = m\\n0:a = space\\n0:start = 1";

    /** Long enough for Fuse to start, the tape to be staged and the question
     *  to be asked - polled for rather than waited out. */
    private static final long APPEARS = 25 * Emulator.SECOND;
    private static final long LOOK = 500;

    private static final TapeProgram PROGRAM = new TapeProgram()
            .line(10, "GO TO 10")
            .startingAt(10);

    private final Emulator emulator = new Emulator();

    private Context context;
    private Uri document;

    private File store;
    private byte[] theirStore;
    private File answers;
    private byte[] theirAnswers;

    /** The profile list is the user's own, and applying the keyboard adds to
     *  it and selects what it added. */
    private String theirProfiles;
    private int theirCurrent;

    /**
     * <b>Nothing is launched here, and that is deliberate.</b>
     *
     * The activity is brought up by the intent itself, so that when this class
     * runs first in a process the game is opened by an activity that is still
     * being created - which is the state the timing half of this was broken
     * in, and the only way to reach it: instrumentation runs inside the app's
     * process, so Fuse cannot be stopped and started again once anything has
     * started it. See the note on the class.
     *
     * The ROMs are asked about as a file rather than by reading the screen -
     * there is no screen yet, and a skip must turn on a fact rather than on
     * what the app had got round to drawing.
     */
    @Before
    public void setUp() throws IOException {
        emulator.useDataFolder();
        assumeTrue("no ROMs in " + emulator.romFolder(), haveRoms());

        context = emulator.context();

        File root = Storage.root(context);
        assumeTrue("the data folder is not usable on this device: " + root,
                   root.isDirectory() && Storage.isWritable(root));

        store = new File(Storage.libraryDirectory(context), "metadata.json");
        theirStore = store.isFile() ? Files.readAllBytes(store.toPath()) : null;

        // Nobody has answered about this game: a remembered answer is applied
        // without asking, which is the whole point of it and would leave this
        // test asserting against a dialog that correctly never appeared.
        answers = new File(root, "setup.json");
        theirAnswers = answers.isFile() ? Files.readAllBytes(answers.toPath()) : null;
        answers.delete();

        writeStore();

        // The store does not read itself, and instrumentation runs in the
        // app's own process: a class that ran earlier in this suite leaves a
        // loaded copy behind, `ensureLoaded` then does nothing, and the file
        // just written is never read - so no record is found and the question
        // is correctly never asked. This passes alone and fails behind
        // DetailPaneTest, which is how it was found. The @After below already
        // does the same thing for whatever runs next.
        Metadata.refresh(context);

        SharedPreferences preferences = preferences();
        theirProfiles = preferences.getString(ControlProfiles.KEY_PROFILES, null);
        theirCurrent = preferences.getInt(ControlProfiles.KEY_CURRENT, 0);

        document = publish();
    }

    @After
    public void putItBack() throws IOException {
        if (document != null) {
            context.getContentResolver().delete(document, null, null);
        }

        restore(store, theirStore);
        restore(answers, theirAnswers);

        if (context != null) {
            SharedPreferences.Editor editor = preferences().edit()
                    .putInt(ControlProfiles.KEY_CURRENT, theirCurrent);

            if (theirProfiles == null) editor.remove(ControlProfiles.KEY_PROFILES);
            else editor.putString(ControlProfiles.KEY_PROFILES, theirProfiles);

            editor.commit();
        }

        // The in-memory copy has to be told, or whatever runs next in this
        // process reads this test's one game instead of the bench's.
        if (context != null) Metadata.refresh(context);
    }

    /**
     * The store this test needs: one game, written as text.
     *
     * <b>Not through {@code Metadata}, and that is the whole point.</b> The
     * store does not read itself - every screen that needs the facts asks for
     * them on a background thread - and writing this through the store's own
     * writer would leave the record in the in-memory copy as well as on disk.
     * The app would then find it without ever having read anything, and the
     * defect that made every route but the library's see an empty store would
     * pass unnoticed. What is wanted is a file nothing in this process has
     * read.
     *
     * The cost is that the format is written out here rather than borrowed:
     * if it moves, this fixture stops parsing and the dialog never appears,
     * which fails loudly. {@code MetadataStoreTest} is where the format itself
     * is pinned down.
     */
    private void writeStore() throws IOException {
        String json = "{\"version\":1,\"linked\":0,\"games\":{"
                + "\"" + PATH + "\":{"
                + "\"name\":\"Setup Test\","
                + "\"source\":\"" + Meta.ESDE + "\","
                + "\"machine\":\"" + MACHINE + "\","
                + "\"keymap\":\"" + KEYMAP + "\","
                + "\"inputs\":[\"Kempston Joystick\",\"Cursor\",\"Redefineable keys\"]"
                + "}}}";

        File directory = store.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("cannot make " + directory);
        }

        try (FileOutputStream out = new FileOutputStream(store)) {
            out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static void restore(File file, byte[] theirs) throws IOException {
        if (file == null) return;

        if (theirs == null) {
            file.delete();
            return;
        }

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(theirs);
        }
    }

    /**
     * The whole journey: the record becomes a question, and the answer sticks.
     *
     * <b>One test and not three, because the question is asked once per game
     * per session.</b> A Skip that is not remembered still stops it being
     * asked again - it has to, or applying a machine, which reopens the game,
     * would ask it again the moment it was answered. A second test method
     * against the same game therefore sees no dialog at all, which is the
     * guard working and looks exactly like the feature broken.
     *
     * The two machines are the point of the first half: "48K/128K" is ZXDB
     * saying either, so both are offered and neither is applied. They are
     * labelled with Fuse's own names - what every other screen calls them -
     * while the answer that gets written down is the id.
     *
     * The second half is the one that was missing when the keyboard option
     * first appeared: it selected keyboard mode and left the pad on whatever
     * profile was last used, so the layout the record carried - the only
     * reason the option is offered at all - was never read.
     */
    @Test
    public void therecordBecomesAquestionAndTheAnswerSticks() {
        view(document);

        // Waited for on the record's own words rather than on the word
        // "Machine": the quick bar has a Machine row, and the accessibility
        // tree carries the whole sheet whether it is up or not - so the
        // heading is a string that matches while no dialog exists at all.
        assertTrue("nothing ever asked how to run the game, quoting the record",
                   awaits(MACHINE));

        String[] ids = FuseNative.machineIds();
        String[] names = FuseNative.machineNames();

        List<Integer> offered = Suggested.machines(MACHINE, ids);
        assertEquals("the record names two machines, so two are offered: "
                     + Arrays.toString(ids), 2, offered.size());

        for (int which : offered) {
            assertTrue(names[which] + " is not offered, though the record names it",
                       offers(names[which]));
        }

        assertTrue("Kempston is not offered", offers("Kempston"));
        assertTrue("Cursor is not offered", offers("Cursor"));

        // The one choice that is not one of Fuse's: the pad sending the game's
        // own keys, offered only because the record carries a layout as well
        // as the fact that the game reads keys at all.
        assertTrue("the keyboard is not offered, though the record has a keymap",
                   offers(text(R.string.joystick_keyboard)));

        emulator.tap(text(R.string.joystick_keyboard));
        emulator.tap(text(R.string.suggest_apply));

        ControlProfiles.Profile now = awaitProfile("Setup Test");
        assertNotNull("no profile was made for the game: "
                      + preferences().getString(ControlProfiles.KEY_PROFILES, "none"),
                      now);

        assertEquals("left", KeyEvent.KEYCODE_Z, now.keys[FuseNative.JOYSTICK_LEFT]);
        assertEquals("right", KeyEvent.KEYCODE_X, now.keys[FuseNative.JOYSTICK_RIGHT]);
        assertEquals("up", KeyEvent.KEYCODE_K, now.keys[FuseNative.JOYSTICK_UP]);
        assertEquals("down", KeyEvent.KEYCODE_M, now.keys[FuseNative.JOYSTICK_DOWN]);
        assertEquals("fire", KeyEvent.KEYCODE_SPACE, now.keys[FuseNative.JOYSTICK_FIRE]);
        assertEquals("start", KeyEvent.KEYCODE_1, now.keys[ControlProfiles.BUTTON_1]);

        // What the layout says nothing about keeps the default it had.
        assertEquals("the third button was not left alone",
                     ControlProfiles.QAOPM[ControlProfiles.BUTTON_3],
                     now.keys[ControlProfiles.BUTTON_3]);

        assertEquals("the pad was not put on the keyboard",
                     Controls.JOYSTICK_KEYBOARD,
                     preferences().getInt(Prefs.KEY_JOYSTICK_TYPE, -1));
    }

    /** The profile in use, once it is the game's - applying goes through the
     *  dialog's own button and the write that follows is not instant. */
    private ControlProfiles.Profile awaitProfile(String named) {
        for (long waited = 0; waited < 10 * Emulator.SECOND; waited += LOOK) {
            ControlProfiles.Profile current = ControlProfiles.current(preferences());
            if (named.equals(current.name)) return current;

            SystemClock.sleep(LOOK);
        }
        return null;
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);
    }

    // --- the world this needs ------------------------------------------------

    private String text(int id) {
        return context.getString(id);
    }

    /**
     * Whether the dialog offers this as a choice.
     *
     * A radio button and not merely text on the screen. {@code isShowing}
     * searches the whole accessibility tree, which spans every window this app
     * has built - including the ☰ sheet, whose machine row carries the name of
     * the machine that is running. Asking for "Spectrum 48K" that way answers
     * yes with no dialog up at all, which is a test that cannot fail. Nothing
     * but this dialog has radio buttons in it.
     */
    private boolean offers(String label) {
        return emulator.device().wait(Until.findObject(
                By.clazz("android.widget.RadioButton").textContains(label)),
                Emulator.SECOND) != null;
    }

    /**
     * Polls until the dialog says something, rather than sleeping.
     *
     * How long this takes is not a constant: Fuse has to start, the tape has
     * to be copied out of the document and opened, and the question waits for
     * the machine to be up before it can name one. A fixed sleep long enough
     * for a cold emulator would be most of a minute, and a shorter one passes
     * alone and fails behind three other classes.
     */
    private boolean awaits(String what) {
        for (long waited = 0; waited < APPEARS; waited += LOOK) {
            if (emulator.isShowing(what)) return true;
            SystemClock.sleep(LOOK);
        }
        return false;
    }

    /**
     * A real document in Downloads, the only way a test can produce a content
     * URI without a provider of its own - see {@code RecentsTest}, which
     * explains the same trick at length.
     *
     * Its display name is the store record's filename, which is what {@code
     * Metadata.resolve} matches on for a document that arrived from outside
     * the library's own folder.
     */
    private Uri publish() throws IOException {
        dropAnyLeftOver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, NAME);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                   Environment.DIRECTORY_DOWNLOADS);

        Uri uri = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertNotNull("MediaStore would not take the file", uri);

        File tape = new File(context.getCacheDir(), NAME);
        PROGRAM.writeTo(tape, "setup");

        try (java.io.InputStream in = new java.io.FileInputStream(tape);
             OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            assertNotNull("cannot write to " + uri, out);

            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }

        return uri;
    }

    /** A run stopped by hand never reaches its {@code @After}, and MediaStore
     *  does not overwrite - it makes "uitest-setup (1).tap" instead, whose
     *  name matches no record and asks nothing. */
    private void dropAnyLeftOver() {
        String[] projection = { MediaStore.MediaColumns._ID };
        String where = MediaStore.MediaColumns.DISPLAY_NAME + " = ?";

        try (android.database.Cursor found = context.getContentResolver()
                .query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                       where, new String[] { NAME }, null)) {
            if (found == null) return;

            while (found.moveToNext()) {
                Uri stale = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, found.getLong(0));
                context.getContentResolver().delete(stale, null, null);
            }
        }
    }

    /**
     * The same intent a file manager sends, so it takes the same path in -
     * and, when nothing has run yet, the thing that brings the activity up.
     *
     * On the display this test can see: left to itself the app can land on a
     * bench's second screen, where the dialog is real, correct and invisible
     * to everything that looks.
     */
    private void view(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                .setPackage(context.getPackageName())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startActivity(intent, Screen.here());
    }

    /** Whether the bench has ROMs at all, which is a fact about the disk and
     *  not about what the app has drawn yet. */
    private boolean haveRoms() {
        File[] roms = new File(emulator.romFolder()).listFiles(
                (dir, name) -> name.toLowerCase(java.util.Locale.US).endsWith(".rom"));

        return roms != null && roms.length > 0;
    }
}
