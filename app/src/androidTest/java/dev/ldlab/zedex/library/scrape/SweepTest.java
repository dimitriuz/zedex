package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.Entry;
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
 * A whole collection at a time, against a provider that never touches the
 * network.
 *
 * {@link Provider} and {@link Http} are interfaces so that this file can
 * exist: every branch a real run can take - a spent quota, refused
 * credentials, a thread limit, somebody pressing Cancel - is one a fake can
 * produce on demand and none of them can be arranged reliably against a live
 * service. The live test alongside this proves the wiring; this proves the
 * decisions.
 *
 * The games are made up. {@code Metadata.relativePath} is pure document-id
 * arithmetic against the content tree, so a document uri built from the real
 * grant resolves to a real key whether or not any file is behind it - and
 * nothing here reads bytes, because the fake provider never asks for a hash.
 *
 * The bench's own store is moved aside and put back: it is somebody's whole
 * scraped collection.
 */
@RunWith(AndroidJUnit4.class)
public class SweepTest {

    /** Somewhere no real collection has, so a leaked row is obvious. */
    private static final String FOLDER = "zedex-sweep-test";

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

    // --- the world the sweep runs in ---------------------------------------------------

    /** A row of the library that resolves to a real key and has no file. */
    private Entry game(String name) {
        Uri root = Storage.contentFolder(context);
        String rootId = DocumentsContract.getDocumentId(root);

        Uri uri = DocumentsContract.buildDocumentUriUsingTree(
                root, rootId + "/" + FOLDER + "/" + name);

        return new Entry(Entry.Kind.FILE, name, uri, null, 1024, 0);
    }

    private String pathOf(Entry entry) {
        return Metadata.relativePath(context, entry.uri);
    }

    private List<Entry> games(String... names) {
        List<Entry> entries = new ArrayList<>();
        for (String name : names) entries.add(game(name));
        return entries;
    }

    /** A watcher that answers however the test needs and records the rest. */
    private static final class Watching implements Sweep.Watcher {

        final List<String> saw = new ArrayList<>();
        final List<String> asked = new ArrayList<>();

        /** Report cancelled once this many games have been started. */
        int cancelAfter = Integer.MAX_VALUE;

        /** What {@link #chooseFrom} answers; the last one is reused. */
        List<Sweep.Choice> choices = new ArrayList<>();

        @Override
        public boolean cancelled() {
            return saw.size() >= cancelAfter;
        }

        @Override
        public void at(int done, int total, String game) {
            saw.add(game);
        }

        @Override
        public Sweep.Choice chooseFrom(List<Candidate> found, String game) {
            asked.add(game);

            int at = Math.min(asked.size() - 1, choices.size() - 1);
            return choices.isEmpty() ? Sweep.Choice.skip() : choices.get(at);
        }
    }

    private Sweep.Tally run(Fakes.Fake provider, Watching watcher, List<Entry> entries) {
        return Sweep.run(context, provider, new Fakes.NoHttp(), entries,
                         Provider.Wanted.nothing(), Sweep.Conflicts.SKIP, watcher);
    }

    private Sweep.Tally run(Fakes.Fake provider, Watching watcher, Sweep.Conflicts conflicts,
                            List<Entry> entries) {
        return Sweep.run(context, provider, new Fakes.NoHttp(), entries,
                         Provider.Wanted.nothing(), conflicts, watcher);
    }

    // --- the ordinary run ----------------------------------------------------------------

    /**
     * Every game, one at a time, in the order it was given.
     *
     * Serial is not a preference: an account without a subscription is allowed
     * one request in flight, and part two measured that asking for more
     * changes nothing. A pool here would spend the day's allowance on
     * refusals.
     */
    @Test
    public void everyGameIsAskedAboutOnceAndInTheOrderGiven() {
        Fakes.Fake provider = new Fakes.Fake();
        Watching watcher = new Watching();

        Sweep.Tally tally = run(provider, watcher, games("A.tap", "B.tap", "C.tap"));

        assertEquals(Arrays.asList("A.tap", "B.tap", "C.tap"), provider.searched);
        assertEquals(Arrays.asList("A.tap", "B.tap", "C.tap"), watcher.saw);
        assertEquals(3, tally.done);
        assertEquals(3, tally.scraped);
        assertTrue(tally.complete());
    }

    /** And what came back is in the store, owned by the provider that fetched
     *  it - the same rule the one-game path follows. */
    @Test
    public void whatWasScrapedIsStoredUnderTheProvidersName() {
        Entry entry = game("Arkanoid.tap");
        run(new Fakes.Fake(), new Watching(), Collections.singletonList(entry));
        Metadata.refresh(context);

        Meta stored = Metadata.forPath(context, pathOf(entry));

        assertNotNull("the sweep wrote nothing", stored);
        assertEquals("Arkanoid.tap", stored.name);
        assertEquals("Fake", stored.source);
        assertFalse("a swept row must not read as ES-DE's", stored.isEsde());
    }

