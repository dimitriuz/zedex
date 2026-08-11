package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One game across several sources, in order.
 *
 * The rule under test throughout: a source may fill a gap and may never
 * overwrite. Everything else here - the title search, stopping early, which
 * folders a later source is asked for - follows from it.
 */
@RunWith(AndroidJUnit4.class)
public class BlendTest {

    private static final String PATH = "./zedex-blend-test/Game.tap";

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
        Artwork.clearStaging(context);
        clearMediaFor(PATH);
    }

    @After
    public void putItBack() throws IOException {
        Artwork.clearStaging(context);
        clearMediaFor(PATH);

        if (store == null) return;

        if (theirs == null) {
            store.delete();
        } else {
            try (FileOutputStream out = new FileOutputStream(store)) {
                out.write(theirs);
            }
        }
        Metadata.clear(context);
    }

    private void clearMediaFor(String path) {
        for (String folder : Arrays.asList("covers", "screenshots", "titlescreens")) {
            for (String extension : Arrays.asList("png", "jpg")) {
                File file = Artwork.fileFor(context, path, folder, extension);
                if (file.isFile()) file.delete();
            }
        }
        Artwork.forget(path);
    }

    /**
     * A row with no document behind it.
     *
     * Entry is immutable and takes all six at once. The uri is null on
     * purpose: Blend is handed the path as an argument and never resolves one
     * itself, and the only thing that would open the document is
     * {@code Provider.Game.md5()}, which no fake here ever asks for.
     */
    private static Entry game(String name) {
        return new Entry(Entry.Kind.FILE, name, null, null, 4096, 0);
    }

    private Blend.Result run(List<Provider> sources, Http http, Blend.Media media,
                             Provider.Wanted wanted, Blend.Chooser chooser) {
        return Blend.run(context, sources, http, game("Game.tap"), PATH,
                         wanted, media, chooser, () -> false);
    }

    /** Asks for nothing and answers nothing: the tests that must not be asked
     *  assert on this having been left alone. */
    private static final class NeverAsked implements Blend.Chooser {
        int asked;

        @Override
        public Candidate choose(String sourceName, List<Candidate> found, String game) {
            asked++;
            return null;
        }
    }

    // --- the merge, end to end ----------------------------------------------------

    /**
     * The second source fills what the first left out and touches nothing
     * else.
     */
    @Test
    public void alaterSourceFillsGapsAndOverwritesNothing() {
        Fakes.Fake first = new Fakes.Fake("First");
        first.facts = candidate -> Meta.at(null).name("Manic Miner").genre("Arcade").build();

        Fakes.Fake second = new Fakes.Fake("Second");
        second.facts = candidate -> Meta.at(null)
                .name("MANIC MINER").genre("Platform").publisher("Bug-Byte").build();

        Blend.Result result = run(Arrays.asList(first, second), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  new NeverAsked());

        assertEquals("Manic Miner", result.meta.name);
        assertEquals("Arcade", result.meta.genre);
        assertEquals("Bug-Byte", result.meta.publisher);
        assertEquals(Arrays.asList("First", "Second"), result.meta.sources());
    }

    /** And the store has it, not just the answer. */
    @Test
    public void theMergedRowIsStored() {
        Fakes.Fake only = new Fakes.Fake("Only");

        run(Collections.singletonList(only), new Fakes.NoHttp(),
            Blend.Media.FILL_GAPS, Provider.Wanted.nothing(), new NeverAsked());

        Meta stored = Metadata.forPath(context, PATH);
        assertNotNull("nothing was written to the store", stored);
        assertEquals(PATH, stored.path);
        assertEquals(Collections.singletonList("Only"), stored.sources());
    }

    /** A source with no candidates at all is not listed - it never answered. */
    @Test
    public void aSourceThatFoundNothingIsNotListed() {
        Fakes.Fake first = new Fakes.Fake("First");
        Fakes.Fake silent = new Fakes.Fake("Silent");
        silent.answer = gameAsked -> Collections.emptyList();

        Blend.Result result = run(Arrays.asList(first, silent), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  new NeverAsked());

        assertEquals(Collections.singletonList("First"), result.meta.sources());
    }

    /**
     * Answered but had nothing to add is still listed - that is a real
     * reply, and different from the source above that was never asked
     * anything at all. Documented rather than left to be rediscovered as a
     * bug: {@code Result.consulted} means "answered", not "changed the row".
     */
    @Test
    public void asourceThatAnswersWithNothingIsStillListed() {
        Fakes.Fake first = new Fakes.Fake("First");
        first.facts = candidate -> Meta.at(null).name("Manic Miner").build();

        Fakes.Fake empty = new Fakes.Fake("Empty");
        empty.facts = candidate -> Meta.at(null).build();

        Blend.Result result = run(Arrays.asList(first, empty), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  new NeverAsked());

        assertEquals(Arrays.asList("First", "Empty"), result.meta.sources());
    }

    /**
     * A game no source has heard of must not rewrite the store.
     *
     * Metadata.put serialises and renames the whole file; writing an empty
     * row for a game nobody answered about would cost a full store rewrite
     * per unknown game across a sweep of a real collection, for a row that
     * says nothing and would then read back as "already scraped".
     */
    @Test
    public void agameNobodyHasHeardOfLeavesTheStoreUntouched() {
        Fakes.Fake silent = new Fakes.Fake("Silent");
        silent.answer = gameAsked -> Collections.emptyList();

        run(Collections.singletonList(silent), new Fakes.NoHttp(),
            Blend.Media.FILL_GAPS, Provider.Wanted.nothing(), new NeverAsked());

        assertNull("nothing was learned, so nothing should have been written",
                   Metadata.forPath(context, PATH));
    }

    // --- which game a later source thinks it is -----------------------------------

    /**
     * A later source is asked about the title, not the filename.
     *
     * Which is what most of them match well on: "MANICM~1.tap" is a filename,
     * and "Manic Miner" is what is in a database.
     */
    @Test
    public void alaterSourceIsAskedAboutTheTitleTheFirstGave() {
        Fakes.Fake first = new Fakes.Fake("First");
        first.facts = candidate -> Meta.at(null).name("Manic Miner").build();

        Fakes.Fake second = new Fakes.Fake("Second");

        run(Arrays.asList(first, second), new Fakes.NoHttp(),
            Blend.Media.FILL_GAPS, Provider.Wanted.nothing(), new NeverAsked());

        assertEquals(Collections.singletonList("Game.tap"), first.searched);
        assertEquals(Collections.singletonList("Manic Miner"), second.searched);
    }

    /**
     * A game already named in the store - by an ES-DE link, a hand edit, or
     * an earlier source this same run - is still asked by hash, not by name
     * alone.
     *
     * {@code known.name} is set the moment anything has named the game, so
     * the very first source in the loop takes the title path exactly as much
     * as a later one does. {@code byTitle} used to answer {@code md5()} with
     * a bare {@code null}, which meant no source could ever answer {@code
     * exact} for a game that already had a name - every ES-DE-linked row,
     * every re-run under {@code Only.EVERYTHING} - and identification fell
     * back to a title comparison the default chooser could not resolve.
     *
     * Mutation-checked: reverting {@code Blend.byTitle}'s {@code md5()} to
     * {@code return null} turns this red; restored afterwards.
     */
    @Test
    public void asourceIsOfferedTheHashEvenWhenTheGameAlreadyHasAName() throws IOException {
        File file = new File(context.getCacheDir(), "blend-hash-test.tap");
        write(file, "fixed bytes to hash");

        try {
            Entry entry = new Entry(Entry.Kind.FILE, "Game.tap", Uri.fromFile(file),
                                    null, file.length(), 0);

            Metadata.put(context, Meta.at(PATH).name("Manic Miner").source(Meta.ESDE).build());

            Fakes.Fake only = new Fakes.Fake("Only");

            Blend.run(context, Collections.singletonList(only), new Fakes.NoHttp(),
                     entry, PATH, Provider.Wanted.nothing(), Blend.Media.FILL_GAPS,
                     new NeverAsked(), () -> false);

            assertEquals(1, only.md5Asked.size());
            assertNotNull("byTitle must still offer the hash to a source asked by "
                          + "title - a source that can match on it should be able to",
                          only.md5Asked.get(0));
        } finally {
            file.delete();
        }
    }

    /**
     * The file is read once for the whole game, not once per source.
     *
     * Both real providers reach for the hash at the top of their own search,
     * so without sharing it a two-source sweep reads all eight hundred files
     * twice through the documents provider - for an answer that cannot have
     * changed between one source and the next.
     */
    @Test
    public void everySourceIsOfferedTheSameHashAndTheFileIsReadOnce() throws IOException {
        File file = new File(context.getCacheDir(), "blend-shared-hash-test.tap");
        write(file, "fixed bytes to hash");

        try {
            Entry entry = new Entry(Entry.Kind.FILE, "Game.tap", Uri.fromFile(file),
                                    null, file.length(), 0);

            Fakes.Fake first = new Fakes.Fake("First");
            Fakes.Fake second = new Fakes.Fake("Second");

            Blend.run(context, Arrays.asList(first, second), new Fakes.NoHttp(),
                      entry, PATH, Provider.Wanted.nothing(), Blend.Media.FILL_GAPS,
                      new NeverAsked(), () -> false);

            assertEquals(1, first.md5Asked.size());
            assertEquals(1, second.md5Asked.size());

            assertNotNull(first.md5Asked.get(0));
            assertEquals("the two sources were handed different hashes for one file",
                         first.md5Asked.get(0), second.md5Asked.get(0));
        } finally {
            file.delete();
        }
    }

    /** The memo itself: asked twice, reads once. */
    @Test
    public void onceReadsItsSourceAtMostOnce() {
        int[] reads = { 0 };

        Blend.Once hash = new Blend.Once(() -> {
            reads[0]++;
            return "abcdef";
        });

        assertEquals("abcdef", hash.get());
        assertEquals("abcdef", hash.get());
        assertEquals("abcdef", hash.get());

        assertEquals("the file was read more than once", 1, reads[0]);
    }

    /**
     * And a file that cannot be read is remembered as unreadable.
     *
     * Without this it would be re-attempted, and re-logged, once per source
     * for every unreadable file in a collection - and null is the answer that
     * falls back to the name search, which is the right outcome to reach
     * quickly rather than repeatedly.
     */
    @Test
    public void onceRemembersThatThereWasNothingToRead() {
        int[] reads = { 0 };

        Blend.Once hash = new Blend.Once(() -> {
            reads[0]++;
            return null;
        });

        assertNull(hash.get());
        assertNull(hash.get());

        assertEquals("an unreadable file was read again", 1, reads[0]);
    }

    /** One guess whose title is the known one needs nobody. */
    @Test
    public void oneExactTitleMatchIsCertainEnough() {
        Fakes.Fake first = new Fakes.Fake("First");
        first.facts = candidate -> Meta.at(null).name("Manic Miner").build();

        Fakes.Fake second = new Fakes.Fake("Second");
        second.answer = gameAsked ->
                Collections.singletonList(Fakes.guess("  manic miner  "));

        NeverAsked chooser = new NeverAsked();

        Blend.Result result = run(Arrays.asList(first, second), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  chooser);

        assertEquals("nobody should have been asked", 0, chooser.asked);
        assertEquals(Arrays.asList("First", "Second"), result.meta.sources());
    }

    /** Three guesses is a question, however close one of them looks. */
    @Test
    public void severalGuessesAreAskedAbout() {
        Fakes.Fake first = new Fakes.Fake("First");
        first.facts = candidate -> Meta.at(null).name("Manic Miner").build();

        Fakes.Fake second = new Fakes.Fake("Second");
        second.answer = gameAsked -> Arrays.asList(
                Fakes.guess("Manic Miner"), Fakes.guess("Manic Miner 2"),
                Fakes.guess("Mining"));

        NeverAsked chooser = new NeverAsked();

        Blend.Result result = run(Arrays.asList(first, second), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  chooser);

        assertEquals(1, chooser.asked);

        // Asked and unanswered is not the same as never heard of: a sweep
        // counts the two separately, because one is an afternoon with the
        // chooser and the other is the service's coverage.
        assertTrue(result.ambiguous);
    }

    @Test
    public void nothingFoundIsNotAmbiguous() {
        Fakes.Fake silent = new Fakes.Fake("Silent");
        silent.answer = gameAsked -> Collections.emptyList();

        Blend.Result result = run(Collections.singletonList(silent), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  new NeverAsked());

        assertFalse(result.ambiguous);
    }

    /** A hash is the file itself; a title is what somebody typed on a shelf. */
    @Test
    public void aCertainMatchWinsEvenWhenItsTitleDisagrees() {
        Fakes.Fake first = new Fakes.Fake("First");
        first.facts = candidate -> Meta.at(null).name("Manic Miner").build();

        Fakes.Fake second = new Fakes.Fake("Second");
        second.answer = gameAsked ->
                Collections.singletonList(Fakes.exact("Wanted: Monty Mole"));
        second.facts = candidate -> Meta.at(null).publisher("Gremlin").build();

        NeverAsked chooser = new NeverAsked();

        Blend.Result result = run(Arrays.asList(first, second), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  chooser);

        assertEquals(0, chooser.asked);
        assertEquals("Manic Miner", result.meta.name);
        assertEquals("Gremlin", result.meta.publisher);
    }

    /**
     * A single guess whose title disagrees with the known one is a question,
     * not an answer.
     *
     * The branch {@code sameTitle} exists to guard: {@link
     * #oneExactTitleMatchIsCertainEnough} only proves the title-matches case,
     * and {@link #aCertainMatchWinsEvenWhenItsTitleDisagrees} never reaches
     * this branch at all because a hash match short-circuits first. Without
     * this test, making {@code sameTitle} answer true unconditionally - which
     * would accept every mismatched guess silently, one game's cover on
     * another for ever - broke nothing else in this class.
     */
    @Test
    public void aguessWhoseTitleDisagreesIsAskedAboutRatherThanAccepted() {
        Fakes.Fake first = new Fakes.Fake("First");
        first.facts = candidate -> Meta.at(null).name("Manic Miner").build();

        Fakes.Fake second = new Fakes.Fake("Second");
        second.answer = gameAsked ->
                Collections.singletonList(Fakes.guess("Wanted: Monty Mole"));

        NeverAsked chooser = new NeverAsked();

        Blend.Result result = run(Arrays.asList(first, second), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  chooser);

        assertEquals(1, chooser.asked);
        assertTrue(result.ambiguous);
    }

    // --- what a sweep costs -------------------------------------------------------

    /**
     * A source is never asked for a folder that already has a picture.
     *
     * The whole of "do not rewrite artwork we already have", and the reason it
     * costs nothing: a ScreenScraper cover is a mediaJeu.php call against the
     * day's allowance.
     */
    @Test
    public void alaterSourceIsAskedOnlyForTheFoldersStillEmpty() throws IOException {
        write(Artwork.fileFor(context, PATH, "covers", "png"), "a cover already here");
        Artwork.forget(PATH);

        Fakes.Fake only = new Fakes.Fake("Only");

        run(Collections.singletonList(only), new Fakes.NoHttp(), Blend.Media.FILL_GAPS,
            Provider.Wanted.of("covers", "screenshots"), new NeverAsked());

        assertEquals(1, only.wantedOf.size());
        assertEquals(Collections.singleton("screenshots"), only.wantedOf.get(0));
    }

    /** Nothing left to gain is not asked at all. */
    @Test
    public void asourceIsNotConsultedWhenThereIsNothingItCouldAdd() throws IOException {
        Metadata.put(context, everythingKnown());
        write(Artwork.fileFor(context, PATH, "covers", "png"), "a cover already here");
        Artwork.forget(PATH);

        Fakes.Fake first = new Fakes.Fake("First");

        run(Collections.singletonList(first), new Fakes.NoHttp(), Blend.Media.FILL_GAPS,
            Provider.Wanted.of("covers"), new NeverAsked());

        assertTrue("a source was asked with nothing to gain", first.searched.isEmpty());
    }

    /** Every field Meta carries, so that "nothing left to gain" is true. */
    private Meta everythingKnown() {
        return Meta.at(PATH)
                .name("Manic Miner").desc("A miner.")
                .developer("Matthew Smith").publisher("Bug-Byte")
                .genre("Arcade Game").subgenre("Platform")
                .released("19831001T000000").players("1").rating("0.9")
                .keymap("0:left = q").machine("ZX-Spectrum 48K")
                .inputs(Collections.singletonList("Cursor"))
                .authors(Collections.singletonList("Matthew Smith"))
                .price("£5.95").series("Miner Willy")
                .seriesGames(Collections.singletonList(new Meta.Link("2", "Jet Set Willy")))
                .compilations(Collections.singletonList(new Meta.Link("3", "Compilation")))
                .contents(Collections.singletonList(new Meta.Link("4", "Something")))
                .contributor("Someone")
                .build();
    }

    /**
     * Every field, or a source gets skipped for nothing.
     *
     * The companion to MergeTest.everyFieldIsMerged, and here for the same
     * reason: this predicate decides whether a source is consulted at all, so
     * a field added to Meta and forgotten here means a service silently never
     * asked about it - and nothing on screen to say why the answer is thinner
     * than it should be.
     */
    @Test
    public void everyMissingFieldIsSomethingLeftToGain() throws Exception {
        Meta everything = everythingKnown();

        assertTrue("the fixture itself does not satisfy the predicate; fix it first",
                   Blend.nothingLeftToGain(everything, Provider.Wanted.nothing()));

        for (java.lang.reflect.Field field : Meta.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isPublic(field.getModifiers())
                    || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String name = field.getName();
            if (name.equals("path") || name.equals("source")) continue;

            Meta.Builder without = everything.but();
            boolean isList = java.util.List.class.isAssignableFrom(field.getType());

            without.getClass()
                   .getMethod(name, isList ? java.util.List.class : String.class)
                   .invoke(without, isList ? java.util.Collections.emptyList() : null);

            assertFalse("nothingLeftToGain ignores " + name
                        + " - a source that could supply it will never be asked",
                        Blend.nothingLeftToGain(without.build(), Provider.Wanted.nothing()));
        }
    }

    // --- one source failing is not the game failing -------------------------------

    @Test
    public void asourceThatThrowsIsRecordedAndTheRestAreStillAsked() {
        Fakes.Fake broken = new Fakes.Fake("Broken");
        broken.answer = gameAsked -> {
            throw new ScrapeException(ScrapeException.Kind.QUOTA_EXCEEDED, "spent");
        };

        Fakes.Fake working = new Fakes.Fake("Working");

        Blend.Result result = run(Arrays.asList(broken, working), new Fakes.NoHttp(),
                                  Blend.Media.FILL_GAPS, Provider.Wanted.nothing(),
                                  new NeverAsked());

        assertEquals(1, result.failures.size());
        assertEquals("Broken", result.failures.get(0).source);
        assertEquals(ScrapeException.Kind.QUOTA_EXCEEDED, result.failures.get(0).why.kind);

        assertEquals(Collections.singletonList("Working"), result.meta.sources());
    }

    // --- offering alternatives ----------------------------------------------------

    /** Medium is (folder, url, extension, md5) - the url comes second, which
     *  is easy to get backwards and compiles either way. */
    private static Medium picture(String folder, String url) {
        return new Medium(folder, url, "png", null);
    }

    /** Nothing on disk: a picture is taken, and it is nobody's question. */
    @Test
    public void afreshPictureIsStagedAndIsNotContested() {
        Fakes.Fake only = new Fakes.Fake("Only");
        only.media = Collections.singletonList(picture("covers", "http://only/cover"));

        Blend.Result result = run(Collections.singletonList(only), new Fakes.WritesTheUrl(),
                                  Blend.Media.OFFER_ALTERNATIVES,
                                  Provider.Wanted.of("covers"), new NeverAsked());

        assertEquals(1, result.staged.size());
        assertEquals("covers", result.staged.get(0).folder);
        assertEquals("Only", result.staged.get(0).source);
        assertFalse(result.staged.get(0).contested);
        assertFalse("nothing may be installed before commit",
                    Artwork.fileFor(context, PATH, "covers", "png").isFile());
    }

    /** Different bytes over something already there is the question the sheet
     *  exists to ask. */
    @Test
    public void adifferentPictureOverOneWeHaveIsContested() throws IOException {
        write(Artwork.fileFor(context, PATH, "covers", "png"), "an older cover");
        Artwork.forget(PATH);

        Fakes.Fake only = new Fakes.Fake("Only");
        only.media = Collections.singletonList(picture("covers", "http://only/cover"));

        Blend.Result result = run(Collections.singletonList(only), new Fakes.WritesTheUrl(),
                                  Blend.Media.OFFER_ALTERNATIVES,
                                  Provider.Wanted.of("covers"), new NeverAsked());

        assertEquals(1, result.staged.size());
        assertTrue(result.staged.get(0).contested);
        assertTrue(result.anythingContested());
    }

    /**
     * The same picture is not a question.
     *
     * Two services carrying the same scan is common, and asking about it would
     * be asking somebody to choose between a picture and itself.
     */
    @Test
    public void thesamePictureIsNotContested() throws IOException {
        write(Artwork.fileFor(context, PATH, "covers", "png"), "http://only/cover");
        Artwork.forget(PATH);

        Fakes.Fake only = new Fakes.Fake("Only");
        only.media = Collections.singletonList(picture("covers", "http://only/cover"));

        Blend.Result result = run(Collections.singletonList(only), new Fakes.WritesTheUrl(),
                                  Blend.Media.OFFER_ALTERNATIVES,
                                  Provider.Wanted.of("covers"), new NeverAsked());

        assertEquals(1, result.staged.size());
        assertFalse(result.staged.get(0).contested);
        assertFalse(result.anythingContested());
    }

    /** Two sources, one folder, two files - neither on top of the other. */
    @Test
    public void twoSourcesEachKeepTheirOwnCover() {
        Fakes.Fake first = new Fakes.Fake("First");
        first.media = Collections.singletonList(picture("covers", "http://first/cover"));

        Fakes.Fake second = new Fakes.Fake("Second");
        second.media = Collections.singletonList(picture("covers", "http://second/cover"));

        Blend.Result result = run(Arrays.asList(first, second), new Fakes.WritesTheUrl(),
                                  Blend.Media.OFFER_ALTERNATIVES,
                                  Provider.Wanted.of("covers"), new NeverAsked());

        assertEquals(2, result.staged.size());

        List<String> offered = new ArrayList<>();
        for (Blend.Staged one : result.staged) offered.add(one.source);
        assertEquals(Arrays.asList("First", "Second"), offered);

        assertFalse("the two staged covers are the same file",
                    result.staged.get(0).file.equals(result.staged.get(1).file));
    }

    // --- committing ---------------------------------------------------------------

    @Test
    public void committingInstallsTheChosenPictureAndEmptiesTheStagingArea() {
        Fakes.Fake only = new Fakes.Fake("Only");
        only.media = Collections.singletonList(picture("covers", "http://only/cover"));

        Blend.Result result = run(Collections.singletonList(only), new Fakes.WritesTheUrl(),
                                  Blend.Media.OFFER_ALTERNATIVES,
                                  Provider.Wanted.of("covers"), new NeverAsked());

        assertEquals(1, Blend.commit(context, PATH, result.staged));

        File installed = Artwork.fileFor(context, PATH, "covers", "png");
        assertTrue(installed.isFile());
        assertFalse("the staging area was left behind",
                    Artwork.stagingRoot(context).exists());
    }

    @Test
    public void committingNothingChangesNothing() throws IOException {
        write(Artwork.fileFor(context, PATH, "covers", "png"), "an older cover");
        Artwork.forget(PATH);

        Fakes.Fake only = new Fakes.Fake("Only");
        only.media = Collections.singletonList(picture("covers", "http://only/cover"));

        run(Collections.singletonList(only), new Fakes.WritesTheUrl(),
            Blend.Media.OFFER_ALTERNATIVES, Provider.Wanted.of("covers"),
            new NeverAsked());

        assertEquals(0, Blend.commit(context, PATH, Collections.emptyList()));

        assertEquals("an older cover",
                     new String(Files.readAllBytes(
                             Artwork.fileFor(context, PATH, "covers", "png").toPath()),
                                "UTF-8"));
    }

    /**
     * The loser goes, not merely stays unwritten.
     *
     * png outranks jpg in Artwork's own order, so a chosen jpg written beside
     * an unchosen png leaves the png on screen - and the choice looks exactly
     * as though it did nothing.
     */
    @Test
    public void installingOneExtensionRemovesTheOther() throws IOException {
        write(Artwork.fileFor(context, PATH, "covers", "png"), "an older png cover");
        Artwork.forget(PATH);

        Fakes.Fake only = new Fakes.Fake("Only");
        only.media = Collections.singletonList(
                new Medium("covers", "http://only/cover", "jpg", null));

        Blend.Result result = run(Collections.singletonList(only), new Fakes.WritesTheUrl(),
                                  Blend.Media.OFFER_ALTERNATIVES,
                                  Provider.Wanted.of("covers"), new NeverAsked());

        assertEquals(1, Blend.commit(context, PATH, result.staged));

        assertTrue(Artwork.fileFor(context, PATH, "covers", "jpg").isFile());
        assertFalse("the png it replaced is still there and still outranks it",
                    Artwork.fileFor(context, PATH, "covers", "png").isFile());
    }

    /** A run killed last time must not offer its leftovers as this run's
     *  findings. */
    @Test
    public void aleftoverFromAKilledRunIsClearedOnTheWayIn() throws IOException {
        File ghost = Artwork.stagingFileFor(context, PATH, "covers/Ghost", "png");
        write(ghost, "from a run that died");

        Fakes.Fake only = new Fakes.Fake("Only");
        only.media = Collections.singletonList(picture("covers", "http://only/cover"));

        Blend.Result result = run(Collections.singletonList(only), new Fakes.WritesTheUrl(),
                                  Blend.Media.OFFER_ALTERNATIVES,
                                  Provider.Wanted.of("covers"), new NeverAsked());

        assertEquals(1, result.staged.size());
        assertEquals("Only", result.staged.get(0).source);

        // The two assertions above hold whether or not the ghost was cleared -
        // Blend.stage never scans the staging tree, it only writes into
        // covers/Only/, a different subfolder from covers/Ghost/. This is the
        // one that actually proves the leftover is gone.
        assertFalse("last run's leftover is still in the staging area, and would be "
                    + "offered as though this run had fetched it",
                    ghost.isFile());
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
        }
    }
}
