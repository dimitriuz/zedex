package dev.ldlab.zedex.library;

import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.nio.file.Files;
import java.util.List;

/**
 * The favourites list: what survives being written, and what comes back.
 *
 * Part of 11.4's number seven. Instrumentation rather than JVM because
 * {@code Favorites} is built on {@code Uri.parse} and a real file under
 * {@code Storage.root} - the first of which the stub android.jar answers null
 * for, which would make every assertion below quietly meaningless.
 *
 * The rule worth pinning hardest is the one this session's own {@code
 * Entry.isContainer} now leans on: a favourite that merely <em>lives</em>
 * inside a zip comes back as {@code Kind.ARCHIVE}, because that is all
 * {@code Favorites} has to call it - and it is told apart from a real archive
 * by carrying a path within one. Get that wrong and a favourite game is a
 * folder the library tries to walk into.
 *
 * The real list is moved aside in {@link #setUp} and put back in
 * {@link #restore}: somebody's favourites are not a thing to lose to a test,
 * and a run killed part-way is the case that would lose them.
 */
@RunWith(AndroidJUnit4.class)
public class FavoritesTest {

    private static final String FILE = "favorites.json";

    private Context context;
    private File list;

    /** The bench's own favourites, or null if there were none. */
    private byte[] theirs;

    @Before
    public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();

        File root = Storage.root(context);
        assumeTrue("the data folder is not usable on this device: " + root,
                   root.isDirectory() && Storage.isWritable(root));

        list = new File(root, FILE);
        theirs = list.isFile() ? Files.readAllBytes(list.toPath()) : null;

