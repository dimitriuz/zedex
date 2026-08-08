package dev.ldlab.zedex;

import dev.ldlab.zedex.screen.SettingsActivity;
import dev.ldlab.zedex.storage.Recents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * The recent files list, and the staging that fills it.
 *
 * <b>Through a real {@code content://} URI</b>, which is the part worth testing.
 * Everything a user opens arrives as a document, and Fuse opens files by path, so
 * {@code Media.stage()} sits in between: it copies the bytes into the cache under
 * the document's own name, takes the md5 on the way past, remembers the origin so
 * a disk can be written back, and takes a persistable grant so the list outlives
 * the launch. Four things that only happen on that path.
 *
 * The picker cannot be driven from a test, so the document is made rather than
 * chosen: MediaStore hands out a real URI for a file in Downloads, and an
 * ACTION_VIEW intent carrying it goes through exactly the same code as a file
 * handed over by a file manager. {@code Emulator.open}, which the other tests
 * use, would miss all of it — it calls Fuse directly and never stages anything.
 */
@RunWith(AndroidJUnit4.class)
public class RecentsTest {

    private static final String NAME = "uitest-recent.tap";

    /** Fuse's own numbering: the tape sets a border so the load can be seen. */
    private static final int GREEN = 4;

    /**
     * Autostarted, so opening the tape is the whole of it: with autoload on, Fuse
     * types LOAD "" itself and the program runs and sets the border.
     */
    private static final TapeProgram PROGRAM = new TapeProgram()
            .line(10, "BORDER 4")
            .line(20, "GO TO 20")
            .startingAt(10);

    private final Emulator emulator = new Emulator();

    private Uri document;

    @Before
    public void setUp() throws IOException {
        emulator.useDataFolder();
        emulator.launch();
        assumeFalse("no ROMs in " + emulator.romFolder(), emulator.needsRoms());

        forget();
        document = publish();
    }

    /**
     * Takes out a document of this name that an earlier run left behind.
     *
     * tearDown deletes it, and a run that is killed part way through never
     * reaches tearDown - a suite stopped by hand, an install landing on top of
     * it. MediaStore does not overwrite: asked for a name that is taken, it
     * makes "uitest-recent (1).tap" instead, and the assertion that the recent
     * was remembered under its own name then fails with what reads like a
     * naming bug in the app. The world this test needs includes the absence of
     * its own leftovers.
     */
    private void dropAnyLeftOver() {
        String[] projection = { MediaStore.MediaColumns._ID };
        String where = MediaStore.MediaColumns.DISPLAY_NAME + " = ?";

        try (android.database.Cursor found = emulator.context().getContentResolver()
                .query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                       where, new String[] { NAME }, null)) {
            if (found == null) return;

            while (found.moveToNext()) {
                Uri stale = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, found.getLong(0));
                emulator.context().getContentResolver().delete(stale, null, null);
            }
        }
    }

    @After
    public void tearDown() {
        if (document != null) {
            emulator.context().getContentResolver().delete(document, null, null);
        }
        forget();
    }

    @Test
    public void openingADocumentStagesItAndRemembersIt() {
        emulator.menu("Machine", "Reset", "Reset");
        emulator.idle(Emulator.BOOT);

        view(document);
        emulator.idle(10 * Emulator.SECOND);

        assertEquals("the tape did not load and run",
                     GREEN, emulator.borderColour());

        List<Recents.Item> remembered = Recents.all(preferences());
        assertEquals("one file should have been remembered", 1, remembered.size());
        assertEquals("remembered under the wrong name",
                     NAME, remembered.get(0).name);
        assertEquals("remembered the wrong document",
                     document, remembered.get(0).uri);

        // And it is offered: the row is what makes the list worth having.
        emulator.menu("Open recent");
        assertTrue("the recent list does not offer " + NAME,
                   emulator.isShowing(NAME));
        emulator.closeMenu();
    }

    /**
     * Opening the same document twice moves it up rather than listing it twice,
     * which is the one rule the list has.
     */
    @Test
    public void openingItAgainDoesNotListItTwice() {
        view(document);
        emulator.idle(8 * Emulator.SECOND);
        view(document);
        emulator.idle(8 * Emulator.SECOND);

        assertEquals("the same file was remembered twice",
                     1, Recents.all(preferences()).size());
    }

    // --- making a document to open -------------------------------------------

    /**
     * A real document in Downloads, through MediaStore.
     *
     * Not a {@code file://} URI: the app reads what it is handed with
     * openInputStream, and a file URI would be rejected before it got that far.
     * MediaStore is the one way a test can produce a content URI without a
     * provider of its own.
     */
    private Uri publish() throws IOException {
        Context context = emulator.context();

        dropAnyLeftOver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, NAME);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                   Environment.DIRECTORY_DOWNLOADS);

        Uri uri = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertNotNull("MediaStore would not take the file", uri);

        java.io.File tape = new java.io.File(context.getCacheDir(), NAME);
        PROGRAM.writeTo(tape, "recent");

        try (java.io.InputStream in = new java.io.FileInputStream(tape);
             OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            assertNotNull("cannot write to " + uri, out);

            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }

        return uri;
    }

    /** The same intent a file manager sends, so it takes the same path in. */
    private void view(Uri uri) {
        Context context = emulator.context();

        Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                .setPackage(context.getPackageName())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startActivity(intent);
    }

    private SharedPreferences preferences() {
        return emulator.context().getSharedPreferences(
                SettingsActivity.PREFS, Context.MODE_PRIVATE);
    }

    private void forget() {
        preferences().edit().remove(Recents.KEY_RECENTS).commit();
    }
}
