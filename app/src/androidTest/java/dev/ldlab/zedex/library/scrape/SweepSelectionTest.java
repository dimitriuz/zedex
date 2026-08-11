package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Which games a run is about.
 *
 * The question this answers is load-bearing twice over: it is the filter
 * somebody chooses, and - because there is deliberately no stored progress -
 * it is also how a run that was cancelled, or that met a spent allowance, is
 * carried on afterwards. Getting {@link Sweep.Only#NOT_SCRAPED} wrong would
 * not merely be a poor default; it would mean resuming did nothing.
 */
@RunWith(AndroidJUnit4.class)
public class SweepSelectionTest {

    private static final String FOLDER = "zedex-select-test";

    private Context context;
    private File store;
    private byte[] theirs;
    private final List<File> wrote = new ArrayList<>();

    @Before
    public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();

        assumeTrue("no content folder is granted on this device",
                   Storage.contentFolder(context) != null);

        File root = Storage.root(context);
        assumeTrue("the data folder is not usable on this device: " + root,
                   root.isDirectory() && Storage.isWritable(root));

        store = new File(Storage.libraryDirectory(context), "metadata.json");
        theirs = store.isFile() ? Files.readAllBytes(store.toPath()) : null;

        Metadata.clear(context);
        Artwork.forget();
    }

    @After
    public void putItBack() throws IOException {
        for (File file : wrote) file.delete();

        if (store == null) return;

        if (theirs == null) {
            store.delete();
        } else {
            try (FileOutputStream out = new FileOutputStream(store)) {
                out.write(theirs);
            }
        }
        Metadata.refresh(context);
        Artwork.forget();
    }

    // --- the world -------------------------------------------------------------------

    private Entry game(String name) {
        return row(Entry.Kind.FILE, name);
    }

    private Entry row(Entry.Kind kind, String name) {
        Uri root = Storage.contentFolder(context);
        String rootId = DocumentsContract.getDocumentId(root);

        Uri uri = DocumentsContract.buildDocumentUriUsingTree(
                root, rootId + "/" + FOLDER + "/" + name);

        return new Entry(kind, name, uri, null, 1024, 0);
    }

    private String pathOf(Entry entry) {
        return Metadata.relativePath(context, entry.uri);
    }

    private List<String> namesOf(List<Entry> entries) {
        List<String> names = new ArrayList<>();
        for (Entry entry : entries) names.add(entry.name);
        return names;
    }

    private List<Entry> select(Sweep.Only only, List<Entry> entries) {
        return Sweep.select(context, entries, only);
    }

    private void store(Entry entry, String source) {
        Metadata.put(context,
                     Meta.at(pathOf(entry)).name(entry.name).source(source).build());
        Metadata.refresh(context);
    }

    private void giveItACover(Entry entry) throws IOException {
        File cover = Artwork.fileFor(context, pathOf(entry), "covers", "png");
        wrote.add(cover);

        try (FileOutputStream out = new FileOutputStream(cover)) {
            out.write(new byte[] { 1, 2, 3, 4 });
        }
        Artwork.forget(pathOf(entry));
    }

    // --- not scraped yet ----------------------------------------------------------------

    /**
     * The default, and the resume: anything this app has not itself scraped.
     *
     * Deliberately <b>not</b> "has no metadata". On a collection linked to
     * ES-DE the store is already mostly full, so that question would match
     * almost nothing and a first run would appear to do nothing at all - and
     * since this same filter is how a stopped run is carried on, it would
     * break resuming too. ES-DE's rows are exactly what somebody scraping is
     * trying to improve on, so they count as not scraped.
     */
    @Test
    public void notScrapedYetMeansNotScrapedByUs() {
        Entry nothing = game("Nothing.tap");
        Entry fromEsde = game("Esde.tap");
        Entry scraped = game("Scraped.tap");

        store(fromEsde, Meta.ESDE);
        store(scraped, "ScreenScraper");

        List<String> chosen = namesOf(select(Sweep.Only.NOT_SCRAPED,
                                             Arrays.asList(nothing, fromEsde, scraped)));

        assertTrue("a game with no row at all", chosen.contains("Nothing.tap"));
        assertTrue("an ES-DE row is what scraping improves on", chosen.contains("Esde.tap"));
        assertFalse("a row this app scraped is done", chosen.contains("Scraped.tap"));
    }

    /** A row with no source at all reads as ES-DE's, which is what an older
     *  store is full of. */
    @Test
    public void arowWithNoSourceCountsAsNotScraped() {
        Entry old = game("Old.tap");
        store(old, null);

        assertEquals(1, select(Sweep.Only.NOT_SCRAPED, Collections.singletonList(old)).size());
    }

    /**
     * A hand-edited row is offered rather than filtered out.
     *
     * A fill-gaps scrape can only add to it, never undo the correction, so
     * dropping it here instead would be quieter but would mean a source that
     * has something new to add never gets the chance. Costs no request
     * either way, since the check happens before the search.
     */
    @Test
    public void ahandEditedRowIsOfferedSoThatTheTallyCanMentionIt() {
        Entry mine = game("Mine.tap");
        store(mine, Meta.USER);

        assertEquals(1, select(Sweep.Only.NOT_SCRAPED, Collections.singletonList(mine)).size());
    }

    // --- no picture ----------------------------------------------------------------------

    /** What "games with no image" means to somebody looking at the grid: no
     *  picture in any folder, which is what the grid itself draws. */
    @Test
    public void nopictureIsAboutWhatTheGridCanDraw() throws IOException {
        Entry bare = game("Bare.tap");
        Entry covered = game("Covered.tap");

        giveItACover(covered);

        List<String> chosen = namesOf(select(Sweep.Only.NO_PICTURE,
                                             Arrays.asList(bare, covered)));

        assertEquals(Collections.singletonList("Bare.tap"), chosen);
    }

    /** And says nothing about the facts - a fully scraped row with no cover is
     *  precisely who this filter is for. */
    @Test
    public void nopictureIgnoresWhateverTheStoreSays() {
        Entry described = game("Described.tap");
        store(described, "ScreenScraper");

        assertEquals(1, select(Sweep.Only.NO_PICTURE,
                               Collections.singletonList(described)).size());
    }

    // --- everything -----------------------------------------------------------------------

    @Test
    public void everythingTakesTheLotWhateverIsAlreadyKnown() throws IOException {
        Entry scraped = game("Scraped.tap");
        store(scraped, "ScreenScraper");
        giveItACover(scraped);

        assertEquals(2, select(Sweep.Only.EVERYTHING,
                               Arrays.asList(scraped, game("Bare.tap"))).size());
    }

    // --- what is not a game -----------------------------------------------------------------

    /**
     * Folders and archives are dropped, not counted and then skipped.
     *
     * A total that included them would promise twenty minutes of work over
     * three hundred things when only two hundred of them can be scraped at
     * all, and the estimate in front of somebody deciding is the whole reason
     * {@code select} is a separate call.
     *
     * An archive is excluded for the same reason the one-game menu row does
     * not offer itself on one: a zip has no path the store can key a game by.
     */
    @Test
    public void foldersAndArchivesAreNotGames() {
        List<Entry> chosen = select(Sweep.Only.EVERYTHING, Arrays.asList(
                row(Entry.Kind.FOLDER, "Arcade"),
                row(Entry.Kind.ARCHIVE, "Bundle.zip"),
                game("Real.tap")));

        assertEquals(Collections.singletonList("Real.tap"), namesOf(chosen));
    }

    /** Nor is anything the store cannot name - a document from some other
     *  grant, which resolves to no path at all. */
    @Test
    public void arowWithNoPathOfItsOwnIsDropped() {
        Entry elsewhere = new Entry(Entry.Kind.FILE, "Elsewhere.tap",
                                    Uri.parse("content://somebody.else/document/7"),
                                    null, 1024, 0);

        assertTrue(select(Sweep.Only.EVERYTHING,
                          Collections.singletonList(elsewhere)).isEmpty());
    }
}
