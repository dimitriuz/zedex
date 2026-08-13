package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One game across several sources, in order, with no screen anywhere in it.
 *
 * The plural counterpart to {@link Scrape}, and the same shape for the same
 * reason: everything that needs a person happens <em>between</em> two calls,
 * so the loop itself can be run against fakes with no network and no device
 * dialog.
 *
 * <b>A source may fill a gap and may never overwrite.</b> That one rule is the
 * whole design - see {@link Merge}, and the paragraph in {@link Scrapers}
 * explaining what it replaced. Everything else here follows from it: a later
 * source is only asked for the folders still empty, a source with nothing left
 * to add is not asked at all, and no answer can undo an earlier one.
 */
public final class Blend {

    private static final String TAG = "Zedex";

    private Blend() {
    }

    /**
     * What a run does about media, which is the only place the two callers
     * differ.
     */
    public enum Media {

        /**
         * Only the folders with nothing in them, written straight to the media
         * folder.
         *
         * A sweep. Nothing is ever replaced, so there is nothing to protect
         * and nothing to choose between - and a source is never asked for a
         * cover that exists, which for ScreenScraper is a {@code mediaJeu.php}
         * call not made against the day's allowance.
         */
        FILL_GAPS,

        /**
         * Every wanted folder from every source, into the staging area.
         *
         * Scraping one game by hand, where the person is present and can be
         * shown the alternatives. Nothing is installed until {@link #commit}.
         */
        OFFER_ALTERNATIVES,
    }

    /**
     * Where a game's media actually gets fetched.
     *
     * The pictures come from hosts nobody paces - {@code
     * spectrumcomputing.co.uk} and {@code zxinfo.dk/media} are static files,
     * and ScreenScraper's own downloads are counted rather than spaced. The
     * <em>facts</em> come from an API this app deliberately waits between
     * calls to. So a sweep that fetches the pictures on the same thread that
     * is about to sleep before the next game's first request spends that
     * sleep doing nothing, having a download to do the whole time.
     *
     * This is the seam that lets a caller say otherwise. {@link #INLINE} does
     * what this class always did, which is what one game from the popup wants
     * - there is nothing else to be getting on with. A sweep hands over a
     * {@link Backlog} instead, which runs the download on one other thread
     * while the paced calls for the next game go out.
     *
     * One other thread, not several: two games' downloads never overlap each
     * other, so this asks no more of anybody's file host than the sequential
     * version did. The only thing that changes is what the scraping thread
     * does while it waits.
     */
    public interface Installs {

        /** How many files landed, thrown if the source has refused in a way
         *  that ends its part of the run. */
        interface Job {
            int run() throws ScrapeException;
        }

        /**
         * Do this game's media for that source, now or shortly.
         *
         * @return what landed, or 0 from an implementation that has not
         *         finished - or started - by the time it answers
         */
        int take(String source, Job job) throws ScrapeException;

        /**
         * Here and now, on the calling thread, and the count is the truth.
         *
         * The default, and what {@code ScrapeOneGame} uses: a person is
         * watching one game and there is no next game to be overlapping
         * with. A refusal thrown from here reaches {@code run}'s own
         * per-source catch exactly as it did before this interface existed.
         */
        Installs INLINE = (source, job) -> job.run();
    }

    /**
     * Which of several a source found, from whoever is watching.
     *
     * Called on the calling thread, which is never the UI thread. Null leaves
     * this source out for this game, which is a real answer and not a failure.
     */
    public interface Chooser {
        Candidate choose(String sourceName, List<Candidate> found, String game);
    }

    /** Whether whoever asked for this has given up - a sweep's Cancel, or
     *  nothing at all for a single game. */
    public interface Cancellable {
        boolean cancelled();
    }

    /** One picture waiting to be chosen between. */
    public static final class Staged {

        public final String folder;
        public final String extension;

        /** Which source offered it, for the sheet to name. */
        public final String source;