        assertTrue("cannot clear " + list, !list.exists() || list.delete());
    }

    @After
    public void restore() throws IOException {
        if (list == null) return;

        if (theirs == null) {
            list.delete();
            return;
        }

        try (FileOutputStream out = new FileOutputStream(list)) {
            out.write(theirs);
        }
    }

    private static Entry file(String name, String uri) {
        return new Entry(Entry.Kind.FILE, name, Uri.parse(uri), null, 1234, 5678);
    }

    private static Entry insideAZip(String name, String zip, String within) {
        return new Entry(Entry.Kind.ARCHIVE, name, Uri.parse(zip), within, 1234, 5678);
    }

    // --- the round trip ------------------------------------------------------------

    /** Nothing stored is no favourites, and no file - not an error, and not a
     *  crash on the first launch after an install. */
    @Test
    public void nofileMeansNoFavourites() {
        assertEquals(0, Favorites.all(context).size());
        assertFalse(Favorites.has(context, "anything"));
    }

    @Test
    public void afavouriteSurvivesBeingWrittenAndReadBack() {
        Entry game = file("Tujad.z80", "content://test/tujad");

        Favorites.add(context, game);

        assertTrue(Favorites.has(context, game.key()));

        List<Entry> all = Favorites.all(context);
        assertEquals(1, all.size());
        assertEquals("Tujad.z80", all.get(0).name);
        assertEquals(game.key(), all.get(0).key());
    }

    /** Newest first, which is what its own comment promises and what the tab
     *  shows. */
    @Test
    public void thenewestFavouriteComesBackFirst() {
        Favorites.add(context, file("first.tap", "content://test/first"));
        Favorites.add(context, file("second.tap", "content://test/second"));
        Favorites.add(context, file("third.tap", "content://test/third"));

        List<Entry> all = Favorites.all(context);
        assertEquals("third.tap", all.get(0).name);
        assertEquals("second.tap", all.get(1).name);
        assertEquals("first.tap", all.get(2).name);
    }

    /** Favouriting the same game twice leaves one, not two - the row can be
     *  held down again and the list must not grow. */
    @Test
    public void addingTheSameGameTwiceKeepsOne() {
        Entry game = file("Tujad.z80", "content://test/tujad");

        Favorites.add(context, game);
        Favorites.add(context, game);

        assertEquals(1, Favorites.all(context).size());
    }

    @Test
    public void removingTakesItAndLeavesTheRest() {
        Entry one = file("one.tap", "content://test/one");
        Entry two = file("two.tap", "content://test/two");
        Favorites.add(context, one);
        Favorites.add(context, two);

        Favorites.remove(context, one.key());

        assertFalse(Favorites.has(context, one.key()));
        assertTrue(Favorites.has(context, two.key()));
        assertEquals(1, Favorites.all(context).size());
    }

    /** "Harmless if it was not one" - the list can be rewritten by one screen
     *  while another is still holding a key from before. */
    @Test
    public void removingSomethingThatWasNeverThereIsHarmless() {
        Favorites.add(context, file("one.tap", "content://test/one"));

        Favorites.remove(context, "content://test/never");

        assertEquals(1, Favorites.all(context).size());
    }

    // --- what a zip entry comes back as -----------------------------------------------

    /**
     * A game inside a zip keeps its path within the archive, and comes back
     * as {@code ARCHIVE} - which {@link Entry#isContainer} must <em>not</em>
     * read as something to walk into.
     *
     * The rule that class's own comment argues: Favourites has nothing else to
     * call an entry it cannot enter, so the kind alone is ambiguous and
     * {@code inside} is what settles it. If this ever came back with a null
     * {@code inside}, the library would offer to open a game as though it were
     * a folder.
     */
    @Test
    public void agameInsideAZipComesBackAsAnArchiveThatIsNotAContainer() {
        Entry game = insideAZip("Tujad.z80", "content://test/games.zip", "games/Tujad.z80");

        Favorites.add(context, game);

        Entry back = Favorites.all(context).get(0);
        assertEquals(Entry.Kind.ARCHIVE, back.kind);
        assertEquals("the path within the zip was lost", "games/Tujad.z80", back.inside);
        assertFalse("a favourite game inside a zip reads as a folder to walk into",
                    back.isContainer());
    }

    /** And two games in one zip are two favourites, not one - the key carries
     *  the path within the archive for exactly this reason. */
    @Test
    public void twoGamesInTheSameZipAreTwoFavourites() {
        Favorites.add(context, insideAZip("a.tap", "content://test/games.zip", "a.tap"));
        Favorites.add(context, insideAZip("b.tap", "content://test/games.zip", "b.tap"));

        assertEquals(2, Favorites.all(context).size());
    }

    /** An ordinary file has no path within an archive, and must come back with
     *  none - an empty string here would make isContainer answer differently. */
    @Test
    public void anOrdinaryFileComesBackWithNothingInside() {
        Favorites.add(context, file("Tujad.z80", "content://test/tujad"));

        Entry back = Favorites.all(context).get(0);
        assertEquals(Entry.Kind.FILE, back.kind);
        assertNull("an ordinary file came back carrying a path within an archive",
                   back.inside);
    }

    // --- a list that will not read -------------------------------------------------------

    /**
     * A corrupt list reads as no favourites, and never throws.
     *
     * Its own comment: "Losing the list is a nuisance; a crash on every launch
     * afterwards would be a worse one." The library reads this on the way to
     * showing the Favourites tab, so throwing here is a screen nobody can open.
     */
    @Test
    public void acorruptListReadsAsEmptyRatherThanThrowing() throws IOException {
        try (FileOutputStream out = new FileOutputStream(list)) {
            out.write("[[[ not json".getBytes());
        }

        assertEquals(0, Favorites.all(context).size());
        assertFalse(Favorites.has(context, "anything"));
    }

    /** And it can be written over afterwards, which is the point of not
     *  throwing: the list is mendable from inside the app. */
    @Test
    public void acorruptListCanBeReplaced() throws IOException {
        try (FileOutputStream out = new FileOutputStream(list)) {
            out.write("{".getBytes());
        }

        Favorites.add(context, file("fresh.tap", "content://test/fresh"));

        assertEquals(1, Favorites.all(context).size());
    }

    /** An entry missing the fields it is rebuilt from is skipped rather than
     *  becoming a row with no name that opens nothing. */
    @Test
    public void anEntryWithNoUriOrNameIsSkipped() throws IOException {
        try (FileOutputStream out = new FileOutputStream(list)) {
            out.write(("[{\"key\":\"a\",\"name\":\"\",\"uri\":\"content://test/a\"},"
                     + "{\"key\":\"b\",\"name\":\"b.tap\",\"uri\":\"\"},"
                     + "{\"key\":\"c\",\"name\":\"c.tap\",\"uri\":\"content://test/c\"}]")
                    .getBytes());
        }

        List<Entry> all = Favorites.all(context);
        assertEquals("only the complete entry should have come back", 1, all.size());
        assertEquals("c.tap", all.get(0).name);
    }
}
