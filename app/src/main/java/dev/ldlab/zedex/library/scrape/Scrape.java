package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;

/**
 * Scraping one game, with no screen anywhere in it.
 *
 * Everything between "this row" and "the store and the media folder have it":
 * turning an {@link Entry} into something a provider can be asked about,
 * asking, writing what comes back. What is <em>not</em> here is the one
 * decision a person still has to make - which of several candidates -
 * because that needs a screen and this needs to be testable without one.
 *
 * So the shape is deliberately two calls: {@link #candidates} answers with
 * what was found, the caller decides, and {@link #apply} writes the one that
 * was chosen. A caller with exactly one certain candidate can go straight from
 * the first to the second without asking anybody, which is the ordinary case
 * and the whole point of matching on a hash.
 */
public final class Scrape {

    private static final String TAG = "Zedex";

    private Scrape() {
    }

    /**
     * What a provider thinks this row is.
     *
     * @param path the game's key, from {@code Metadata.relativePath} - never
     *             null here; a row with no path of its own cannot be scraped
     *             and the menu does not offer it
     */
    public static List<Candidate> candidates(Context context, Provider provider,
                                             Entry entry, String path)
            throws ScrapeException {
        return candidates(provider, entry, path, () -> md5Of(context, entry));
    }

    /**
     * The same question, with the hash supplied rather than read here.
     *
     * For a caller asking several services about one file: every one of them
     * wants the same hash, and it cannot have changed between one and the
     * next, so reading it once and handing it round saves reading the whole
     * file through the documents provider per service. {@code Blend} passes a
     * supplier that remembers - see {@code Blend.Once}.
     *
     * Still lazy either way: the supplier is only asked when a provider
     * actually reaches for the hash, and one that matches on the name alone
     * never makes it read anything.
     */
    public static List<Candidate> candidates(Provider provider, Entry entry, String path,
                                             java.util.function.Supplier<String> hash)
            throws ScrapeException {
        return provider.search(gameOf(entry, path, hash));
    }

    /**
     * Whether one answer is good enough to use without asking.
     *
     * Exactly one, and the provider sure of it - which for ScreenScraper means
     * the file's hash was in their database. Anything else is a guess, and a
     * guess acted on silently is one game's cover on another for ever.
     */
    public static boolean certain(List<Candidate> candidates) {
        return candidates.size() == 1 && candidates.get(0).exact;
    }

    /**
     * Writes one candidate: the facts into the store, the media onto disk.
     *
     * The row is stored under the provider's own name rather than {@link
     * Meta#ESDE}, so an ES-DE link leaves it alone afterwards - see {@link
     * Meta#isEsde}. It is not marked {@link Meta#USER} either: nobody typed
     * it, and the editor's "forget my edits" should not offer to undo
     * something a person did not write.
     *
     * The media are fetched after the facts are written, deliberately. A
     * scrape that got the metadata and then met a spent quota has still
     * improved the row, and losing that because the covers failed would be
     * the day's allowance spent for nothing.
     */
    public static Downloads.Result apply(Context context, Provider provider, Http http,
                                         Candidate candidate, String path,
                                         Provider.Wanted wanted) throws ScrapeException {
        Provider.Scraped scraped = provider.fetch(candidate, wanted);

        Metadata.put(context, owned(scraped.meta, path, provider.name()));

        try {
            return Downloads.fetch(context, http, provider, path, scraped.media,
                                   Downloads.into(context, path));
        } finally {
            // Whatever went wrong, what did arrive has to become visible - a
            // scrape stopped by a spent quota still fetched the covers it got
            // to, and leaving them behind a cached miss would waste them.
            // Downloads used to do this and cannot any more: it no longer
            // knows whether it wrote where anybody looks.
            Artwork.forget(path);
        }
    }

    /**
     * The scraped facts, keyed and owned.
     *
     * Two changes to what the provider sent, and everything else carried
     * across untouched - which is the whole reason {@link Meta#but} exists.
     * This method used to rebuild the row through a ten-argument constructor
     * and silently dropped the key map, and nothing failed or logged: the
     * store simply had no key map in it, and it took comparing a real scrape
     * against a live reply to see. Listing what changes rather than what stays
     * makes that class of bug unwriteable here.
     */
    static Meta owned(Meta from, String path, String providerName) {
        return from.but().path(path).source(providerName).build();
    }

    // --- turning a row into a question ------------------------------------------------

    private static Provider.Game gameOf(Entry entry, String path,
                                        java.util.function.Supplier<String> hash) {
        return new Provider.Game() {
            @Override public String path() { return path; }
            @Override public String filename() { return entry.name; }
            @Override public long size() { return entry.size; }

            @Override
            public String md5() {
                return hash.get();
            }
        };
    }

    /**
     * The file's MD5, or null when it cannot be read.
     *
     * The whole file, through the documents provider - which is why {@link
     * Provider.Game#md5} is asked for lazily and at most once. A Spectrum tape
     * is fifty to two hundred kilobytes, so this is cheap in absolute terms;
     * it is the round trip that costs, and it buys the one thing a filename
     * cannot: an answer the provider is certain of.
     *
     * Null rather than an exception. A file that cannot be read is a reason to
     * fall back to searching the name, not a reason to fail the scrape - the
     * name is what a person would have searched by anyway.
     */
    static String md5Of(Context context, Entry entry) {
        try (InputStream in = context.getContentResolver().openInputStream(entry.uri)) {
            if (in == null) return null;

            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            int read;

            while ((read = in.read(buffer)) != -1) md5.update(buffer, 0, read);

            StringBuilder hex = new StringBuilder(32);
            for (byte b : md5.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            Log.w(TAG, "cannot hash " + entry.name + "; falling back to its name", e);
            return null;
        }
    }
}