        /** In the staging area, not where anything looks. */
        public final File file;

        /**
         * Something is already in that folder, and it is not these bytes.
         *
         * Half the question, not the whole of it - this one is only ever
         * about the file the user already has. Two sources offering two
         * different covers for a folder that was empty is a question too, and
         * one no single {@code Staged} can see; {@link Result#anythingContested}
         * is where the folder is looked at as a whole.
         */
        public final boolean contested;

        /**
         * What is already on disk in that folder, or null when it was empty.
         *
         * The other half of the question the sheet asks - it draws this
         * against {@link #file} - and carried here rather than looked up
         * there, because only this class knows which extension the existing
         * one turned out to have.
         */
        public final File existing;

        Staged(String folder, String extension, String source, File file,
               boolean contested, File existing) {
            this.folder = folder;
            this.extension = extension;
            this.source = source;
            this.file = file;
            this.contested = contested;
            this.existing = existing;
        }
    }

    /**
     * One, without a network behind it.
     *
     * Public for {@code ArtworkChoiceTest}, which is in another package and
     * needs the sheet's input without a live scrape to produce it. Nothing in
     * the app calls this - {@link #run} is the only thing that makes one for
     * real.
     */
    public static Staged staged(String folder, String extension, String source,
                                File file, boolean contested, File existing) {
        return new Staged(folder, extension, source, file, contested, existing);
    }

    /** One source's refusal, kept rather than thrown: the others are still
     *  worth asking. */
    public static final class Failure {
        public final String source;
        public final ScrapeException why;

        Failure(String source, ScrapeException why) {
            this.source = source;
            this.why = why;
        }
    }

    /** What a run came to. */
    public static final class Result {

        /** The merged row, already stored. */
        public final Meta meta;

        /** Files written straight into the media folder - {@link
         *  Media#FILL_GAPS} only. */
        public final int installed;

        /** Files waiting for {@link #commit} - {@link Media#OFFER_ALTERNATIVES}
         *  only. */
        public final List<Staged> staged;

        /**
         * The sources that answered - not that contributed.
         *
         * A source whose facts merged into nothing still counts: it really
         * was asked and really did reply, and telling that apart from a
         * source with no candidates at all is what {@link #ambiguous} is for.
         * Comparing what actually changed would need a field-by-field method
         * that could rot the way {@code nothingLeftToGain} almost did, for no
         * behaviour anything here relies on.
         */
        public final List<String> consulted;

        public final List<Failure> failures;

        /**
         * Whether some source found candidates that nobody chose between.
         *
         * The difference between "never heard of it" and "an afternoon with
         * the chooser", which a sweep's tally reports as two different
         * numbers because they need two different things done about them.
         */
        public final boolean ambiguous;

        /**
         * Whether anybody has to be asked anything about the pictures.
         *
         * Worked out here, where {@link #run} builds this on a worker
         * thread, rather than in the getter: deciding it means
         * hashing every staged file, and the one caller asks on the UI thread
         * with the sheet about to go up.
         */
        private final boolean anyQuestion;

        Result(Meta meta, int installed, List<Staged> staged, List<String> consulted,
               List<Failure> failures, boolean ambiguous) {
            this.meta = meta;
            this.installed = installed;
            this.staged = Collections.unmodifiableList(staged);
            this.consulted = Collections.unmodifiableList(consulted);
            this.failures = Collections.unmodifiableList(failures);
            this.ambiguous = ambiguous;
            this.anyQuestion = questionsIn(this.staged);
        }

        /** Whether anybody has to be asked anything about the pictures. */
        public boolean anythingContested() {
            return anyQuestion;
        }
    }

