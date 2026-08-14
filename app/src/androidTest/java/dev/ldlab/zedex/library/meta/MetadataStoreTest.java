package dev.ldlab.zedex.library.meta;

import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The scraped store itself: written, read back, and every way it can be
 * broken.
 *
 * The rest of 11.4's number three - {@link EsdeSettingsTest} covers the
 * synthetic-root wrapper over ES-DE's own settings file; this is our own
 * {@code library/metadata.json}, the thing a link produces and every row in the
 * library then reads.
 *
 * What makes it worth its own file rather than a corner of the link's is that
 * a mistake here is not visible as an error. The store failing to load reads
 * as "nothing has ever been scraped", which is exactly what a device that has
 * never been linked looks like - so the app would show filenames, and the
 * Library tab would say so calmly, and nothing anywhere would suggest the
 * eight hundred games it knew about yesterday had gone.
 *
 * The bench's real store is moved aside in {@link #setUp} and put back in
 * {@link #putItBack}. It is somebody's whole scraped collection and it takes
 * minutes to rebuild.
 */
@RunWith(AndroidJUnit4.class)
public class MetadataStoreTest {

    private Context context;
    private File store;
    private byte[] theirs;

    @Before
    public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();

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

        // The in-memory copy has to be told, or whatever runs next in this
        // process reads the test's games instead of the bench's.
        Metadata.refresh(context);
    }

    private static Meta game(String path, String name) {
        return Meta.at(path)
                .name(name).desc("a description")
                .developer("Ocean").publisher("Ocean")
                .genre("Platform").released("1984").players("1-2").rating("0.9")
                .source(Meta.ESDE)
                .build();
    }

    // --- the round trip -------------------------------------------------------------

    @Test
    public void agameSurvivesBeingWrittenAndReadBack() {
        Metadata.replaceScraped(context, Collections.singletonList(
                game("./games/Tujad.z80", "Tujad")));
        Metadata.refresh(context);

        Meta back = Metadata.forPath(context, "./games/Tujad.z80");
        assertNotNull("the game was not found by the path it was stored under", back);
        assertEquals("Tujad", back.name);
        assertEquals("Ocean", back.developer);
        assertEquals("1984", back.year());
        assertEquals("Platform", back.genre);
    }

    /** It really goes through the file, not just the cache: a fresh read of
     *  the store has to find it, which is the only thing that survives the
     *  app being killed. */
    @Test
    public void whatWasWrittenIsOnDiskAndNotOnlyInMemory() throws IOException {
        Metadata.replaceScraped(context, Collections.singletonList(
                game("./games/Tujad.z80", "Tujad")));

        assertTrue("no store file was written", store.isFile());

        String xml = new String(Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8);
        assertTrue("the file does not name the game: " + xml, xml.contains("Tujad"));

        Metadata.refresh(context);
        assertNotNull(Metadata.forPath(context, "./games/Tujad.z80"));
    }

    @Test
    public void thecountAndTheWholeCollectionAgree() {
        Metadata.replaceScraped(context, Arrays.asList(
                game("./a.tap", "A"), game("./b.tap", "B"), game("./c.tap", "C")));
        Metadata.refresh(context);

        assertEquals(3, Metadata.count(context));
        assertEquals(3, Metadata.all(context).size());
    }

    /** A path nothing was stored under answers null, not somebody else's
     *  game - attaching one game's description to another is the failure this
     *  keying exists to avoid. */
    @Test
    public void anUnknownPathAnswersNothing() {
        Metadata.replaceScraped(context, Collections.singletonList(game("./a.tap", "A")));
        Metadata.refresh(context);

        assertNull(Metadata.forPath(context, "./somewhere/else.tap"));
    }

    /** A game with no path of its own is dropped rather than stored under an
     *  empty key that every unmatched lookup would then find. */
    @Test
    public void agameWithNoPathIsNotStored() {
        Metadata.replaceScraped(context, Arrays.asList(
                game("", "No path"), game(null, "Also none"), game("./real.tap", "Real")));
        Metadata.refresh(context);

        assertEquals("only the game with a path should be stored",
                     1, Metadata.count(context));
        assertNull(Metadata.forPath(context, ""));
        assertNotNull(Metadata.forPath(context, "./real.tap"));
    }

    // --- when it was last linked -------------------------------------------------------

    /**
     * {@code lastLinked} moves when the store is replaced, and that is what
     * the library watches.
     *
     * {@code LibraryActivity.onResume} compares it and throws away every
     * cached name and picture when it has changed - see its own comment. A
     * link that did not move it would leave the list showing filenames for
     * games it now knows the names of, until something else happened to clear
     * the cache.
     */
    @Test
    public void relinkingMovesTheLinkedTime() throws InterruptedException {
        Metadata.replaceScraped(context, Collections.singletonList(game("./a.tap", "A")));
        Metadata.refresh(context);
        long first = Metadata.lastLinked(context);
        assertTrue("nothing recorded a link time at all", first > 0);

        Thread.sleep(1100);   // the stamp is in milliseconds but the file's mtime is not

        Metadata.replaceScraped(context, Collections.singletonList(game("./b.tap", "B")));
        Metadata.refresh(context);

        assertTrue("relinking did not move lastLinked: " + first + " then "
                   + Metadata.lastLinked(context),
                   Metadata.lastLinked(context) > first);
    }

    /** Nothing linked is a link time of zero, which is what the settings row
     *  reads as "never". */
    @Test
    public void neverLinkedIsZero() {
        Metadata.clear(context);

        assertEquals(0, Metadata.lastLinked(context));
        assertEquals(0, Metadata.count(context));
    }

    // --- unlinking -----------------------------------------------------------------------

    @Test
    public void clearingForgetsTheGamesAndTheFile() {
        Metadata.replaceScraped(context, Collections.singletonList(game("./a.tap", "A")));
        Metadata.refresh(context);
        assertEquals(1, Metadata.count(context));

        Metadata.clear(context);

        assertEquals(0, Metadata.count(context));
        assertFalse("Unlink left the store file behind", store.isFile());
        assertNull(Metadata.forPath(context, "./a.tap"));
    }

    // --- who owns a row -----------------------------------------------------------------

    private static Meta mine(String path, String name) {
        return Meta.at(path).name(name).source(Meta.USER).build();
    }

    /**
     * A link replaces what ES-DE owns and leaves a hand-edited row alone.
     *
     * The whole ownership rule. Getting it wrong is not an error anybody sees:
     * the edit is simply gone the next time Link is pressed, which on this
     * collection is whenever anything at all is scraped.
     */
    @Test
    public void alinkKeepsAHandEditedRowAndReplacesTheRest() {
        Metadata.put(context, mine("./mine.tap", "My own name"));
        Metadata.replaceScraped(context, Collections.singletonList(
                game("./theirs.tap", "Scraped")));
        Metadata.refresh(context);

        assertEquals(2, Metadata.count(context));
        assertNotNull("the hand-edited row was dropped by a link",
                      Metadata.forPath(context, "./mine.tap"));
        assertEquals("My own name", Metadata.forPath(context, "./mine.tap").name);
        assertEquals("Scraped", Metadata.forPath(context, "./theirs.tap").name);
    }

    /** And a scraped row for the same game gives way to the hand-edited one -
     *  otherwise the edit survives the link and is overwritten by it in the
     *  same breath. */
    @Test
    public void ahandEditedRowWinsOverAScrapedOneForTheSameGame() {
        Metadata.put(context, mine("./same.tap", "What I called it"));

        Metadata.replaceScraped(context, Collections.singletonList(
                game("./same.tap", "What ES-DE calls it")));
        Metadata.refresh(context);

        assertEquals(1, Metadata.count(context));
        assertEquals("the link overwrote a hand-edited row",
                     "What I called it", Metadata.forPath(context, "./same.tap").name);
    }

    /** A link that finds nothing still leaves the hand-edited rows - the case
     *  where a lapsed grant would otherwise take somebody's own work with it. */
    @Test
    public void alinkThatFindsNothingStillKeepsWhatWasEdited() {
        Metadata.put(context, mine("./mine.tap", "My own name"));

        Metadata.replaceScraped(context, new ArrayList<>());
        Metadata.refresh(context);

        assertEquals(1, Metadata.count(context));
        assertNotNull(Metadata.forPath(context, "./mine.tap"));
    }

    /** Unlink is a different button and takes everything, edits included -
     *  that is what unlinking means. */
    @Test
    public void unlinkTakesTheHandEditedRowsToo() {
        Metadata.put(context, mine("./mine.tap", "My own name"));
        Metadata.refresh(context);
        assertEquals(1, Metadata.count(context));

        Metadata.clear(context);

        assertEquals(0, Metadata.count(context));
    }

    // --- writing and forgetting one game -----------------------------------------------

    @Test
    public void onegameCanBeWrittenWithoutTouchingTheRest() {
        Metadata.replaceScraped(context, Arrays.asList(
                game("./a.tap", "A"), game("./b.tap", "B")));
        Metadata.refresh(context);

        Metadata.put(context, mine("./a.tap", "A, corrected"));
        Metadata.refresh(context);

        assertEquals(2, Metadata.count(context));
        assertEquals("A, corrected", Metadata.forPath(context, "./a.tap").name);
        assertEquals("B", Metadata.forPath(context, "./b.tap").name);
    }

    /** Writing a game that was not there adds it - the editor can fill in
     *  something ES-DE never scraped at all. */
    @Test
    public void onegameCanBeWrittenWhereThereWasNone() {
        Metadata.put(context, mine("./new.tap", "Never scraped"));
        Metadata.refresh(context);

        assertEquals(1, Metadata.count(context));
        assertEquals("Never scraped", Metadata.forPath(context, "./new.tap").name);
    }

    /** A game with no path is refused rather than stored under nothing. */
    @Test
    public void agameWithNoPathIsNotWritten() {
        Metadata.put(context, mine("", "No path"));
        Metadata.put(context, null);
        Metadata.refresh(context);

        assertEquals(0, Metadata.count(context));
    }

    /**
     * Forgetting drops the row, so the next link brings ES-DE's own back.
     *
     * The way out of owning a game. There is a gap where the game has nothing
     * at all, which is the honest cost of the per-game rule and what the
     * button's wording has to say.
     */
    @Test
    public void forgettingDropsTheRowSoALinkCanBringItBack() {
        Metadata.put(context, mine("./mine.tap", "My own name"));
        Metadata.refresh(context);

        Metadata.forget(context, "./mine.tap");
        Metadata.refresh(context);

        assertNull("the row survived being forgotten",
                   Metadata.forPath(context, "./mine.tap"));

        Metadata.replaceScraped(context, Collections.singletonList(
                game("./mine.tap", "What ES-DE calls it")));
        Metadata.refresh(context);

        assertEquals("a link did not bring the scraped version back",
                     "What ES-DE calls it", Metadata.forPath(context, "./mine.tap").name);
    }

    /** Forgetting something that was never there leaves the rest alone. */
    @Test
    public void forgettingSomethingThatIsNotThereIsHarmless() {
        Metadata.replaceScraped(context, Collections.singletonList(game("./a.tap", "A")));
        Metadata.refresh(context);

        Metadata.forget(context, "./never.tap");
        Metadata.forget(context, null);
        Metadata.refresh(context);

        assertEquals(1, Metadata.count(context));
    }

    /** The source survives the round trip, or nothing above can be told apart
     *  after a restart. */
    @Test
    public void whoOwnsARowSurvivesBeingWrittenAndReadBack() {
        Metadata.put(context, mine("./mine.tap", "Mine"));
        Metadata.replaceScraped(context, Collections.singletonList(
                game("./theirs.tap", "Theirs")));
        Metadata.refresh(context);

        assertTrue("a hand-edited row did not read back as one",
                   Metadata.forPath(context, "./mine.tap").isMine());
        assertFalse("a scraped row read back as hand-edited",
                    Metadata.forPath(context, "./theirs.tap").isMine());
    }

    // --- a store that will not read ---------------------------------------------------------

    /**
     * A corrupt store reads as nothing scraped, and never throws.
     *
     * The library reads this while binding rows, so throwing here is not an
     * error message - it is a list that cannot be drawn.
     */
    @Test
    public void acorruptStoreReadsAsEmptyRatherThanThrowing() throws IOException {
        store.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(store)) {
            out.write("<gameList><game><path>./a.tap".getBytes(StandardCharsets.UTF_8));
        }
        Metadata.refresh(context);

        assertEquals(0, Metadata.count(context));
        assertNull(Metadata.forPath(context, "./a.tap"));
    }

    /** And it can be linked over afterwards - the store is mendable from
     *  inside the app, which is the point of not throwing. */
    @Test
    public void acorruptStoreCanBeReplacedByLinkingAgain() throws IOException {
        store.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(store)) {
            out.write("not xml at all".getBytes(StandardCharsets.UTF_8));
        }
        Metadata.refresh(context);

        Metadata.replaceScraped(context, Collections.singletonList(game("./a.tap", "A")));
        Metadata.refresh(context);

        assertEquals(1, Metadata.count(context));
    }

    /**
     * No temporary file is left beside the store.
     *
     * The write goes to {@code metadata.json.tmp} and is renamed, so that a
     * failure part way through cannot leave the real file half written - which
     * would then read back as no metadata at all, the moment it lost the tag
     * that made it well formed. A {@code .tmp} still there afterwards means
     * the rename did not happen and what is being read is the old store.
     */
    @Test
    public void nothingIsLeftHalfWritten() {
        Metadata.replaceScraped(context, Collections.singletonList(game("./a.tap", "A")));

        File temp = new File(store.getParentFile(), "metadata.json.tmp");
        assertFalse("a half-written " + temp.getName() + " was left behind", temp.exists());
        assertTrue("the store itself is not there", store.isFile());
    }

    /**
     * Replacing everything with nothing does replace it.
     *
     * Deliberate, and its own comment says it is logged loudly rather than
     * refused: the caller has already decided, and second-guessing here would
     * make Unlink and an empty link behave differently for no reason a user
     * could see.
     */
    @Test
    public void replacingWithAnEmptyListEmptiesTheStore() {
        Metadata.replaceScraped(context, Collections.singletonList(game("./a.tap", "A")));
        Metadata.refresh(context);
        assertEquals(1, Metadata.count(context));

        Metadata.replaceScraped(context, new ArrayList<>());
        Metadata.refresh(context);

        assertEquals(0, Metadata.count(context));
    }

    /** Two games claiming the same path leave one - a store keyed by path
     *  cannot hold both, and the later one wins rather than the read failing. */
    @Test
    public void twoGamesOnOnePathLeaveOne() {
        Metadata.replaceScraped(context, Arrays.asList(
                game("./same.tap", "First"), game("./same.tap", "Second")));
        Metadata.refresh(context);

        assertEquals(1, Metadata.count(context));
        assertEquals("Second", Metadata.forPath(context, "./same.tap").name);
    }

    /** A description with characters XML has rules about survives - a game
     *  blurb with an ampersand in it is not unusual. */
    @Test
    public void awkwardTextSurvivesTheRoundTrip() {
        String awkward = "Tom & Jerry <the> \"one\" with 'apostrophes'";

        Metadata.replaceScraped(context, Collections.singletonList(
                Meta.at("./a.tap").name(awkward).desc(awkward).source(Meta.ESDE).build()));
        Metadata.refresh(context);

        Meta back = Metadata.forPath(context, "./a.tap");
        assertNotNull(back);
        assertEquals(awkward, back.name);
        assertEquals(awkward, back.desc);
    }

    /** And the list survives being large enough to be the real thing - this
     *  is read on every cold start and bound per row. */
    @Test
    public void athousandGamesRoundTrip() {
        List<Meta> many = new ArrayList<>();
        for (int at = 0; at < 1000; at++) many.add(game("./game" + at + ".tap", "Game " + at));

        Metadata.replaceScraped(context, many);
        Metadata.refresh(context);

        assertEquals(1000, Metadata.count(context));
        assertEquals("Game 999", Metadata.forPath(context, "./game999.tap").name);
    }

    // --- the control layout ------------------------------------------------------------

    /**
     * A scraped control layout survives being written and read back.
     *
     * Stored under an element of ours, since ES-DE has nothing that means it.
     * Losing it on the round trip would be invisible until somebody tried to
     * use it, which is a separate piece of work and a long way from here.
     */
    @Test
    public void acontrolLayoutSurvivesTheRoundTrip() {
        String layout = "# Manic Miner\n0:left = q ;; left\n0:a = v ;; jump";

        Metadata.put(context, game("./a.tap", "A").but().keymap(layout).build());
        Metadata.refresh(context);

        assertEquals(layout, Metadata.forPath(context, "./a.tap").keymap);
    }

    /** A game without one reads back with none, not an empty string. */
    @Test
    public void agameWithNoLayoutReadsBackWithNone() {
        Metadata.replaceScraped(context, Collections.singletonList(game("./a.tap", "A")));
        Metadata.refresh(context);

        assertNull(Metadata.forPath(context, "./a.tap").keymap);
    }

    /** Editing a field by hand keeps it - the editor does not show the layout,
     *  so dropping it there would lose it silently on the first correction. */
    @Test
    public void editingAFieldByHandKeepsTheLayout() {
        String layout = "0:left = q";

        Metadata.put(context, game("./a.tap", "A").but().keymap(layout).build());
        Metadata.refresh(context);

        Meta edited = Metadata.forPath(context, "./a.tap").with(Meta.Field.NAME, "Renamed");
        Metadata.put(context, edited);
        Metadata.refresh(context);

        assertEquals("Renamed", Metadata.forPath(context, "./a.tap").name);
        assertEquals("the hand editor dropped the control layout",
                     layout, Metadata.forPath(context, "./a.tap").keymap);
    }

    /** Multi-line and full of punctuation, which is what it really is. */
    @Test
    public void arealisticLayoutSurvivesXmlEscaping() {
        String layout = "# Manic Miner\n# v1.0 - 23/03/2025\n"
                      + "0:start = ENTER ;; start game\n0:left = q ;; left\n"
                      + "0:r1 = h ;; music on & off <loud>";

        Metadata.put(context, game("./a.tap", "A").but().keymap(layout).build());
        Metadata.refresh(context);

        assertEquals(layout, Metadata.forPath(context, "./a.tap").keymap);
    }

    // --- the fields that are not strings ------------------------------------------------

    /**
     * A list survives the round trip, which the old format could not have
     * managed without inventing a separator.
     *
     * {@code inputs} is the first field here that is not a single string, and
     * the concrete reason the store stopped borrowing ES-DE's schema: in XML
     * this needed a convention for joining them and a rule for a value that
     * contained the joining character.
     */
    @Test
    public void alistOfInputsSurvivesBeingWrittenAndReadBack() {
        java.util.List<String> inputs =
                java.util.Arrays.asList("Kempston Joystick", "Cursor", "Redefineable keys");

        Metadata.put(context, Meta.at("./a.tap").name("A")
                .machine("ZX-Spectrum 48K/128K").inputs(inputs)
                .source("ZXInfo").build());
        Metadata.refresh(context);

        Meta back = Metadata.forPath(context, "./a.tap");

        assertEquals(inputs, back.inputs);
        assertEquals("ZX-Spectrum 48K/128K", back.machine);
    }

    /** And nothing stored gives an empty list rather than null, so no caller
     *  has to remember which. */
    @Test
    public void agameWithNoInputsReadsBackAsAnEmptyList() {
        Metadata.put(context, Meta.at("./a.tap").name("A").source(Meta.ESDE).build());
        Metadata.refresh(context);

        assertTrue(Metadata.forPath(context, "./a.tap").inputs.isEmpty());
    }

    // --- the fields a second provider brought ----------------------------------------

    /** Writes the store as text, for the cases where what is under test is
     *  what the reader makes of a file it did not write. */
    private void write(String json) throws IOException {
        store.getParentFile().mkdirs();

        try (FileOutputStream out = new FileOutputStream(store)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }


    /**
     * Authors, price, series and compilations survive the round trip.
     *
     * The point of a format of our own: these are ZXInfo's and ES-DE's schema
     * has no room for them. Two of them are lists of <em>other entries</em>,
     * so each row is an object rather than a string - which the gamelist this
     * replaced could not have held at all without inventing a separator
     * convention and a rule for what happens when a title contains it.
     */
    @Test
    public void thesecondProvidersFieldsSurviveTheRoundTrip() {
        Metadata.put(context, game("./a.tap", "A").but()
                .authors(Arrays.asList("Jon Ritman", "F. David Thorpe (Load Screen)"))
                .price("£7.95")
                .series("Chaos")
                .seriesGames(Collections.singletonList(
                        new Meta.Link("2930", "Lords of Chaos")))
                .compilations(Arrays.asList(
                        new Meta.Link("12019", "Dixons Premier Collection for Your +2"),
                        new Meta.Link("14204", "Outlet issue 117")))
                .contents(Collections.singletonList(
                        new Meta.Link("1860", "Freddy Hardest")))
                .build());
        Metadata.refresh(context);

        Meta back = Metadata.forPath(context, "./a.tap");

        assertEquals(Arrays.asList("Jon Ritman", "F. David Thorpe (Load Screen)"),
                     back.authors);
        assertEquals("£7.95", back.price);
        assertEquals("Chaos", back.series);

        assertEquals(Collections.singletonList(new Meta.Link("2930", "Lords of Chaos")),
                     back.seriesGames);
        assertEquals("the id has to survive, or the titles can never become links",
                     "12019", back.compilations.get(0).id);
        assertEquals(2, back.compilations.size());
        assertEquals(Collections.singletonList(new Meta.Link("1860", "Freddy Hardest")),
                     back.contents);
    }

    /** A game with none of them reads back with none of them, rather than with
     *  empty strings and lists of nothing. */
    @Test
    public void agameWithoutThemIsNotGivenEmptyOnes() {
        Metadata.put(context, game("./b.tap", "B"));
        Metadata.refresh(context);

        Meta back = Metadata.forPath(context, "./b.tap");

        assertNull(back.price);
        assertNull(back.series);
        assertTrue(back.authors.isEmpty());
        assertTrue(back.seriesGames.isEmpty());
        assertTrue(back.compilations.isEmpty());
        assertTrue(back.contents.isEmpty());
    }

    /** A row of the list with no id, or no title, is not a link - the store is
     *  a file people edit, and half a link points nowhere. */
    @Test
    public void ahalfWrittenLinkIsDropped() throws Exception {
        write("{\"version\":1,\"linked\":0,\"games\":{\"./c.tap\":{"
              + "\"name\":\"C\",\"compilations\":["
              + "{\"id\":\"1\",\"title\":\"Kept\"},"
              + "{\"title\":\"No id\"},"
              + "{\"id\":\"3\"},"
              + "\"not an object\"]}}}");
        Metadata.refresh(context);

        Meta back = Metadata.forPath(context, "./c.tap");
        assertEquals(Collections.singletonList(new Meta.Link("1", "Kept")),
                     back.compilations);
    }

    // --- counting a start --------------------------------------------------------------

    /**
     * Opening a game counts, and the count survives the store being written
     * and read.
     */
    @Test
    public void playedCountsOneAtATime() {
        Metadata.played(context, "./counted.tap");
        Metadata.played(context, "./counted.tap");
        Metadata.played(context, "./counted.tap");

        Metadata.refresh(context);

        assertEquals(3, Metadata.forPath(context, "./counted.tap").plays());
    }

    /**
     * A game nobody has scraped is counted too, and the row it makes still
     * reads as unscraped.
     *
     * Most of a fresh collection is unscraped, so a count that only worked for
     * scraped games would count the wrong thing - and a row carrying nothing
     * but a path and a number must not start reading as one somebody has
     * already fetched, or a sweep would skip it for ever. {@code Meta.isEsde}
     * answering true for no contributors at all is what makes that safe, and
     * this is what says so out loud.
     */
    @Test
    public void anunscrapedRowIsCountedAndStaysUnscraped() {
        Metadata.played(context, "./never-scraped.tap");
        Metadata.refresh(context);

        Meta row = Metadata.forPath(context, "./never-scraped.tap");

        assertEquals(1, row.plays());
        assertTrue("a counted row must still be offered to a sweep", row.isEsde());
        assertFalse("counting is not a hand edit", row.isMine());
    }

    /** And it leaves everything else the row says exactly as it was - the
     *  count is the app's own bookkeeping, not an edit of the facts. */
    @Test
    public void countingLeavesTheRestOfTheRowAlone() {
        Metadata.put(context, Meta.at("./scraped.tap")
                .name("Manic Miner").genre("Arcade Game")
                .contributor("ZXInfo").build());

        Metadata.played(context, "./scraped.tap");
        Metadata.refresh(context);

        Meta row = Metadata.forPath(context, "./scraped.tap");

        assertEquals("Manic Miner", row.name);
        assertEquals("Arcade Game", row.genre);
        assertEquals(Collections.singletonList("ZXInfo"), row.sources());
        assertEquals(1, row.plays());
    }

    // --- the video link -------------------------------------------------------------

    /** A video link survives being written and read back, exactly as every
     *  other string field here does. */
    @Test
    public void avideoLinkSurvivesTheRoundTrip() {
        String link = "https://www.youtube.com/watch?v=r1U9U1MMn6g";

        Metadata.put(context, game("./a.tap", "A").but().videoLink(link).build());
        Metadata.refresh(context);

        assertEquals(link, Metadata.forPath(context, "./a.tap").videoLink);
    }

    /** A game with no video reads back with none, not an empty string - the
     *  ordinary case, since most of a collection has no video at all. */
    @Test
    public void agameWithNoVideoReadsBackWithNone() {
        Metadata.replaceScraped(context, Collections.singletonList(game("./a.tap", "A")));
        Metadata.refresh(context);

        assertNull(Metadata.forPath(context, "./a.tap").videoLink);
    }

    /** The completed flag survives a round trip too - three states, and
     *  "false" is not the same as absent. */
    @Test
    public void thecompletedFlagIsStoredAsItWasSaid() {
        Metadata.put(context, Meta.at("./done.tap").completed("true").build());
        Metadata.put(context, Meta.at("./not-done.tap").completed("false").build());
        Metadata.put(context, Meta.at("./unsaid.tap").name("x").build());

        Metadata.refresh(context);

        assertTrue(Metadata.forPath(context, "./done.tap").isCompleted());
        assertFalse(Metadata.forPath(context, "./not-done.tap").isCompleted());
        assertEquals("false", Metadata.forPath(context, "./not-done.tap").completed);
        assertNull("nobody said, and that is not the same as no",
                   Metadata.forPath(context, "./unsaid.tap").completed);
    }
}
