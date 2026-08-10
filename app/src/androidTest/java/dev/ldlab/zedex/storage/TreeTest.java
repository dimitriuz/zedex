package dev.ldlab.zedex.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNotNull;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writing into somebody's own folder through the grant they gave.
 *
 * The route that makes this work on the Play build, which has no All-files
 * access - the same one ES-DE's two files already take. On a device because
 * there is no SAF anywhere else.
 *
 * The test needs a content folder to have been granted; without one it skips
 * rather than failing, since a bench with no folder chosen is a setup fact and
 * not a defect. It writes under a folder of its own and removes it afterwards.
 *
 * A folder that <em>is</em> granted but read-only is a different thing from a
 * folder that is absent, and must not be treated the same way: skipping there
 * would be the {@code NewDiskTest} mistake again - a bench that never runs the
 * real assertions reporting green in twenty seconds. So a granted-but-read-only
 * tree fails setUp outright, loudly, with what to do about it - see below.
 */
@RunWith(AndroidJUnit4.class)
public class TreeTest {

    private static final String SCRATCH = "zedex-tree-test";

    private Context context;
    private Uri tree;
    private File source;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        tree = Storage.contentFolder(context);
        assumeNotNull("no content folder granted on this device", tree);

        // A read-only grant is a setup fact too, but not one to skip past:
        // unlike "no folder at all", somebody chose this folder and the app
        // told them nothing was wrong with it. Failing loudly here is what
        // stops every test below from either throwing on the first write or,
        // worse, "passing" on the vacuous null-equals-null the create path
        // leaves behind when it cannot make anything.
        if (!Tree.canWrite(context, tree)) {
            fail("content folder is granted read-only (Tree.canWrite says no) - "
                    + "re-pick it through the app (Settings > content folder, or "
                    + "Library's folder picker) so the grant includes write, "
                    + "then re-run this class");
        }

        source = new File(context.getCacheDir(), "tree-test.tap");
        try (FileOutputStream out = new FileOutputStream(source)) {
            out.write("not really a tape".getBytes(StandardCharsets.US_ASCII));
        }
    }

    @After
    public void tidyUp() {
        if (source != null) source.delete();
        if (tree == null) return;

        Uri scratch = Tree.find(context, Tree.folder(context, tree), SCRATCH);
        if (scratch != null) {
            try {
                android.provider.DocumentsContract.deleteDocument(
                        context.getContentResolver(), scratch);
            } catch (Exception ignored) {
                // A leftover folder is untidy, not a failure - and deleting
                // somebody's content folder because a delete went wide is
                // very much worse than untidy.
            }
        }
    }

    /** setUp already failed loudly if this were false; pinning it here too
     *  means the interface itself is under test, not just its consequence. */
    @Test
    public void canWriteIsTrueOnTheGrantThisBenchNeeds() {
        assertTrue(Tree.canWrite(context, tree));
    }

    /** A folder that is not there is made. */
    @Test
    public void afolderIsCreatedWhenItIsAbsent() {
        Uri made = Tree.folder(context, tree, SCRATCH);

        assertNotNull("the folder was not created", made);
        assertNotNull(Tree.find(context, Tree.folder(context, tree), SCRATCH));
    }

    /**
     * And a second call finds the first one rather than making a second.
     *
     * SAF will happily create two documents with the same display name in the
     * same folder - the id is what is unique, not the name - so a
     * find-or-create that only creates gives somebody "Games" and "Games (1)"
     * and splits their imports across both.
     */
    @Test
    public void asecondCallFindsRatherThanMakesAsecond() {
        Uri first = Tree.folder(context, tree, SCRATCH);
        Uri second = Tree.folder(context, tree, SCRATCH);

        assertEquals("a second folder of the same name was made", first, second);
    }

    /** Several levels in one call, which is what Downloaded/Games/ is. */
    @Test
    public void afolderSeveralLevelsDownIsMadeInOneCall() {
        Uri deep = Tree.folder(context, tree, SCRATCH, "Downloaded", "Games");

        assertNotNull(deep);
        assertNotNull(Tree.find(context, Tree.folder(context, tree, SCRATCH, "Downloaded"),
                                "Games"));
    }

    /** A file written through the grant is there afterwards and has the
     *  bytes. */
    @Test
    public void afileIsWrittenAndCanBeReadBack() throws Exception {
        Uri into = Tree.folder(context, tree, SCRATCH);

        Uri written = Tree.write(context, into, "written.tap", source);

        assertNotNull(written);
        assertEquals(source.length(), lengthOf(written));
    }

    /** Nothing there is null rather than a throw - "is it already there" is a
     *  question the importer asks about every file. */
    @Test
    public void findingNothingIsNull() {
        Uri into = Tree.folder(context, tree, SCRATCH);

        assertNull(Tree.find(context, into, "nothing-of-that-name.tap"));
    }

    private long lengthOf(Uri document) throws Exception {
        try (android.os.ParcelFileDescriptor descriptor =
                     context.getContentResolver().openFileDescriptor(document, "r")) {
            return descriptor == null ? -1 : descriptor.getStatSize();
        }
    }
}