    /**
     * Every source in turn, each filling in what the ones before left out.
     *
     * The facts are stored before this returns - deliberately, and for the
     * reason {@code Scrape.apply} stores them before fetching media: a scrape
     * that got the metadata and then met a spent quota has still improved the
     * row, and a person who cancels the picture sheet should not lose it.
     *
     * @param path the game's key, from {@code Metadata.relativePath}
     */
    public static Result run(Context context, List<Provider> sources, Http http,
                             Entry entry, String path, Provider.Wanted wanted,
                             Media media, Chooser chooser, Cancellable cancel) {
        return run(context, sources, http, entry, path, wanted, media, chooser, cancel,
                   Installs.INLINE);
    }

    /**
     * The same, with somewhere else to put the downloading - see {@link
     * Installs}. Only a sweep passes anything but {@link Installs#INLINE}.
     */
    public static Result run(Context context, List<Provider> sources, Http http,
                             Entry entry, String path, Provider.Wanted wanted,
                             Media media, Chooser chooser, Cancellable cancel,
                             Installs installs) {
        // The store does not read itself - a caller reaching this before the
        // library has run in this process would find an empty cache and read
        // every already-scraped game as unscraped, which on a cold start
        // would overwrite the whole file down to this one row.
        Metadata.ensureLoaded(context);

        Meta known = Metadata.forPath(context, path);
        if (known == null) known = Meta.at(path).build();

        List<String> consulted = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        List<Staged> staged = new ArrayList<>();
        int installed = 0;
        boolean ambiguous = false;

        // One read of the file for the whole game, however many sources ask
        // about it - see Once. Built here rather than per source, which is the
        // whole point of it.
        Once hash = new Once(() -> Scrape.md5Of(context, entry));

        if (media == Media.OFFER_ALTERNATIVES) {
            // On the way in, not on the way out: a scrape killed mid-flight
            // never reaches its own cleanup, and last run's leftover would be
            // offered as though this run had fetched it.
            Artwork.clearStaging(context);
        }

        for (Provider source : sources) {
            Provider.Wanted mine = wantedFrom(context, path, wanted, media);

            if (media == Media.FILL_GAPS && nothingLeftToGain(known, mine)) break;

            final Meta soFar = known;

            try {
                // Retried per step rather than per source: a fetch that failed
                // after its search succeeded is re-fetched, not re-searched,
                // or the retry costs an extra request against the day's
                // allowance every time.
                Identified who = attempt(
                        () -> identify(source, entry, path, soFar, chooser, hash),
                        cancel);

                if (who.chosen == null) {
                    // Found something and nobody would say which. Not the same
                    // as never having heard of it, and a sweep counts the two
                    // separately because they need different things done about
                    // them. Read off the search that already happened - asking
                    // again would be a second request for a fact we hold.
                    if (who.hadCandidates) ambiguous = true;
                    continue;
                }

                Provider.Scraped answer = attempt(() -> source.fetch(who.chosen, mine),
                                                  cancel);

                known = Merge.of(known, answer.meta);
                consulted.add(source.name());

                if (media == Media.FILL_GAPS) {
                    final Provider.Scraped fetched = answer;

                    // Artwork's own cache is invalidated where the files
                    // actually land rather than after this loop, because with
                    // a Backlog that can be minutes later and on another
                    // thread - and a cache dropped before the file exists is
                    // a cache that re-reads the empty folder and remembers
                    // *that*.
                    installed += installs.take(source.name(), () -> {
                        int saved = Downloads.fetch(context, http, source, path,
                                                    fetched.media,
                                                    Downloads.into(context, path)).saved;
                        if (saved > 0) Artwork.forget(path);
                        return saved;
                    });
                } else {
                    staged.addAll(stage(context, http, source, path, answer.media));
                }
            } catch (ScrapeException e) {
                // One source's refusal is not the game's: the others may well
                // answer, and a game with three of its facts is better than a
                // game with none.
                Log.w(TAG, source.name() + " could not answer about " + path, e);
                failures.add(new Failure(source.name(), e));
            }
        }

        if (consulted.isEmpty() && installed == 0 && staged.isEmpty()) {
            // Nothing was learned. Metadata.put rewrites the whole store, and
            // a sweep of a real collection asks about far more games nobody
            // has heard of than it scrapes - writing every one of those
            // would be hundreds of megabytes of flash for rows that say
            // nothing, and each would then read back as "scraped" and never
            // be offered to a later source again.
            return new Result(known, installed, staged, consulted, failures, ambiguous);
        }

        Meta.Builder built = known.but().path(path);
        for (String name : consulted) built.contributor(name);

        Meta merged = built.build();
        Metadata.put(context, merged);

        return new Result(merged, installed, staged, consulted, failures, ambiguous);
    }

