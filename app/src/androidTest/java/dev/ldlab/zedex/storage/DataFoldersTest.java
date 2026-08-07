package dev.ldlab.zedex.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * That every folder in the data folder is one the app knows how to move.
 *
 * This is the invariant that broke. The list of what lives in the data folder
 * was written out by hand in three places - {@code createFolders},
 * {@code hideFromGallery} and {@code SettingsActivity.moveData} - and they had
 * drifted apart: {@code moveData} was missing {@code cards}, and all three were
 * missing {@code library}, which {@code Metadata} had built a path to out of a
 * private constant of its own.
 *
 * So changing the data folder left the scraped metadata store behind. What made
 * it expensive to find is that nothing failed: {@code Metadata.store} reads a
 * missing file as an <em>empty</em> store, so the app answered "nothing known
 * about this game" and "never linked" rather than "the store is gone", while
 * artwork - which comes from ES-DE's own media folder and never touches the
 * store - carried on working and made the link look like it had succeeded.
 *
 * A test rather than a comment because the failure mode is silence, and the way
 * it recurs is somebody adding a seventh folder.
 */
@RunWith(AndroidJUnit4.class)
public class DataFoldersTest {

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** The names and the folders are the same list, in the same order. */
    @Test
    public void namesAndFoldersCorrespond() {
        Context context = context();

        String[] names = Storage.dataFolderNames();
        File[] folders = Storage.dataFolders(context);

        assertEquals("dataFolderNames and dataFolders are different lengths,"
                     + " so moveData would pair a folder with the wrong name",
                     names.length, folders.length);

        for (int i = 0; i < names.length; i++) {
            assertEquals("entry " + i + " of the two lists disagrees",
                         names[i], folders[i].getName());
        }
    }

    /** Every one of them really is inside the data folder. */
    @Test
    public void everyFolderIsUnderTheDataRoot() {
        Context context = context();
        String root = Storage.root(context).getAbsolutePath();

        for (File folder : Storage.dataFolders(context)) {
            assertEquals("moveData moves this by name from the previous root,"
                         + " so it has to be a direct child of the root",
                         root, folder.getParentFile().getAbsolutePath());
        }
    }

    /**
     * The metadata store's own folder is in the list.
     *
     * The specific regression: {@code Metadata} used to name this folder
     * itself, so nothing moved it and a change of data folder silently
     * unlinked the library.
     */
    @Test
    public void theMetadataStoreIsMoved() {
        Context context = context();
        File library = Storage.libraryDirectory(context);

        List<String> named = new ArrayList<>();
        for (File folder : Storage.dataFolders(context)) {
            named.add(folder.getAbsolutePath());
        }

        assertTrue("Storage.libraryDirectory is not in dataFolders, so changing"
                   + " the data folder leaves gamelist.xml behind and the"
                   + " library reads as never linked: " + named,
                   named.contains(library.getAbsolutePath()));
    }
}
