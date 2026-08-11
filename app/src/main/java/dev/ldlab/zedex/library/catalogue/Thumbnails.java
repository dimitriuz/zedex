package dev.ldlab.zedex.library.catalogue;

import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.work.Work;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Covers for the rows of the catalogue that are on screen, and only those.
 *
 * A row's picture is a url on an ordinary web host - {@code
 * Catalogue.Item.pictureUrl()} - and this is what turns that into a decoded
 * {@link Bitmap} without a grid asking twice for the same one or the app
 * asking for one no row wants any more. Two things this deliberately is not:
 * a queue, since nothing here is asked before its row is on screen, and a
 * client of {@code Pace} - see CLAUDE.md, "Every ScreenScraper medium is a
 * request; every ZXInfo medium is free" - these are static files, not API
 * calls, and pacing them the way {@code Pace} paces {@code api.zxinfo.dk}
 * would make the grid unusable for nothing bought in return. They still
 * carry the identity header, because {@link Http.Real} sends it on
 * everything, static file or not.
 *
 * <b>One request per url, however many rows ask.</b> A grid flung past a row
 * and back is the ordinary case, not an edge case, and doubling every fetch
 * for it is exactly the behaviour pattern that got this app's own address
 * blocked once. {@link #inFlight} is what makes a second caller join the
 * first rather than start another - see its own comment for why the check
 * and the start have to be one atomic step rather than two, or the guarantee
 * is luck rather than a proof - and for why every listener that joins is
 * told exactly once, whatever the fetch actually does.
 *
 * Bounded the same way {@code PictureCache} is - an {@link LruCache} sized
 * from a fraction of this process's own heap, evicting the least-recently
 * used cover once it is full, rather than a count of rows guessed to be
 * enough for every screen size this runs on.
 */
public final class Thumbnails {

    private Thumbnails() {
    }

    /** Told once a cover has arrived - or has not, in which case {@code
     *  picture} is null and the row keeps whatever placeholder it already
     *  draws. Told exactly once for the same {@link #load} call. */
    public interface Listener {
        void ready(String url, Bitmap picture);
    }

    /**
     * The row this is decoded for, in dp.
     *
     * A full-size cover scan decoded for anything bigger than the row that
     * draws it is the measured mistake CLAUDE.md keeps: 1.9 GB and 663
     * threads once, from an {@code ImageView} nobody sized before decoding
     * into it. 140 to match the grid row {@code EntryAdapter} already
     * decodes to - the two are drawn at the same size and there is no
     * reason for this one to guess a different number.
     */
    private static final int TARGET_DP = 140;

    /**
     * One entry per url, sized the same fraction of the heap {@code
     * PictureCache} and {@code Scraped} use, for the same reason: this is
     * one of several caches sharing the process's memory rather than the
     * only claim on it, and the emulator - almost always what is running
     * behind the library - would rather have a large budget go unused than
     * a small one force a decode to happen twice.
     */
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) Math.max(Runtime.getRuntime().maxMemory() / 8, 1)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /**
     * Urls the service <b>answered</b>, with something that is not a
     * picture - a 404, a 410, a file {@code BitmapFactory} could not decode
     * - remembered so that scrolling the same row off screen and back does
     * not repeat a request already known to fail. The same shape {@code
     * Artwork} uses, caching a miss as {@code Uri.EMPTY} rather than
     * re-deriving it every time it is asked; see CLAUDE.md on this very
     * address having been blocked once for exactly this pattern of repeated
     * requests for nothing. Bounded by entry count rather than by heap
     * fraction the way {@link #cache} is - a remembered failure costs a few
     * bytes, not a decoded bitmap, so sizing it off memory pressure would
     * answer a question this map does not raise.
     *
     * <b>Only a permanent answer goes in here, never a failure to get one
     * and never a "not now".</b> The justification above - "a request
     * already known to fail" - is true of a 404 and false of a timeout: a
     * phone that lost its tunnel for one moment used to have that whole
     * screenful of covers blacklisted for the life of the process, since
     * nothing in the app calls {@link #forget} and {@code
     * CatalogueView.retry()} re-asks for the <em>page</em> rather than for
     * its covers. So the rule is what the failure says about the url and not
     * how it was thrown: the request got a reply and the reply was not a
     * picture, which nothing but a new file upstream will change; or the url
     * is fine and the moment was not, which is {@link #cooling}'s business.
     */
    private static final LruCache<String, Boolean> failed = new LruCache<>(512);

    /**
     * The other half of a failure: urls that may well work, but not yet, and
     * the earliest moment each may be asked again.
     *
     * <b>Why this exists at all.</b> Making transient failures retryable
     * without this makes them retryable <em>without limit</em>: nothing
     * remembers the attempt, so every rebind of a visible row starts another
     * fetch. A grid does not bind once - a scroll, a rotation, a {@code
     * setRows} all rebind every row on screen - so an offline phone or a
     * host that is turning requests away gets a fresh screenful of requests
     * per bind, on the shared {@code Work} pool, with {@code
     * CatalogueView.fetch()} and its own <b>Try again</b> button queued
     * behind them. The old blanket blacklist was wrong, but it did bound
     * this at one attempt per url per process, and nothing replaced that
     * bound.
     *
     * <b>The bound.</b> After a transient failure a url is not asked again
     * until its cooldown has passed, and the cooldown doubles with each
     * consecutive failure up to {@link #MAX_COOLDOWN_MS}. So a row rebinding
     * in a loop cannot make more than one request per cooldown per url, and
     * the rate falls away to one a minute rather than climbing - which is
     * the property that matters, since it holds however often the row is
     * rebound and however long the phone stays offline. Nothing here is ever
     * permanent: when the tunnel comes back the next bind after the current
     * cooldown fetches the cover, with nobody having to kill the app.
     *
     * <b>Why a 429 waits from the very first one.</b> 429 is the host
     * explicitly asking for less traffic, and this app's own address has
     * been blocked once at the network layer for "behaviour patterns" - see
     * CLAUDE.md. Answering that with an immediate re-ask is the behaviour it
     * complained about, so a refusal the host <em>sent</em> - 429, 408, a
     * 5xx - starts its cooldown at {@link #FIRST_COOLDOWN_MS} straight away.
     * A failure to reach anybody is different: nobody was troubled by it and
     * it says nothing about the url, so the first one costs no wait at all
     * and only a second consecutive one starts the cooldown. That is the
     * whole difference between the two, and it is one line in {@link
     * Outcome}.
     *
     * Bounded by entry count for the same reason {@link #failed} is - a
     * deferred url costs a long and an int, not a bitmap.
     */
    private static final LruCache<String, Cooldown> cooling = new LruCache<>(512);

    /** How long a url has been failing transiently, and until when it must
     *  not be asked. Mutated only under the {@link #inFlight} lock. */
    private static final class Cooldown {
        /** Consecutive transient failures, reset by a picture arriving. */
        int failures;

        /** {@code System.nanoTime()} before which no request may be made. */
        long until;
    }

    /** The first wait after a refusal the host sent, and the first after a
     *  second consecutive failure to reach it - long enough that a row
     *  rebinding as fast as a grid can lay out makes one request rather than
     *  hundreds, short enough that somebody watching a blank cover does not
     *  notice the wait when the network comes back. */
    private static final long FIRST_COOLDOWN_MS = 1_000;

    /** Where the doubling stops. A cover nobody has managed to fetch eight
     *  times running is not worth more than one request a minute, and a
     *  minute is short enough that a phone reconnecting recovers by itself
     *  rather than needing the app killed. */
    private static final long MAX_COOLDOWN_MS = 60_000;

    /**
     * What a fetch came to, which is the only thing {@link #finish} needs to
     * know to file it.
     *
     * The field is the wait the <em>first</em> failure of that kind buys;
     * each consecutive one doubles it. See {@link #cooling} for why the two
     * transient kinds differ in exactly this and nothing else.
     */
    private enum Outcome {
        /** The host answered, and will answer the same tomorrow: a 404, a
         *  410, bytes {@code BitmapFactory} cannot decode. Remembered in
         *  {@link #failed} and never asked again. */
        PERMANENT(-1),

        /** The host answered and asked for less - 429, 408, a 5xx. Its own
         *  request, so it is honoured from the first one. */
        TOLD_TO_WAIT(FIRST_COOLDOWN_MS),

        /** Nothing came back at all: a timeout, a lost tunnel, a name that
         *  did not resolve. Troubled nobody and says nothing about the url,
         *  so the very next bind may ask again. */
        UNREACHABLE(0);

        final long firstCooldownMs;

        Outcome(long firstCooldownMs) {
            this.firstCooldownMs = firstCooldownMs;
        }
    }

    /**
     * Every url a fetch is currently running for, and who is waiting to be
     * told when it finishes.
     *
     * <p>The key's presence is the only fact that decides "start a fetch"
     * from "join the one already running", so {@link #load} makes that
     * decision - together with the {@link #cache}, {@link #failed} and
     * {@link #cooling} lookups, since a caller must tell all four apart in
     * one look - inside a single block synchronized on this map, never as
     * separate steps a second caller could land between.
     *
     * <p>The same lock guards the other end: {@link #finish} files the
     * answer (into {@link #cache} on a picture, {@link #failed} on a
     * permanent refusal, {@link #cooling} on a transient one) and removes
     * this map's entry inside one more synchronized block, so a
     * caller can never observe "not cached, not a known failure, and nobody
     * is fetching it" while an answer is on its way in but not yet filed -
     * the one interleaving that would let a second, needless fetch start.
     * That is what makes "one request per url" a guarantee rather than
     * something that happens to hold under one timing and stops holding
     * under another.
     *
     * <p>It is also what guarantees every listener that ever entered this
     * map is told exactly once. A listener is added to a url's list inside
     * the same synchronized block that confirms the url is (or is about to
     * become) in flight, so it can never be added after {@link #finish} has
     * already removed and notified that same list - there is no state
     * between "still in flight" and "answered" for it to be added into by
     * mistake. And {@link #finish} itself runs exactly once per fetch,
     * reached through {@link #fetch}'s own {@code finally} whatever the
     * fetch actually did - decoded a picture, threw, or ran out of memory -
     * so a list, once handed to {@link #finish}, is always notified and
     * never notified twice.
     *
     * <p>{@link #forget} deliberately does not touch this map. Clearing an
     * in-flight entry here - rather than only a finished answer in {@link
     * #cache} or {@link #failed} - is exactly what would strand its
     * listeners: {@link #finish} would find nothing to remove and notify
     * nobody, including the very caller that started the fetch.
     */
    private static final Map<String, List<Listener>> inFlight = new HashMap<>();

    /**
     * Whatever is cached for {@code url} - null on a miss. Cache only, and
     * never blocks: a row binds this while the recycler is laying out and
     * must not wait on a network read to draw itself. A miss here is
     * ordinary, not an error - the row draws its placeholder and {@link
     * #load} is what actually goes and gets one.
     */
    public static Bitmap get(String url) {
        if (url == null || url.isEmpty()) return null;
        return cache.get(url);
    }

    /**
     * {@link #get}, fetching on a miss.
     *
     * A null or empty {@code url} is not a request - plenty of catalogue
     * entries have no picture at all, and those rows are text rows; asking
     * for nothing here would only be a wasted round trip through {@link
     * Work}. A url the host has already answered with something that is not
     * a picture - see {@link #failed} - is not re-requested either, for the
     * same reason: a row scrolling back on screen must not repeat a request
     * already known to fail. A url whose last attempt failed
     * <em>transiently</em> is asked again, but not before its cooldown has
     * passed - see {@link #cooling} for what that bounds, and for why a 429
     * in particular buys one from the very first refusal.
     *
     * A cache hit, a known failure, or a url still cooling down answers
     * {@code listener} at once, on the calling thread, exactly as {@code
     * Scraped.picture} already does for its own cache hit; an actual miss
     * fetches on {@link Work#run} and answers through {@link Work#onMain},
     * so a caller never has to guess which thread it will be told on.
     */
    public static void load(Context context, Http http, String url, Listener listener) {
        if (url == null || url.isEmpty()) return;

        Bitmap cached;
        boolean knownFailure = false;
        boolean start = false;

        synchronized (inFlight) {
            cached = cache.get(url);
            if (cached == null) {
                // Both mean "no picture, and make no request": one because
                // asking again can never help, the other because asking
                // again this soon is the flood cooling exists to stop.
                if (Boolean.TRUE.equals(failed.get(url)) || tooSoon(url)) {
                    knownFailure = true;
                } else {
                    List<Listener> waiting = inFlight.get(url);
                    if (waiting == null) {
                        waiting = new ArrayList<>();
                        inFlight.put(url, waiting);
                        start = true;
                    }
                    waiting.add(listener);
                }
            }
        }

        if (cached != null) {
            listener.ready(url, cached);
            return;
        }

        if (knownFailure) {
            listener.ready(url, null);
            return;
        }

        if (!start) return; // somebody else is already fetching this url

        Context app = context.getApplicationContext();
        int targetPx = pixels(app, TARGET_DP);

        Work.run("thumbnail", () -> fetch(app, http, url, targetPx));
    }

    /**
     * Drops every cover held and every remembered failure - a fresh {@link
     * #load} for anything tries again as though nothing had been asked
     * before.
     *
     * <b>Does not touch a fetch already in flight.</b> That fetch's own
     * {@link #finish} still runs when it completes and still notifies
     * whatever listeners joined it - {@link #inFlight} is untouched here on
     * purpose; see its own comment for why clearing it is exactly what would
     * strand those listeners. It can be called while the grid is live and a
     * row's cover mid-fetch, so a permanently blank row is the one outcome
     * that is worse than this method doing slightly less than "forget"
     * suggests. What it does free is only what {@link #finish} would
     * otherwise have found already filed - a fetch still in flight simply
     * files its answer into an empty cache when it lands.
     *
     * <b>Nothing calls this when a view goes away, and that is deliberate.</b>
     * A rotation detaches the catalogue's grid, and emptying the cache on one
     * would mean fetching a screenful of covers all over again from a host
     * that blocked this app's address once for behaviour patterns: the cache
     * is meant to outlive a detach. This is for a deliberate emptying, and
     * memory pressure would be its honest caller - there is no such caller
     * yet. Leaving it uncalled leaks nothing: {@link #cache} is bounded by a
     * fraction of the heap and evicts its own least-recently-used entry.
     *
     * <b>And no screen needs it to undo a bad moment.</b> A retry button that
     * had to call this to work would mean {@link #failed} was remembering
     * things it should not: what goes in there is only what the host itself
     * answered permanently, so there is nothing for a person's second
     * attempt to clear. A cover still inside a {@link #cooling} wait is not
     * re-asked by that tap either, and deliberately: a person tapping <b>Try
     * again</b> twice must not be a way round a bound that exists to stop
     * this app flooding a host that asked it not to. The wait is seconds,
     * and the covers land by themselves.
     */
    public static void forget() {
        synchronized (inFlight) {
            cache.evictAll();
            failed.evictAll();
            cooling.evictAll();
        }
    }

    private static void fetch(Context context, Http http, String url, int targetPx) {
        Bitmap picture = null;
        File file = new File(context.getCacheDir(), "thumbnail-" + System.nanoTime());

        // How this ended, which decides whether the url is remembered, made
        // to wait, or neither. It starts PERMANENT because the ordinary end
        // of this method is a decode that either worked or did not, and a
        // decode that did not is the host's final answer about this url.
        Outcome outcome = Outcome.PERMANENT;

        try {
            try {
                http.save(url, file);
                picture = decode(file, targetPx);
            } finally {
                file.delete();
            }
        } catch (Http.Refused refused) {
            // A reply, and a refusal. Most of them are permanent - a 404 for
            // a picture ZXDB does not have is the commonest thing here - but
            // two are the host asking for a moment rather than saying no:
            // 408 is its own timeout and 429 is "not so fast", and
            // blacklisting a url over either would punish a row for the state
            // of the queue when it happened to scroll past. They do not
            // buy an immediate re-ask, though: a host that said 429 asked
            // for less traffic, so its cooldown starts at once. See cooling.
            outcome = permanent(refused.status) ? Outcome.PERMANENT : Outcome.TOLD_TO_WAIT;
            picture = null;
        } catch (IOException e) {
            // Nothing came back: a timeout, a lost tunnel, a name that did
            // not resolve. Says nothing whatever about the url and troubled
            // nobody, so it is not remembered and the next bind asks again -
            // once. A second failure in a row starts a cooldown like any
            // other, or an offline phone rebinds its way into a flood.
            outcome = Outcome.UNREACHABLE;
            picture = null;
        } catch (Throwable t) {
            // The bytes arrived and could not be made into a picture: a
            // corrupt file, a format BitmapFactory does not know, or a decode
            // too large to fit in memory - an OutOfMemoryError, not an
            // Exception; PictureCache.decodeFresh catches the same width for
            // the same reason. Any of them means "no picture", never "leave
            // this url in flight for ever": the outer finally below always
            // runs regardless of what was thrown here, so every listener that
            // joined is still told and no future load() for this url is left
            // joining a fetch that can never finish.
            picture = null;
        } finally {
            // Reached on every path out of the try above - success, any of
            // the caught failures, or anything this method did not anticipate
            // - which is what makes finish() run exactly once per fetch
            // rather than a catch clause hoped to be wide enough. See
            // inFlight's own comment for why that is what guarantees every
            // listener is told exactly once.
            finish(url, picture, outcome);
        }
    }

    /** Whether a status the host actually sent means "and it will say the
     *  same tomorrow". Everything it did not answer at all is decided by
     *  {@link #fetch}'s own {@code outcome}, not here. */
    private static boolean permanent(int status) {
        if (status == 408 || status == 429) return false;

        return status < 500;
    }

    /**
     * Whether {@code url} is inside a cooldown a transient failure gave it.
     *
     * Called from {@link #load} with the {@link #inFlight} lock already
     * held, together with the {@link #cache} and {@link #failed} lookups -
     * a caller must tell all of them apart in one look, or two rebinds can
     * both decide to fetch. Compared by subtraction rather than by {@code
     * <}, since {@code nanoTime} is only meaningful as a difference.
     */
    private static boolean tooSoon(String url) {
        Cooldown held = cooling.get(url);
        return held != null && System.nanoTime() - held.until < 0;
    }

    /**
     * Puts {@code url} out of reach until its cooldown has passed, doubling
     * the wait for each consecutive transient failure up to {@link
     * #MAX_COOLDOWN_MS}.
     *
     * The doubling is the bound: however often a row rebinds, the url can be
     * asked at most once per wait, and the wait grows while the failures
     * keep coming. Called with the {@link #inFlight} lock held.
     */
    private static void defer(String url, Outcome outcome) {
        Cooldown held = cooling.get(url);
        if (held == null) {
            held = new Cooldown();
            cooling.put(url, held);
        }
        held.failures++;

        long ms = outcome.firstCooldownMs;
        for (int failure = held.failures; failure > 1 && ms < MAX_COOLDOWN_MS; failure--) {
            // A first wait of zero - what UNREACHABLE buys - has to reach
            // FIRST_COOLDOWN_MS on the second failure rather than doubling
            // zero into zero for ever.
            ms = ms == 0 ? FIRST_COOLDOWN_MS : ms * 2;
        }

        held.until = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.min(ms, MAX_COOLDOWN_MS));
    }

    /**
     * The one place a fetch ends, always reached through {@link #fetch}'s
     * own {@code finally}. Files the answer and hands the url's own waiters
     * to it, atomically with {@link #load}'s hit / known-failure / join /
     * start decision - see {@link #inFlight}'s comment for why that
     * atomicity is what guarantees every listener is told exactly once.
     *
     * @param outcome what the fetch came to. Only {@link Outcome#PERMANENT}
     *                is remembered in {@link #failed}; the other two defer
     *                the url instead - see {@link #cooling}.
     */
    private static void finish(String url, Bitmap picture, Outcome outcome) {
        List<Listener> waiting;

        synchronized (inFlight) {
            if (picture != null) {
                // A cover that arrived clears the url's failure history
                // outright: the next transient failure starts from the
                // shortest wait rather than from wherever the last bad
                // afternoon left it.
                cache.put(url, picture);
                cooling.remove(url);
            } else if (outcome == Outcome.PERMANENT) {
                failed.put(url, Boolean.TRUE);
                cooling.remove(url);
            } else {
                defer(url, outcome);
            }
            waiting = inFlight.remove(url);
        }

        if (waiting == null) return;

        for (Listener listener : waiting) {
            Work.onMain(() -> listener.ready(url, picture));
        }
    }

    private static Bitmap decode(File file, int targetPx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx);
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    /** Same reasoning as {@code PictureCache.sampleSize}: the largest
     *  power-of-two downsample that still leaves at least {@code targetPx}
     *  on the shorter side, since {@code BitmapFactory}'s own {@code
     *  inSampleSize} only understands powers of two. */
    private static int sampleSize(int width, int height, int targetPx) {
        if (targetPx <= 0) return 1;

        int shorter = Math.min(width, height);
        int sample = 1;
        while (shorter / (sample * 2) >= targetPx) {
            sample *= 2;
        }
        return sample;
    }

    private static int pixels(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