    /**
     * Installs the chosen staged media.
     *
     * At most one per folder; a folder named by none of them keeps whatever is
     * already there, which is what makes "Save without touching anything" a
     * no-op rather than a decision.
     *
     * @return how many files were installed
     */
    public static int commit(Context context, String path, List<Staged> chosen) {
        int installed = 0;

        for (Staged one : chosen) {
            File into = Artwork.fileFor(context, path, one.folder, one.extension);

            if (into.isFile() && !into.delete()) {
                Log.w(TAG, "cannot replace " + into);
                continue;
            }

            if (!one.file.renameTo(into)) {
                Log.w(TAG, "cannot install " + one.file + " as " + into);
                continue;
            }

            // The loser has to go, not merely be left unwritten: png outranks
            // jpg, so a new jpg beside an old png leaves the old one on screen
            // and the choice looks as though it did nothing.
            Artwork.removeOthers(context, path, one.folder, one.extension);
            installed++;
        }

        Artwork.clearStaging(context);
        if (installed > 0) Artwork.forget(path);

        return installed;
    }

    // --- which game is it ---------------------------------------------------------

    /** What a search came to: which entry, and whether there was anything to
     *  choose between. The second is why this is not just a {@link Candidate} -
     *  "nobody chose" and "nothing was found" are different outcomes and a
     *  null cannot say which. */
    private static final class Identified {
        final Candidate chosen;
        final boolean hadCandidates;

        Identified(Candidate chosen, boolean hadCandidates) {
            this.chosen = chosen;
            this.hadCandidates = hadCandidates;
        }
    }

    /**
     * Which entry this source thinks the file is, or null to leave it out.
     *
     * The first source is asked about the filename, because that is all
     * anybody knows. Every later one is asked about the <em>title</em> an
     * earlier source gave, which is what most services match well on - and a
     * single answer whose title is that title needs nobody, since it is the
     * same fact from two directions.
     *
     * <b>A hash still beats a title.</b> A source certain of its answer is
     * used even when the title disagrees: a hash match is the file itself,
     * where a title is what somebody typed on a shelf. The earlier name is
     * kept anyway - that source had priority.
     */
    private static Identified identify(Provider source, Entry entry, String path,
                                       Meta known, Chooser chooser,
                                       java.util.function.Supplier<String> hash)
            throws ScrapeException {
        String title = known.name;

        List<Candidate> found = title == null
                ? Scrape.candidates(source, entry, path, hash)
                : source.search(byTitle(entry, path, title, hash));

        if (found.isEmpty()) return new Identified(null, false);
        if (Scrape.certain(found)) return new Identified(found.get(0), true);

        if (title != null && found.size() == 1 && sameTitle(found.get(0).name, title)) {
            return new Identified(found.get(0), true);
        }

        return new Identified(chooser.choose(source.name(), found, entry.name), true);
    }

    // --- waiting out the failures worth waiting out --------------------------------

    /** How many times a step whose failure is worth waiting out is tried. */
    private static final int ATTEMPTS = 3;

    /** Between attempts, multiplied by which attempt this is: two seconds,
     *  then four. A thread limit clears in about that; longer would make a
     *  wobble halfway through a collection cost minutes. */
    private static final long BACKOFF_MS = 2000;

