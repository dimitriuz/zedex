package dev.ldlab.zedex;

import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The cheats a scrape fetched, on the page somebody opens to use them.
 *
 * The bundled database matches the md5 of the loaded file and knows about a
 * third of a real collection. This is the other two thirds: a {@code .pok}
 * fetched for the game and found by the store's own key. {@code
 * ScrapedPokesTest} covers finding and parsing it; what needs the app running
 * is the part in between - that the screen tells the cheats page which game is
 * loaded, and that the page looks.
 *
 * <b>The tape is one this test makes, which is the point.</b> A fresh file has
 * an md5 the bundled database has never seen, so the database finds nothing
 * and the scraped section is the only thing that can fill the page. Using a
 * real game would prove nothing: the database would answer first.
 */
@RunWith(AndroidJUnit4.class)
public class ScrapedCheatsTest {

    private static final String NAME = "uitest-cheats.tap";

    /** The store's own key for it, sent with the intent exactly as the
     *  library sends it - so no store record is needed at all. */
    private static final String PATH = "./" + NAME;

    /** A cheat with a name nothing else could produce, so finding it on the
     *  page means it came from this file. */
    private static final String CHEAT = "Uitest infinite lives";

    /** The archive's own format, which is what the parser reads. */
    private static final String POK = "N" + CHEAT + "\nZ 8 32768 0 0\nY\n";

    private static final long SETTLES = 700;

    private static final TapeProgram PROGRAM = new TapeProgram()
            .line(10, "GO TO 10")
            .startingAt(10);

    private final Emulator emulator = new Emulator();

    private Context context;
    private Uri document;
    private File pokes;

    @Before
    public void setUp() throws IOException {
        emulator.useDataFolder();
        context = emulator.context();

        File root = Storage.root(context);
        assumeTrue("the data folder is not usable on this device: " + root,
                   root.isDirectory() && Storage.isWritable(root));

        emulator.launch();
        assumeTrue("no ROMs in " + emulator.romFolder(), !emulator.needsRoms());

        // Written by this process, which is the app's - a file anything else
        // put there is not necessarily one the app can read. See CLAUDE.md.
        pokes = Artwork.fileFor(context, PATH, "pokes", "pok");
        try (FileOutputStream out = new FileOutputStream(pokes)) {
            out.write(POK.getBytes(StandardCharsets.UTF_8));
        }

        document = publish();
    }

    @After
    public void tearDown() {
        emulator.closeMenu();

        if (document != null) context.getContentResolver().delete(document, null, null);
        if (pokes != null) pokes.delete();
    }

    /**
     * A game with a fetched {@code .pok} offers its cheats.
     *
     * Opened the way the library opens one - the intent carries the store's
     * key beside the document - because that is what tells the cheats page
     * which game to look for. Without it the page is correct and empty, which
     * is indistinguishable from a game nobody has scraped.
     */
    @Test
    public void afetchedPokFileFillsTheCheatsPage() {
        view(document);
        emulator.idle(6 * Emulator.SECOND);

        emulator.menu("Pokes");

        assertTrue("the fetched cheat is not offered",
                   emulator.isInMenu(CHEAT));

        // Upper-cased because that is how a section heading is drawn -
        // MenuDrawer.addSection does it with Locale.ROOT - and matching is
        // exact. PokesTest asserts on "GAMES" for the same reason.
        assertTrue("the page does not say where these came from",
                   emulator.isInMenu(context.getString(R.string.poke_scraped)
                                            .toUpperCase(java.util.Locale.ROOT)));
    }

    // --- the world this needs ------------------------------------------------

    /** The same intent the library sends: the document, and the store's own
     *  key beside it. */
    private void view(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                .setPackage(context.getPackageName())
                .putExtra(EmulatorActivity.EXTRA_LIBRARY_PATH, PATH)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startActivity(intent, Screen.here());
        SystemClock.sleep(2 * SETTLES);
    }

    /** A real document, the only way a test can produce a content URI - see
     *  {@code RecentsTest}, which explains the trick at length. */
    private Uri publish() throws IOException {
        dropAnyLeftOver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, NAME);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        Uri uri = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertNotNull("MediaStore would not take the file", uri);

        File tape = new File(context.getCacheDir(), NAME);
        PROGRAM.writeTo(tape, "cheats");

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
     *  makes "uitest-cheats (1).tap" rather than overwriting. */
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
}
