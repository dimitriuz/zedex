package dev.ldlab.zedex.library.catalogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.storage.Tree;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bringing a zxart download in from the reader's side of the app - a prod, a
 * tune, a picture - and proving the fix in {@code Imports.namedFrom}.
 *
 * <b>Why this is here and not in {@code ImportsTest}.</b> Every fixture in
 * that file is a hand-built zip because zxart's own downloads are the
 * opposite case: none of these three URLs point at an archive, so each one
 * exercises the fallback in {@code Imports.bring} that treats the download
 * itself as the file - the path {@code namedFrom} was added to. On a device
 * for the same reason as {@code ImportsTest}: the destination is SAF and
 * nothing here exists on the JVM.
 *
 * <b>The fix this class exists to prove.</b> zxart's rendered-picture url
 * carries no extension at all ({@code zximages/id=2232;border=0;pal=srgb;
 * type=standard;zoom=1}), so a document written under its bare basename has
 * no extension either. {@code Tree.write} always creates the document as
 * {@code application/octet-stream}, and that argument is inert: {@code
 * CataloguePane.openOutside} asks {@code ContentResolver.getType()} at open
 * time, and SAF re-derives the type from the document's own display name, not
 * from whatever it was created with. So the assertion that actually proves
 * the fix is not "a file exists" - it is that the stored name ends
 * {@code .png} <b>and</b> {@code getType()} on it answers an image type,
 * which is the only thing that says Open will resolve to something rather
 * than nothing.
 *
 * <b>A strict fake, not a lenient one.</b> {@code Fixtures.Canned} in the
 * unit-test tier was made strict for exactly this reason: a fake that invents
 * a reply for anything it is asked can make an import test pass while
 * importing nothing. {@link Recorded} throws on any url it was not built to
 * serve, and each test reads its own {@code calls} count back rather than
 * assuming a single request happened.
 *
 * <b>Names are stamped with {@code nanoTime}, never fixed</b> - the same
 * reason {@code ImportsTest} does it and not {@code RecentsTest}'s
 * {@code dropAnyLeftOver}: that pattern exists for a test that needs one
 * predictable, human-visible name across runs. Nothing here does - a fresh
 * nanoTime makes a name a killed run's leftover can never collide with,
 * which is the same guarantee {@code dropAnyLeftOver} gives by a different
 * route. The one test that imports the same thing twice does so within a
 * single stamp, on purpose - that repeat is the point of the test, not a
 * hazard.
 */
@RunWith(AndroidJUnit4.class)
public class ZxartImportTest {

    /** New every run, so this run's names collide with nothing a previous
     *  run - killed or otherwise - left behind. */
    private final String stamp = "zedex-zxart-" + System.nanoTime();