    /** A game the provider has never heard of is an outcome, not a failure -
     *  and on a Spectrum collection it is the common one. */
    @Test
    public void anUnknownGameIsCountedRatherThanFailed() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.answer = game -> Collections.emptyList();

        Sweep.Tally tally = run(provider, new Watching(), games("A.tap", "B.tap"));

        assertEquals(2, tally.unknown);
        assertEquals(0, tally.failed);
        assertEquals(0, tally.scraped);
        assertTrue("an unknown game must not stop a run", tally.complete());
    }

    // --- conflicts -------------------------------------------------------------------------

    /** The default leaves them alone, so the resume filter finds them again. */
    @Test
    public void severalCandidatesAreSkippedAndCountedByDefault() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.answer = game -> Arrays.asList(Fakes.exact("One"), Fakes.exact("Two"));

        Sweep.Tally tally = run(provider, new Watching(), games("A.tap"));

        assertEquals(1, tally.ambiguous);
        assertEquals(0, tally.scraped);
        assertTrue("nothing should have been fetched", provider.fetched.isEmpty());
    }

    /** One candidate the provider is not sure of is a conflict too. A guess
     *  acted on silently is one game's cover on another for ever. */
    @Test
    public void asingleUncertainCandidateIsAConflictAndNotAMatch() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.answer = game -> Collections.singletonList(Fakes.guess("Maybe"));

        assertEquals(1, run(provider, new Watching(), games("A.tap")).ambiguous);
    }

    /** "Take the best match" is the provider's own first answer and nothing
     *  cleverer - which is exactly what the screen offering it says. */
    @Test
    public void takingTheBestMatchUsesTheProvidersFirstAnswer() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.answer = game -> Arrays.asList(Fakes.guess("First"), Fakes.guess("Second"));

        Sweep.Tally tally = run(provider, new Watching(), Sweep.Conflicts.BEST,
                                games("A.tap"));

        assertEquals(1, tally.scraped);
        assertEquals(Collections.singletonList("First"), provider.fetched);
    }

    /** Asking hands the whole list over and uses whatever comes back. */
    @Test
    public void askingUsesTheChoiceItIsGiven() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.answer = game -> Arrays.asList(Fakes.guess("First"), Fakes.guess("Second"));

        Watching watcher = new Watching();
        watcher.choices = Collections.singletonList(Sweep.Choice.of(Fakes.guess("Second")));

        Sweep.Tally tally = run(provider, watcher, Sweep.Conflicts.ASK, games("A.tap"));

        assertEquals(Collections.singletonList("A.tap"), watcher.asked);
        assertEquals(1, tally.scraped);
        assertEquals(Collections.singletonList("Second"), provider.fetched);
    }

    /** And skipping one game is not cancelling the run. */
    @Test
    public void skippingOneConflictCarriesOnToTheNextGame() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.answer = game -> Arrays.asList(Fakes.guess("First"), Fakes.guess("Second"));

        Watching watcher = new Watching();
        watcher.choices = Collections.singletonList(Sweep.Choice.skip());

        Sweep.Tally tally = run(provider, watcher, Sweep.Conflicts.ASK,
                                games("A.tap", "B.tap"));

        assertEquals(2, tally.ambiguous);
        assertEquals(2, watcher.asked.size());
        assertTrue(tally.complete());
    }

    /**
     * The escape hatch: stop asking, finish the rest unattended.
     *
     * The reason {@link Sweep.Choice} has three states instead of being a
     * nullable candidate. After thirty dialogs a person wants out, and without
     * this the only way out of a long tail of conflicts is cancelling the
     * whole run and losing the games still ahead of it - so this asserts both
     * halves: no more dialogs, and the run still finished.
     */
    @Test
    public void skipTheRestStopsAskingAndStillFinishesTheRun() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.answer = game -> Arrays.asList(Fakes.guess("First"), Fakes.guess("Second"));

        Watching watcher = new Watching();
        watcher.choices = Collections.singletonList(Sweep.Choice.skipTheRest());

        Sweep.Tally tally = run(provider, watcher, Sweep.Conflicts.ASK,
                                games("A.tap", "B.tap", "C.tap"));

        assertEquals("it went on asking after being told to stop",
                     Collections.singletonList("A.tap"), watcher.asked);
        assertEquals(3, tally.done);
        assertEquals(3, tally.ambiguous);
        assertTrue("stopping the questions must not stop the run", tally.complete());
    }

    // --- somebody's own work ------------------------------------------------------------

    /**
     * A hand-edited row is left alone, and costs nothing to leave alone.
     *
     * The one-game path asks before overwriting one. A run of three hundred
     * cannot ask three hundred times, so it skips - and the check is local and
     * happens <b>before the search</b>, which is the half worth asserting:
     * asking the service about a game whose answer is going to be thrown away
     * would spend the day's allowance on nothing.
     */
    @Test
    public void ahandEditedRowIsLeftAloneWithoutSpendingARequest() {
        Entry mine = game("Mine.tap");
        Metadata.put(context,
                     Meta.at(pathOf(mine)).name("What I called it").source(Meta.USER).build());
        Metadata.refresh(context);

        Fakes.Fake provider = new Fakes.Fake();
        Sweep.Tally tally = run(provider, new Watching(), Arrays.asList(mine, game("B.tap")));

        assertEquals(1, tally.yours);
        assertEquals(1, tally.scraped);
        assertEquals("the hand-edited game should never have been asked about",
                     Collections.singletonList("B.tap"), provider.searched);
        assertEquals("What I called it", Metadata.forPath(context, pathOf(mine)).name);
    }

    // --- stopping ---------------------------------------------------------------------------

    /** Cancel is noticed between games, and says so in the tally. */
    @Test
    public void cancellingStopsBetweenGames() {
        Fakes.Fake provider = new Fakes.Fake();
        Watching watcher = new Watching();
        watcher.cancelAfter = 2;

        Sweep.Tally tally = run(provider, watcher, games("A.tap", "B.tap", "C.tap"));

        assertEquals(2, provider.searched.size());
        assertEquals(2, tally.done);
        assertTrue(tally.cancelled);
        assertFalse(tally.complete());
        assertNull("cancelling is not a failure", tally.stopped);
    }

    /**
     * A spent allowance stops the run before it asks anything.
     *
     * <b>Asked, never waited for.</b> Part two forced the counter to 100000
     * against an allowance of 10000 and still got a 200 with a real candidate
     * back: ScreenScraper does not refuse when an account is over. The
     * counters in every reply are the only warning there is, so a run that
     * waited to be refused would never stop at all.
     */
    @Test
    public void aspentAllowanceStopsTheRunBeforeAskingAnything() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.quota = new Quota(10000, 10000, 1);

        Sweep.Tally tally = run(provider, new Watching(), games("A.tap", "B.tap"));

        assertTrue("nothing should have been asked", provider.searched.isEmpty());
        assertEquals(0, tally.done);
        assertNotNull(tally.stopped);
        assertEquals(ScrapeException.Kind.QUOTA_EXCEEDED, tally.stopped.kind);
    }

    /** Not enough left for one whole game is the same as none: a search whose
     *  media cannot be fetched has spent a request for half an answer. */
    @Test
    public void notEnoughLeftForAWholeGameIsAsGoodAsNone() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.quota = new Quota(9998, 10000, 1);

        Sweep.Tally stopped = Sweep.run(context, provider, new Fakes.NoHttp(), games("A.tap"),
                                        Provider.Wanted.usual(), Sweep.Conflicts.SKIP,
                                        new Watching());

        assertNotNull("four requests wanted, two left", stopped.stopped);
        assertTrue(provider.searched.isEmpty());
    }

    /** An allowance nobody has stated does not stop anything - refusing to try
     *  on a guess is worse than one refused request. */
    @Test
    public void anUnknownAllowanceStopsNothing() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.quota = Quota.unknown();

        assertTrue(run(provider, new Watching(), games("A.tap")).complete());
    }

    /** Nor does a provider that reports no quota at all - the other two of the
     *  three planned may well not. */
    @Test
    public void aproviderThatReportsNoQuotaAtAllStopsNothing() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.quota = null;

        assertTrue(run(provider, new Watching(), games("A.tap")).complete());
    }

    /** A refused password is every remaining game as well as this one. */
    @Test
    public void refusedCredentialsStopTheWholeRun() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.answer = game -> {
            throw new ScrapeException(ScrapeException.Kind.BAD_CREDENTIALS, "no");
        };

        Sweep.Tally tally = run(provider, new Watching(), games("A.tap", "B.tap", "C.tap"));

        assertEquals("it should not have asked twice", 1, provider.searched.size());
        assertEquals(ScrapeException.Kind.BAD_CREDENTIALS, tally.stopped.kind);
    }

    /** A reply that would not parse is one game's problem, not the run's. */
    @Test
    public void amalformedReplyIsOneGameAndNotTheRun() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.answer = game -> {
            if (game.filename().equals("B.tap")) {
                throw new ScrapeException(ScrapeException.Kind.MALFORMED, "eh?");
            }
            return Collections.singletonList(Fakes.exact(game.filename()));
        };

        Sweep.Tally tally = run(provider, new Watching(), games("A.tap", "B.tap", "C.tap"));

        assertEquals(2, tally.scraped);
        assertEquals(1, tally.failed);
        assertEquals(3, tally.done);
        assertTrue(tally.complete());
    }

    // --- waiting things out --------------------------------------------------------------

    /**
     * A thread limit is waited out rather than counted.
     *
     * The ordinary reason a loop this shape stumbles, and it clears by itself
     * in a second or two. Counting it as a failure would lose a game to a
     * hiccup; stopping for it would lose the collection.
     */
    @Test
    public void athreadLimitIsWaitedOutAndTheGameRetried() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.answer = new Fakes.Answer() {
            int attempts;

            @Override
            public List<Candidate> to(Provider.Game game) throws ScrapeException {
                if (++attempts == 1) {
                    throw new ScrapeException(ScrapeException.Kind.THREAD_LIMIT, "slow down");
                }
                return Collections.singletonList(Fakes.exact(game.filename()));
            }
        };

        Sweep.Tally tally = run(provider, new Watching(), games("A.tap"));

        assertEquals("it should have asked twice", 2, provider.searched.size());
        assertEquals(1, tally.scraped);
        assertEquals(0, tally.failed);
    }

    /** And is given up on eventually, without taking the run down with it. */
    @Test
    public void agameThatKeepsFailingIsGivenUpOnAndTheRunCarriesOn() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.answer = game -> {
            if (game.filename().equals("A.tap")) {
                throw new ScrapeException(ScrapeException.Kind.NETWORK, "gone");
            }
            return Collections.singletonList(Fakes.exact(game.filename()));
        };

        Sweep.Tally tally = run(provider, new Watching(), games("A.tap", "B.tap"));

        assertEquals("three attempts at the bad one, one at the good", 4,
                     provider.searched.size());
        assertEquals(1, tally.failed);
        assertEquals(1, tally.scraped);
        assertTrue(tally.complete());
    }

    // --- media ------------------------------------------------------------------------------

    /**
     * The media count is the whole run's, not the last game's.
     *
     * Worth its own test because it is an accumulation, and an accumulation
     * that assigned instead of adding would read as "1 picture" after
     * scraping three hundred games and look like a download problem rather
     * than an arithmetic one.
     */
    @Test
    public void mediaAreCountedAcrossTheWholeRun() throws IOException {
        Fakes.Fake provider = new Fakes.Fake();
        provider.media = Collections.singletonList(
                new Medium("covers", "http://example.invalid/cover.png", "png", null));

        List<Entry> entries = games("A.tap", "B.tap");

        try {
            Sweep.Tally tally = Sweep.run(context, provider, new Writes(), entries,
                                          Provider.Wanted.of("covers"),
                                          Sweep.Conflicts.SKIP, new Watching());

            assertEquals(2, tally.scraped);
            assertEquals("one cover each, counted across both", 2, tally.media);
        } finally {
            for (Entry entry : entries) {
                dev.ldlab.zedex.library.meta.Artwork
                        .fileFor(context, pathOf(entry), "covers", "png").delete();
            }
        }
    }

    /** Writes something plausible wherever it is pointed, so {@code Downloads}
     *  keeps the file rather than deleting it as empty. */
    private static final class Writes implements Http {
        @Override public Reply get(String url) {
            throw new AssertionError("the sweep should not be fetching a page itself");
        }

        @Override
        public String save(String url, File into) throws IOException {
            try (FileOutputStream out = new FileOutputStream(into)) {
                out.write(new byte[] { 1, 2, 3, 4 });
            }
            return null;
        }
    }

    /** Cancel is noticed while backing off, not only between games - a wait of
     *  six seconds that ignored it would make the button look broken. */
    @Test
    public void cancelIsNoticedWhileBackingOff() {
        Fakes.Fake provider = new Fakes.Fake();
        provider.answer = game -> {
            throw new ScrapeException(ScrapeException.Kind.NETWORK, "gone");
        };

        // Cancels the moment the first game is started, which is before the
        // first back-off begins.
        Watching watcher = new Watching();
        watcher.cancelAfter = 1;

        long began = android.os.SystemClock.uptimeMillis();
        Sweep.Tally tally = run(provider, watcher, games("A.tap", "B.tap"));
        long took = android.os.SystemClock.uptimeMillis() - began;

        assertEquals("it gave up waiting rather than serving out the back-off",
                     1, provider.searched.size());
        assertTrue("waited " + took + "ms; a served back-off would be 6000", took < 3000);
        assertEquals(1, tally.failed);
    }
}
