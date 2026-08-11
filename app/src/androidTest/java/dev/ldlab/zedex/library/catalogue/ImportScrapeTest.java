package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.library.scrape.Candidate;
import dev.ldlab.zedex.library.scrape.Downloads;
import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.scrape.Medium;
import dev.ldlab.zedex.library.scrape.Provider;
import dev.ldlab.zedex.library.scrape.Quota;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.storage.Storage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The two seams meet at the entry id.
 *
 * {@code Provider.fetch} already takes a {@link Candidate} whose {@code
 * handle} IS the catalogue's own entry id, so {@link Imports#describe} hands
 * that id straight to the scraper: no second search, no name matching, and a
 * certainty a name match can never promise - on a Spectrum collection full of
 * hacks and re-releases, a name match is as often wrong as right.
 *
 * What this pins is that the handle arrives unchanged and that {@code exact}
 * is true, since a {@link Candidate} that says it is a guess sends the scrape
 * back through a dialog asking somebody to confirm the game they just chose.
 *
 * <b>No network.</b> {@code describe} takes the {@link Provider} as a
 * parameter rather than resolving one itself via {@code Scrapers.enabled} -
 * see the class comment on {@link Imports#describe} - so the fake below is
 * handed straight in, the same way {@code SweepTest.Fake} is handed to
 * {@code Sweep.run}. Nothing here touches a socket.
 *
 * <b>No real document.</b> {@link Metadata#relativePath} is pure document-id
 * arithmetic against the content tree - {@code SweepTest} makes the same
 * observation - so a uri built from the real grant resolves to a real key
 * whether or not any file sits behind it, and this test never needs to write
 * one through SAF.
 *
 * The bench's own metadata store is moved aside and put back, the same
 * discipline {@code ScrapeTest} and {@code SweepTest} already use: it is
 * somebody's whole scraped collection.
 */
@RunWith(AndroidJUnit4.class)
public class ImportScrapeTest {

    private static final String FOLDER = "zedex-import-scrape-test";

    private Context context;
    private File store;
    private byte[] theirs;

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
    }

    @After
    public void putItBack() throws IOException {
        if (store == null) return;

        if (theirs == null) {
            store.delete();
        } else {
            try (FileOutputStream out = new FileOutputStream(store)) {
                out.write(theirs);
            }
        }
        Metadata.refresh(context);
    }

    /** A document uri that resolves to a real key and has no file behind it -
     *  {@code describe} never reads the bytes, only the id arithmetic. */
    private Uri documentFor(String name) {
        Uri root = Storage.contentFolder(context);
        String rootId = DocumentsContract.getDocumentId(root);

        return DocumentsContract.buildDocumentUriUsingTree(
                root, rootId + "/" + FOLDER + "/" + name);
    }

    private static Catalogue.Item item(String id, String title) {
        return new Catalogue.Item(id, title, "1987", "Ocean", "Arcade Game", "Available",
                                  null, Collections.emptyList());
    }

    /** Never asked for a page, never saves media - {@code describe} does not
     *  drive either. */
    private static final class NoHttp implements Http {
        @Override public Reply get(String url) {
            throw new UnsupportedOperationException("describe does not search a second time");
        }

        @Override public String save(String url, File into) {
            throw new UnsupportedOperationException("no media were wanted");
        }
    }

    /**
     * Records the one {@link Candidate} it was handed and answers with a
     * {@link Meta} known ahead of time - no media, so a run of this test
     * never reaches {@link Http#save}.
     */
    private static final class Fake implements Provider {
        Candidate given;
        final Meta meta;

        Fake(Meta meta) {
            this.meta = meta;
        }

        @Override public String name() { return "Fake"; }
        @Override public boolean configured() { return true; }
        @Override public Quota quota() { return Quota.unknown(); }
        @Override public int costPerGame(Wanted wanted) { return 1; }

        @Override
        public List<Candidate> search(Game game) {
            throw new AssertionError("an import must not search - the id is already known");
        }

        @Override
        public Scraped fetch(Candidate candidate, Wanted wanted) {
            given = candidate;
            return new Scraped(meta, Collections.<Medium>emptyList());
        }

        @Override
        public ScrapeException refusalFor(int status) {
            return new ScrapeException(ScrapeException.Kind.NETWORK, "status " + status);
        }
    }

    // --- the one thing that matters -----------------------------------------------------

    /**
     * The candidate {@code describe} builds carries the entry id unchanged
     * and is marked exact, and what the fake answers with lands in the
     * store under the game's own path.
     */
    @Test
    public void thecandidateCarriesTheEntryIdUnchangedAndIsExact() {
        Meta scraped = Meta.at(null)
                .name("Head over Heels").desc("Isometric classic")
                .developer("Ocean").publisher("Ocean")
                .genre("Arcade Game").released("1987")
                .build();

        Fake provider = new Fake(scraped);
        Uri document = documentFor("HeadOverHeels.tzx");
        Imports.Result result =
                new Imports.Result(document, "HeadOverHeels.tzx", Kinds.GAMES, false, null);
        Catalogue.Item item = item("0002259", "Head over Heels");

        Downloads.Result outcome =
                Imports.describe(context, provider, new NoHttp(), result, item);

        assertNotNull("a configured fake provider must not answer null", outcome);

        assertNotNull("the provider was never asked", provider.given);
        assertEquals("the entry id must arrive unchanged", "0002259", provider.given.handle);
        assertTrue("a candidate built from an id already chosen must be exact",
                   provider.given.exact);

        String path = Metadata.relativePath(context, document);
        Meta stored = Metadata.forPath(context, path);
        assertNotNull("nothing was written to the store", stored);
        assertEquals("Head over Heels", stored.name);
        assertEquals("Ocean", stored.developer);
    }

    /**
     * <b>A multi-file import describes a file, never the folder.</b>
     *
     * {@code Imports.writeFolder} answers with the folder it made - which is
     * what somebody sees and what the pane offers - and every test here until
     * now handed {@code describe} a file-shaped result, which is why this
     * survived. Keyed on the folder, the {@link Meta} and every picture the
     * scrape downloaded land under {@code ./Downloaded/Games/<Title>}, and
     * {@code EntryAdapter.onBind} returns before any metadata lookup for an
     * {@code Entry.Kind.FOLDER}: nothing ever reads it, the multi-load game
     * draws as a bare folder name, and the orphan is still counted by {@code
     * Facets.of(Metadata.all(...))}, which then offers a filter value that
     * selects nothing.
     *
     * So {@code describeUri} is what {@code describe} keys on, and for a
     * folder import it is the first member.
     */
    @Test
    public void afolderImportDescribesItsFirstMemberAndNotTheFolder() {
        Fake provider = new Fake(Meta.at(null).name("Robocop").build());

        Uri folder = documentFor("Robocop");
        Uri side1 = documentFor("Robocop/Robocop - Side 1.tap");

        Imports.Result result =
                new Imports.Result(folder, side1, "Robocop", Kinds.GAMES, false, null);

        Downloads.Result outcome =
                Imports.describe(context, provider, new NoHttp(), result, item("1", "Robocop"));

        assertNotNull("the folder import was never described", outcome);

        String memberPath = Metadata.relativePath(context, side1);
        assertNotNull("nothing was written under the member",
                      Metadata.forPath(context, memberPath));

        String folderPath = Metadata.relativePath(context, folder);
        assertNull("the details were written where no row reads them",
                   Metadata.forPath(context, folderPath));
    }

    // --- what is not a failure ------------------------------------------------------------

    /** No configured provider is not a failure - the file is imported
     *  either way and the details are the extra. */
    @Test
    public void noProviderIsNull() {
        Uri document = documentFor("Tasword.tap");
        Imports.Result result =
                new Imports.Result(document, "Tasword.tap", Kinds.APPLICATIONS, false, null);

        Downloads.Result outcome =
                Imports.describe(context, null, new NoHttp(), result, item("1", "Tasword"));

        assertNull(outcome);
    }

    /** A document outside the granted tree resolves to no path, and that is
     *  handled rather than thrown - the one way it happens is somebody
     *  re-granting a different folder mid-import. */
    @Test
    public void adocumentOutsideTheContentTreeIsNullNotAcrash() {
        Fake provider = new Fake(Meta.at(null).name("Nowhere").build());

        Imports.Result result =
                new Imports.Result(null, null, null, false, null);

        Downloads.Result outcome =
                Imports.describe(context, provider, new NoHttp(), result, item("1", "Nowhere"));

        assertNull(outcome);
        assertNull("a null document must never reach the provider", provider.given);
    }
}