    private Context context;
    private Uri tree;
    private final List<Uri> made = new ArrayList<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        tree = Storage.contentFolder(context);
        assumeNotNull("no content folder granted on this device", tree);
    }

    /** Only what this run made - see the class doc for why nothing else can
     *  be left for it to collide with. */
    @After
    public void tidyUp() {
        for (Uri document : made) {
            try {
                android.provider.DocumentsContract.deleteDocument(
                        context.getContentResolver(), document);
            } catch (Exception ignored) {
                // A leftover under a nanoTime name collides with nothing.
            }
        }
    }

    // --- a prod's tzx, into Games --------------------------------------------------

    @Test
    public void aProdsTzxLandsInDownloadedGames() {
        String url = "https://zxart.ee/file/" + stamp + "-Prod.tzx";
        Recorded http = new Recorded(url, "a real prod would be bigger than this".getBytes(
                StandardCharsets.US_ASCII));

        Imports.Result result = kept(Imports.game(
                context, http, item("Games", download(url, "tzx")), download(url, "tzx")));

        assertNull("import failed: " + (result.failure == null ? null : result.failure.getMessage()),
                   result.failure);
        assertEquals(1, http.calls);
        assertEquals(Kinds.GAMES, result.folder);
        assertEquals(stamp + "-Prod.tzx", result.displayName);

        Uri games = kindFolder(Kinds.GAMES);
        Uri found = Tree.find(context, games, stamp + "-Prod.tzx");
        assertNotNull("the game was not found by name under Downloaded/Games", found);
        assertEquals(found, result.documentUri);
    }

    // --- a tune's ogg, into Music ---------------------------------------------------

    @Test
    public void atunesOggLandsInDownloadedMusic() {
        String url = "https://zxart.ee/file/" + stamp + "-Tune.ogg";
        Recorded http = new Recorded(url, "not really ogg, but that is not what this proves"
                .getBytes(StandardCharsets.US_ASCII));

        Imports.Result result = kept(Imports.document(
                context, http, item("Music", download(url, "ogg")), download(url, "ogg")));

        assertNull("import failed: " + (result.failure == null ? null : result.failure.getMessage()),
                   result.failure);
        assertEquals(1, http.calls);
        assertEquals(Kinds.MUSIC, result.folder);
        assertEquals(stamp + "-Tune.ogg", result.displayName);

        Uri music = kindFolder(Kinds.MUSIC);
        Uri found = Tree.find(context, music, stamp + "-Tune.ogg");
        assertNotNull("the tune was not found by name under Downloaded/Music", found);
        assertEquals(found, result.documentUri);
    }

    // --- a picture's png, into Graphics - the fix ------------------------------------

    /**
     * The url this states is exactly zxart's shape: no dot anywhere in it, so
     * {@code Imports.namedFrom} has nothing to read an extension off and must
     * fall back to {@code Catalogue.Download.format()}. A url that already
     * carried {@code .png} would pass whether or not the fix exists and would
     * prove nothing.
     */
    @Test
    public void apicturesPngLandsInDownloadedGraphicsNamedWithItsExtension() {
        String url = "https://zxart.ee/zximages/id=" + stamp.replace("zedex-zxart-", "")
                + ";border=0;pal=srgb;type=standard;zoom=1";
        assertTrue("the fixture url must carry no extension, or this test proves nothing",
                   lastSegment(url).indexOf('.') < 0);

        byte[] png = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0 };
        Recorded http = new Recorded(url, png);

        Imports.Result result = kept(Imports.document(
                context, http, item("Graphics", download(url, "png")), download(url, "png")));

        assertNull("import failed: " + (result.failure == null ? null : result.failure.getMessage()),
                   result.failure);
        assertEquals(1, http.calls);
        assertEquals(Kinds.GRAPHICS, result.folder);

        // The assertion that proves the fix, part one: the name the app chose.
        assertTrue("an extensionless url must still be named with its stated format, was: "
                        + result.displayName,
                   result.displayName.endsWith(".png"));

        Uri graphics = kindFolder(Kinds.GRAPHICS);
        Uri found = Tree.find(context, graphics, result.displayName);
        assertNotNull("the picture was not found by its own display name", found);

        // The assertion that proves the fix, part two, and the one that
        // actually matters: what SAF itself now says this document is. Tree.write
        // always creates it "application/octet-stream" - that argument is inert,
        // because getType() re-derives the type from the display name at query
        // time, not from what created the document. So the mime here is read off
        // the same name SAF will consult when CataloguePane.openOutside asks to
        // open it; getting an image/* answer back is what says Open will resolve
        // to something instead of nothing.
        ContentResolver resolver = context.getContentResolver();
        String type = resolver.getType(found);
        Log.i("ZxartImportTest", "getType() for " + result.displayName + " answered " + type);
        assertNotNull("SAF answered no type at all for " + result.displayName, type);
        assertTrue("expected an image type for a document named " + result.displayName
                        + ", SAF answered " + type,
                   type.startsWith("image/"));
    }

    // --- the SAF trap: a second import must not make "(1)" ----------------------------

    /**
     * {@code createDocument} over a name already present cheerfully makes
     * {@code Games (1)} - which is how a collection acquires four of
     * everything and how somebody concludes the first import failed. Imports
     * the very same picture twice and checks the folder itself, not just what
     * the second {@link Imports.Result} claims about itself.
     */
    @Test
    public void asecondImportOfTheSamePictureDoesNotMakeAOneInParens() {
        String url = "https://zxart.ee/zximages/id=" + stamp.replace("zedex-zxart-", "")
                + "9;border=0;pal=srgb;type=standard;zoom=1";
        byte[] png = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

        Imports.Result first = kept(Imports.document(
                context, new Recorded(url, png), item("Graphics", download(url, "png")),
                download(url, "png")));
        assertNull(first.failure);

        Imports.Result second = kept(Imports.document(
                context, new Recorded(url, png), item("Graphics", download(url, "png")),
                download(url, "png")));

        assertNull(second.failure);
        assertTrue("a second copy was written instead of being recognised as already there",
                   second.alreadyThere);
        assertEquals("the second import should hand back the very same document",
                     first.documentUri, second.documentUri);

        Uri graphics = kindFolder(Kinds.GRAPHICS);
        String duplicateName = withOne(first.displayName);
        assertNull("a duplicate " + duplicateName + " was created",
                   Tree.find(context, graphics, duplicateName));
    }

    // --- fixtures --------------------------------------------------------------------

    /**
     * Records exactly one url this fake will serve, and how many times it
     * was asked - a lenient fake that answered anything would let an import
     * test pass while importing nothing, which is the failure {@code
     * Fixtures.Canned} was made strict to stop hiding.
     */
    private static final class Recorded implements Http {
        private final String url;
        private final byte[] bytes;
        int calls = 0;

        Recorded(String url, byte[] bytes) {
            this.url = url;
            this.bytes = bytes;
        }

        @Override
        public Reply get(String url) {
            throw new UnsupportedOperationException("not this test's business");
        }

        @Override
        public String save(String url, File into) throws IOException {
            if (!this.url.equals(url)) {
                throw new IOException("this fake serves only " + this.url + ", asked for " + url);
            }
            calls++;
            try (FileOutputStream out = new FileOutputStream(into)) {
                out.write(bytes);
            }
            return "00000000000000000000000000000000";
        }
    }

    private Catalogue.Item item(String kind, Catalogue.Download file) {
        return new Catalogue.Item(stamp, stamp + "-item", "2026", "zxart",
                                  kind, "Available", null,
                                  Collections.singletonList(
                                          new Catalogue.Version(null, "2026",
                                                                Collections.singletonList(file))),
                                  null);
    }

    private static Catalogue.Download download(String url, String format) {
        return new Catalogue.Download(url, format, -1);
    }

    /** {@code Downloaded/<folder>} under the granted tree, made if it is not
     *  already there - the same lookup {@code Imports.bring} does. */
    private Uri kindFolder(String folder) {
        return Tree.folder(context, tree, "Downloaded", folder);
    }

    private static String lastSegment(String url) {
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }

    /** The name SAF would give a second document of this name - inserted
     *  before the extension, exactly as {@code createDocument} does it. */
    private static String withOne(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name + " (1)" : name.substring(0, dot) + " (1)" + name.substring(dot);
    }

    /** Remembers what a call wrote, so tidyUp removes that and only that -
     *  same idiom as ImportsTest.kept. */
    private Imports.Result kept(Imports.Result result) {
        if (result != null && result.documentUri != null && !result.alreadyThere) {
            made.add(result.documentUri);
        }
        return result;
    }
}
