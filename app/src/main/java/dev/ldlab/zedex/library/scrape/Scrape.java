package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.Entry;
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
 * asking, writing what comes back. What is <em>not</em> here is any decision a
 * person has to make - which of several candidates, whether to overwrite a
 * hand edit - because those need a screen and this needs to be testable
 * without one.
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
        return provider.search(gameOf(context, entry, path));
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

        return Downloads.fetch(context, http, provider, path, scraped.media);
    }

    /** The scraped facts, keyed and owned. */
    static Meta owned(Meta from, String path, String providerName) {
        return new Meta(path, from.name, from.desc, from.developer, from.publisher,
                        from.genre, from.released, from.players, from.rating,
                        providerName);
    }

    /**
     * Whether this row already carries something a person typed.
     *
     * Asked before a scrape overwrites it. Rare, and the case where losing
     * work is most annoying, so it earns a confirmation rather than a silent
     * replacement - the hand editor shipped for exactly the corrections this
     * would discard.
     */
    public static boolean wouldOverwriteAHandEdit(Context context, String path) {
        Meta existing = Metadata.forPath(context, path);
        return existing != null && existing.isMine();
    }

    // --- turning a row into a question ------------------------------------------------

    private static Provider.Game gameOf(Context context, Entry entry, String path) {
        return new Provider.Game() {
            @Override public String path() { return path; }
            @Override public String filename() { return entry.name; }
            @Override public long size() { return entry.size; }

            @Override
            public String md5() {
                return md5Of(context, entry);
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
