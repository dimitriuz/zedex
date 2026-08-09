package dev.ldlab.zedex.library.meta;

import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

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
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * The app's own media folder, and {@link Artwork} preferring it to ES-DE's.
 *
 * The first piece of scraping: there was nowhere to put a fetched cover and
 * nothing that would find it. {@code Artwork} resolved only from ES-DE's
 * {@code downloaded_media}, through ES-DE's grant, and the app had never
 * written a byte into it.
 *
 * Only our own half is exercised here. ES-DE's is reached through a grant this
 * bench may or may not hold and a folder belonging to another app - {@code
 * EsDeMergeTest} is where their side is driven, and putting files into their
 * media tree to prove a precedence rule is not something a test should do to
 * somebody's frontend. What that leaves untested is stated at the bottom.
 *
 * Everything written here goes under a game path no collection has, and is
 * removed afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class OwnMediaTest {

    /** A path nothing real could collide with, including the subfolder that
     *  makes it worth testing at all. */
    private static final String GAME = "./zedex-test/Not A Real Game.tap";

    private Context context;
    private final java.util.List<File> made = new java.util.ArrayList<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();

        File root = Storage.root(context);
        assumeTrue("the data folder is not usable on this device: " + root,
                   root.isDirectory() && Storage.isWritable(root));

        Artwork.forget();
    }

    @After
    public void tidyUp() {
        for (File file : made) file.delete();
        made.clear();

        new File(Storage.mediaDirectory(context), "covers/zedex-test").delete();
        new File(Storage.mediaDirectory(context), "screenshots/zedex-test").delete();
        new File(Storage.mediaDirectory(context), "videos/zedex-test").delete();
        new File(Storage.mediaDirectory(context), "manuals/zedex-test").delete();

        Artwork.forget();
    }

    /** Writes something real - {@code ours} tests length as well as
     *  readability, so a zero-byte file is deliberately not a hit. */
    private File put(String folder, String extension) throws IOException {
        File file = Artwork.fileFor(context, GAME, folder, extension);
        made.add(file);

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[] { 1, 2, 3, 4 });
        }
        Artwork.forget(GAME);
        return file;
    }

    // --- the folder is one of the data folders -----------------------------------

    /**
     * {@code media} is in {@link Storage#dataFolderNames}, and that is not a
     * detail.
     *
     * Two things depend on it and both fail silently. A data-folder change
     * moves what is in that list and leaves behind what is not - CLAUDE.md
     * records the same list missing cards and library, which orphaned a DivMMC
     * image and the whole metadata store. And {@code hideFromGallery} drops a
     * {@code .nomedia} into what is in that list; without one, eight hundred
     * scraped covers turn up in the phone's photo gallery.
     */
    @Test
    public void themediaFolderIsOneOfTheDataFoldersThatMove() {
        List<String> names = Arrays.asList(Storage.dataFolderNames());

        assertTrue("media is not in dataFolderNames, so a data-folder change would "
                   + "leave every scraped cover behind - and nothing would put a "
                   + ".nomedia in it either: " + names,
                   names.contains("media"));

        boolean listed = false;
        for (File folder : Storage.dataFolders(context)) {
            if (folder.equals(Storage.mediaDirectory(context))) listed = true;
        }
        assertTrue("media is not in dataFolders, so createFolders and "
                   + "hideFromGallery both skip it", listed);
    }

    /** And it is inside the data folder, not somewhere of its own - so it
     *  travels with everything else and needs no grant. */
    @Test
    public void themediaFolderIsInsideTheDataFolder() {
        assertEquals(Storage.root(context), Storage.mediaDirectory(context).getParentFile());
    }

    // --- writing and finding again ------------------------------------------------

    /**
     * What {@code fileFor} names, {@code picture} finds.
     *
     * The two have to agree or a scrape appears to do nothing at all: the file
     * is on disk, the library shows no cover, and there is no error anywhere
     * to say why.
     */
    @Test
    public void whatfileForNamesIsWhatArtworkThenFinds() throws IOException {
        File written = put("covers", "png");

        Uri found = Artwork.picture(context, GAME);

        assertNotNull("the cover just written was not found", found);
        assertEquals(written.getAbsolutePath(), found.getPath());
    }

    /** A game in a subfolder keeps its subfolder, or two games of the same
     *  name in different folders would land on one file. */
    @Test
    public void agameInASubfolderKeepsIt() throws IOException {
        File written = put("covers", "png");

        assertTrue("the subfolder was flattened: " + written,
                   written.getAbsolutePath().contains("/covers/zedex-test/"));
    }

    /** The leading "./" of ES-DE's own key is not part of the path on disk. */
    @Test
    public void theEsDeStyleKeyDoesNotBecomeADotFolder() {
        File file = Artwork.fileFor(context, GAME, "covers", "png");

        assertTrue("the ./ was kept and made a folder called '.': " + file,
                   !file.getAbsolutePath().contains("/./"));
    }

    /** jpg as readily as png - a scraper takes whatever the provider has. */
    @Test
    public void ajpgIsFoundTheSameWayApngIs() throws IOException {
        File written = put("covers", "jpg");

        assertEquals(written.getAbsolutePath(), Artwork.picture(context, GAME).getPath());
    }

    /** A file with nothing in it is not a picture. An interrupted download
     *  leaves exactly that, and treating it as a hit means a broken image with
     *  no way to retry. */
    @Test
    public void anEmptyFileIsNotAHit() throws IOException {
        File file = Artwork.fileFor(context, GAME, "covers", "png");
        made.add(file);
        assertTrue(file.createNewFile());
        Artwork.forget(GAME);

        assertNull("a zero-length cover was taken for a real one",
                   Artwork.picture(context, GAME));
    }

    // --- the cover comes first ---------------------------------------------------------

    /** {@code picture} answers the cover when there is one, whatever else is
     *  there - every row and tile draws whatever this returns. */
    @Test
    public void thecoverWinsOverAScreenshot() throws IOException {
        put("screenshots", "png");
        File cover = put("covers", "png");

        assertEquals(cover.getAbsolutePath(), Artwork.picture(context, GAME).getPath());
    }

    /** And the gallery shows both, cover first. */
    @Test
    public void thegalleryShowsEveryFolderInOrder() throws IOException {
        File cover = put("covers", "png");
        File shot = put("screenshots", "png");

        List<Uri> all = Artwork.pictures(context, GAME);

        assertEquals(2, all.size());
        assertEquals(cover.getAbsolutePath(), all.get(0).getPath());
        assertEquals(shot.getAbsolutePath(), all.get(1).getPath());
    }

    /** One per folder, not one per extension - a cover scraped as both is
     *  still one cover. */
    @Test
    public void onefolderContributesOnePicture() throws IOException {
        put("covers", "png");
        put("covers", "jpg");

        assertEquals(1, Artwork.pictures(context, GAME).size());
    }

    // --- video and manuals ---------------------------------------------------------------

    @Test
    public void avideoIsFoundInOurOwnFolder() throws IOException {
        File written = put("videos", "mp4");

        assertEquals(written.getAbsolutePath(), Artwork.video(context, GAME).getPath());
    }

    @Test
    public void amanualIsFoundInOurOwnFolder() throws IOException {
        File written = put("manuals", "pdf");

        assertEquals(written.getAbsolutePath(), Artwork.manual(context, GAME).getPath());
    }

    /** A game with nothing has nothing, in all four answers - most of a
     *  collection, and the answer this is asked for most often. */
    @Test
    public void agameWithNothingHasNothing() {
        assertNull(Artwork.picture(context, GAME));
        assertNull(Artwork.video(context, GAME));
        assertNull(Artwork.manual(context, GAME));
        assertTrue(Artwork.pictures(context, GAME).isEmpty());
    }

    // --- the cache ---------------------------------------------------------------------------

    /**
     * A miss is remembered, so writing a cover is not seen until something
     * says to look again - which is exactly why {@link Artwork#forget(String)}
     * exists and why a scraper has to call it.
     */
    @Test
    public void amissIsCachedUntilTheGameIsForgotten() throws IOException {
        assertNull(Artwork.picture(context, GAME));

        File file = Artwork.fileFor(context, GAME, "covers", "png");
        made.add(file);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[] { 1, 2, 3, 4 });
        }

        assertNull("the miss should still be cached", Artwork.picture(context, GAME));

        Artwork.forget(GAME);

        assertNotNull("forgetting the game did not make the new cover visible",
                      Artwork.picture(context, GAME));
    }

    /** And forgetting one game leaves another alone - the whole reason it
     *  takes a path rather than clearing five hundred entries per game
     *  scraped. */
    @Test
    public void forgettingOneGameKeepsWhatWasKnownAboutAnother() throws IOException {
        String other = "./zedex-test/Another One.tap";
        File otherFile = Artwork.fileFor(context, other, "covers", "png");
        made.add(otherFile);
        try (FileOutputStream out = new FileOutputStream(otherFile)) {
            out.write(new byte[] { 1, 2, 3, 4 });
        }

        put("covers", "png");
        assertNotNull(Artwork.picture(context, GAME));
        assertNotNull(Artwork.picture(context, other));

        Artwork.forget(GAME);

        // Still answered, and still right - the point is that it was not
        // dropped, which nothing here can observe directly.
        assertEquals(otherFile.getAbsolutePath(), Artwork.picture(context, other).getPath());
    }

    // --- what this does not cover ------------------------------------------------------------

    /**
     * Not tested here: that ours beats ES-DE's for the same folder.
     *
     * Proving it needs a file inside ES-DE's own media tree, which means
     * either a bench that happens to have one for a chosen game - not
     * something to depend on - or this test writing into another app's data
     * to make a point. The rule is one line in {@code Artwork.inFolder}: ours
     * is asked first and returns, theirs is only reached when ours answers
     * nothing. Everything above proves the first half of that; the second is
     * ES-DE's existing behaviour, unchanged, which {@code FilterTest} and the
     * pane exercise on every run against a real scraped collection.
     */
    @Test
    public void oursIsAskedBeforeEsDes() throws IOException {
        // The observable part: with ours present, the answer is ours - on a
        // bench where ES-DE may well have artwork for other games in the very
        // same folders.
        File cover = put("covers", "png");

        assertEquals(cover.getAbsolutePath(), Artwork.picture(context, GAME).getPath());
    }
}