    /** How often the back-off looks up to see whether Cancel was pressed.
     *  Sleeping the whole two seconds would make Cancel appear broken. */
    private static final long CANCEL_POLL_MS = 200;

    /** One request, so that {@link #attempt} can retry either half of a scrape
     *  without knowing which it is holding. */
    private interface Step<T> {
        T run() throws ScrapeException;
    }

    /**
     * Runs one step, waiting out the failures that are worth waiting out.
     *
     * Only the two {@code ScrapeException.worthWaiting} kinds are retried. A
     * thread limit is the ordinary reason a loop this shape stumbles and it
     * clears by itself in a second or two; a network that went away often
     * comes back. Everything else is thrown at once, because trying a refused
     * password three times is three refusals.
     *
     * <b>{@code Sweep}'s own copy of this - {@code Step}, this method, {@link
     * #pause} and the three constants below - is gone; this is the only one
     * left.</b> It has to live here rather than there: the retry has to sit
     * inside the per-source loop or a wobble at one service ends that game at
     * every service, and {@code Sweep} does not have one of those to put it
     * in. It is per <em>step</em> rather than per source, so a fetch that
     * failed after its search succeeded is re-fetched and not re-searched.
     * Re-searching would cost an extra request against the day's allowance
     * every single time.
     */
    private static <T> T attempt(Step<T> step, Cancellable cancel) throws ScrapeException {
        ScrapeException last = null;

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (attempt > 1 && !pause(BACKOFF_MS * (attempt - 1), cancel)) break;

            try {
                return step.run();
            } catch (ScrapeException e) {
                if (!e.worthWaiting()) throw e;

                Log.w(TAG, "attempt " + attempt + " of " + ATTEMPTS + " met " + e.kind);
                last = e;
            }
        }

        throw last;
    }

    /** Waits, looking up often enough that Cancel still means something. False
     *  if it was cancelled or interrupted, in which case the caller should stop
     *  rather than try again. */
    private static boolean pause(long millis, Cancellable cancel) {
        long until = android.os.SystemClock.uptimeMillis() + millis;

        while (android.os.SystemClock.uptimeMillis() < until) {
            if (cancel.cancelled()) return false;

            try {
                Thread.sleep(CANCEL_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return !cancel.cancelled();
    }

    /**
     * The game as a later source should be asked about it: by title.
     *
     * A decorator rather than a change to {@link Provider}, because both
     * providers already derive their search term from {@code filename()} -
     * ZXInfo through {@code ZxInfo.titleOf}, ScreenScraper as {@code romnom} -
     * so handing them the title is all it takes.
     *
     * The hash is deliberately still offered: a later source that can match on
     * it should, and reading it is what {@code Provider.Game} makes lazy - so
     * {@link #md5()} delegates to {@link Scrape#md5Of}, the same lazy read a
     * first-source search already pays for, rather than answering null and
     * throwing away the one thing a title search cannot offer.
     */
    private static Provider.Game byTitle(Entry entry, String path, String title,
                                         java.util.function.Supplier<String> hash) {
        return new Provider.Game() {
            @Override public String path() { return path; }
            @Override public String filename() { return title; }
            @Override public long size() { return entry.size; }
            @Override public String md5() { return hash.get(); }
        };
    }

    /**
     * A value read at most once, however many times it is asked for.
     *
     * Every source in a run is asked about the same file, and both real
     * providers reach for the hash at the top of their own {@code search} - so
     * without this, a two-source sweep reads all eight hundred files twice
     * through the documents provider, and a three-source one three times, for
     * an answer that cannot have changed between one source and the next.
     *
     * <b>A null is remembered too.</b> A file that cannot be read answers null
     * and falls back to the name search, which is the right outcome - but
     * without the flag it would be re-attempted, and re-logged, once per
     * source for every unreadable file in the collection.
     *
     * Still lazy: nothing is read until a provider actually asks, so a service
     * that matches on the name alone still costs nothing.
     */
    static final class Once implements java.util.function.Supplier<String> {

        private final java.util.function.Supplier<String> read;

        private boolean taken;
        private String value;

        Once(java.util.function.Supplier<String> read) {
            this.read = read;
        }

        @Override
        public String get() {
            if (!taken) {
                value = read.get();
                taken = true;
            }
            return value;
        }
    }

    /** Case and surrounding space, and nothing more. Anything fuzzier is a
     *  guess, and a guess acted on silently is one game's cover on another for
     *  ever. */
    private static boolean sameTitle(String one, String other) {
        if (one == null || other == null) return false;

        return one.trim().toLowerCase(Locale.ROOT)
                .equals(other.trim().toLowerCase(Locale.ROOT));
    }

    // --- what to ask for ----------------------------------------------------------

    /**
     * Which folders this source should be asked to resolve.
     *
     * Under {@link Media#OFFER_ALTERNATIVES}, all of them: the sheet cannot
     * offer a choice it did not fetch. Under {@link Media#FILL_GAPS}, only the
     * folders with nothing in them.
     */
    private static Provider.Wanted wantedFrom(Context context, String path,
                                              Provider.Wanted wanted, Media media) {
        if (media == Media.OFFER_ALTERNATIVES) return wanted;

        Set<String> empty = new LinkedHashSet<>();

        for (String folder : wanted.folders()) {
            if (existing(context, path, folder) == null) empty.add(folder);
        }
        return Provider.Wanted.of(empty);
    }

    /**
     * Whether every source has been asked everything worth asking.
     *
     * Precise rather than a "well described" bar: every field a provider can
     * supply, and every wanted folder. In practice it almost never triggers,
     * which is the honest answer - a second source usually does have something
     * to add, and pretending otherwise would only hide the cost.
     *
     * Package-private so {@code BlendTest.everyMissingFieldIsSomethingLeftToGain}
     * can walk it field by field: a field added to {@link Meta} and forgotten
     * here means a source is silently never asked about it, with nothing on
     * screen to say the answer is thinner than it should be.
     */
    static boolean nothingLeftToGain(Meta known, Provider.Wanted stillWanted) {
        if (stillWanted.any()) return false;

        for (Meta.Field field : Meta.Field.values()) {
            if (known.get(field) == null) return false;
        }

        return known.desc != null && known.keymap != null && known.price != null
                && known.series != null
                && !known.authors.isEmpty() && !known.seriesGames.isEmpty()
                && !known.compilations.isEmpty() && !known.contents.isEmpty();
    }

    // --- staging ------------------------------------------------------------------

    /** Fetches one source's media into the staging area and says which of them
     *  something is already holding. */
    private static List<Staged> stage(Context context, Http http, Provider source,
                                      String path, List<Medium> media)
            throws ScrapeException {
        List<Staged> staged = new ArrayList<>();
        if (media.isEmpty()) return staged;

        // Per source, so two sources' covers do not overwrite each other on
        // the way in - the folder is the same, and so is the game's stem.
        Downloads.Destination into = (folder, extension) ->
                Artwork.stagingFileFor(context, path, folder + "/" + source.name(),
                                       extension);

        Downloads.Result result = Downloads.fetch(context, http, source, path, media, into);

        // Every medium in `media` contributes to exactly one of result.saved
        // or result.failed - a source that genuinely had nothing for a
        // medium lands in failed, and that is the ordinary case, expected
        // for most media on most sources, and not worth a line in the log.
        // Counted here so the loop below can tell it apart from the other
        // way to end up with no file: fetched, reported as saved, and still
        // not found under the name it should have landed as. That second
        // kind is exactly what swallowed the AY, silently, before landsAs
        // existed - and it must stay loud, or the next instance of this
        // class of bug is invisible again.
        int ordinaryMisses = 0;

        for (Medium medium : media) {
            // What was asked for is not what landed, for the two media that
            // are transformed on arrival: a tune is unpacked out of its zip
            // and a screen dump converted to a picture, and the downloaded
            // file is deleted either way. Looking for medium.extension
            // therefore looked for a file that no longer existed - so a tune
            // that had been fetched and unpacked perfectly well was never
            // staged, never offered, never installed, and nothing anywhere
            // logged a thing. Downloads knows what it wrote; ask it.
            String extension = Downloads.landsAs(medium);

            File file = into.fileFor(medium.folder, extension);
            if (!file.isFile() || file.length() == 0) {
                if (ordinaryMisses < result.failed) {
                    ordinaryMisses++;
                } else {
                    Log.w(TAG, "fetched " + medium.folder + " from " + source.name()
                               + " for " + path + " but found nothing at "
                               + file.getName() + " afterwards");
                }
                continue;
            }

            File already = existing(context, path, medium.folder);

            staged.add(new Staged(medium.folder, extension, source.name(), file,
                                  differs(already, file), already));
        }
        return staged;
    }

    /**
     * Whether any folder has more than one answer.
     *
     * Two questions, and both are questions: a picture that is not the one
     * the user already has, and - the half this missed - two sources offering
     * two different pictures for a folder that was empty. Only the first was
     * asked, so a first scrape of a game with nothing on disk took whichever
     * source happened to come first in the order and threw the other's cover
     * away without ever showing it. Nothing on disk is not nothing to choose
     * between.
     *
     * A folder whose offers are all the same bytes is still not a question -
     * see {@link #differs} for why that matters as much here as it does
     * against the user's own file.
     */
    private static boolean questionsIn(List<Staged> staged) {
        Map<String, String> seen = new HashMap<>();

        for (Staged one : staged) {
            if (one.contested) return true;

            String signature = signatureOf(one.file);
            String before = seen.put(one.folder, signature);

            if (before != null && !before.equals(signature)) return true;
        }

        return false;
    }

    /** What makes two files the same file here. A file that cannot be hashed
     *  answers only for itself, so it counts as different and the question
     *  gets asked - which is the safe way round: the cost is one dialog, and
     *  the other way silently drops a picture. */
    private static String signatureOf(File file) {
        String md5 = md5Of(file);
        return md5 != null ? file.length() + ":" + md5 : file.getPath();
    }

    /**
     * Whether this file is a question.
     *
     * Nothing there is no question - there is nothing to lose. The same bytes
     * is no question either: two services carrying the same scan is common,
     * and asking about it would be asking somebody to choose between a picture
     * and itself.
     */
    private static boolean differs(File existing, File staged) {
        if (existing == null) return false;

        if (existing.length() != staged.length()) return true;

        String mine = md5Of(staged);
        String theirs = md5Of(existing);

        return mine == null || theirs == null || !mine.equals(theirs);
    }

    /** This game's file in one media folder, whatever extension it was written
     *  with, or null.
     *
     * {@link Artwork#allExtensions()} rather than a list of its own: that list
     * exists precisely so nobody has to keep a second one in step with it, and
     * a second one that drifted would make a picture the user already has
     * invisible here - neither offered as contested nor kept, and deleted by
     * {@link #commit}'s call to {@link Artwork#removeOthers}. */
    static File existing(Context context, String path, String folder) {
        for (String extension : Artwork.allExtensions()) {
            File file = Artwork.fileFor(context, path, folder, extension);
            if (file.isFile() && file.length() > 0) return file;
        }
        return null;
    }

    private static String md5Of(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];

            for (int read; (read = in.read(buffer)) != -1; ) md5.update(buffer, 0, read);

            StringBuilder hex = new StringBuilder(32);
            for (byte b : md5.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            Log.w(TAG, "cannot hash " + file, e);
            return null;
        }
    }
}
