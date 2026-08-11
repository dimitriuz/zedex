package dev.ldlab.zedex.library.scrape;

import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.ScreenPicture;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Media onto disk, and the checking that makes it safe to keep.
 *
 * The same work whichever provider named the file, which is why it is here
 * rather than in {@link ScreenScraper}: deciding what a game is called differs
 * completely between the three services, and downloading a picture does not
 * differ at all.
 *
 * <b>A download that arrived wrong is deleted, not kept.</b> ScreenScraper
 * gives an MD5 for each medium, and a truncated cover that stays on disk is
 * indistinguishable from a real one for ever after - {@code Artwork} tests
 * length and readability, both of which a half a picture passes. This is the
 * same lesson as the updater's checksum, and the same reason: the cost of
 * getting it wrong is silent and permanent, and the check is nearly free.
 */
public final class Downloads {

    private static final String TAG = "Zedex";

    private Downloads() {
    }

    /**
     * What one game's media came to.
     *
     * Counted rather than thrown, because a scrape that got the cover and
     * missed the video has still largely worked, and a multi-scrape needs to
     * carry on rather than stop at the first missing manual.
     */
    public static final class Result {
        public final int saved;
        public final int failed;

        Result(int saved, int failed) {
            this.saved = saved;
            this.failed = failed;
        }

        public boolean anything() {
            return saved > 0;
        }
    }

    /**
     * Where one game's media should be written.
     *
     * Here rather than assumed, because a one-game scrape fetches from every
     * source before anybody chooses between them: those files must not land on
     * top of what is already on disk on the way past. A sweep passes {@link
     * #into} and writes straight to the media folder, since it only ever asks
     * for folders that are empty and so has nothing to protect.
     */
    public interface Destination {
        File fileFor(String folder, String extension);
    }

    /** The media folder itself - what a scrape that has nothing to choose
     *  between writes to. */
    public static Destination into(Context context, String relativePath) {
        return (folder, extension) -> Artwork.fileFor(context, relativePath, folder, extension);
    }

    /** The staging area, for media that will be chosen between before any of
     *  them is kept. */
    public static Destination staging(Context context, String relativePath) {
        return (folder, extension) ->
                Artwork.stagingFileFor(context, relativePath, folder, extension);
    }

    /**
     * Fetches every medium for one game into {@code destination}.
     *
     * <b>The caller forgets the game from {@code Artwork}'s caches, not this
     * method.</b> A miss is cached, so a cover just written stays invisible
     * until something clears it - but a staged file is not where anything
     * looks, and forgetting on its account would throw away the lookups for
     * nothing. {@code Scrape.apply} and {@code Blend.commit} each do it at the
     * point the files actually become visible.
     *
     * @param relativePath the game's own key, {@code ./folder/Game.tap}
     */
    public static Result fetch(Context context, Http http, Provider provider,
                               String relativePath, List<Medium> media,
                               Destination destination)
            throws ScrapeException {
        int saved = 0;
        int failed = 0;

        for (Medium medium : media) {
            try {
                if (save(context, http, relativePath, medium, destination)) saved++;
                else failed++;
            } catch (Http.Refused refused) {
                stopIfHopeless(provider, refused);
                failed++;
            }
        }

        return new Result(saved, failed);
    }

    /**
     * Whether a refusal is one to carry on past.
     *
     * A picture the service does not have is one missing cover. A spent quota
     * is every remaining game in a multi-scrape, and there is no point
     * discovering it eight hundred more times - <b>media come from the same
     * API as everything else</b>, so a cover can be refused for exactly the
     * reasons a search can. Only the provider knows which of its codes mean
     * which.
     */
    private static void stopIfHopeless(Provider provider, Http.Refused refused)
            throws ScrapeException {
        ScrapeException why = provider.refusalFor(refused.status);

        switch (why.kind) {
            case QUOTA_EXCEEDED:
            case BAD_CREDENTIALS:
            case CLOSED:
            case NOT_CONFIGURED:
                throw why;
            default:
                // A missing picture, a rate wobble, a server hiccup: the next
                // medium and the next game are still worth trying.
                break;
        }
    }

    /** Music arrives zipped, always - the archive keeps it that way. */
    private static boolean isZippedMusic(Medium medium) {
        return "music".equals(medium.folder) && "zip".equalsIgnoreCase(medium.extension);
    }

    /**
     * Takes the tune out of the zip it arrived in.
     *
     * One file out of possibly several: a zip may hold a tune per ripper or
     * per version, and there is nowhere to put a second - the media folder
     * holds one file per game per folder, the same as every picture. The
     * first is taken, which for these is the one named after the game.
     *
     * The zip goes either way. Unpacked it has served its purpose; unpacked
     * to nothing it was not what it claimed, and leaving it would have {@code
     * Artwork} resolving a file the music reader cannot read.
     */
    private static boolean unzip(Context context, String relativePath,
                                 String folder, File zip, Destination destination) {
        File into = destination.fileFor(folder, "ay");
        boolean taken = false;

        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (entry.isDirectory()
                        || !entry.getName().toLowerCase(java.util.Locale.US)
                                 .endsWith(".ay")) {
                    continue;
                }

                try (FileOutputStream out = new FileOutputStream(into)) {
                    byte[] buffer = new byte[8192];
                    for (int read; (read = in.read(buffer)) != -1; ) {
                        out.write(buffer, 0, read);
                    }
                }

                taken = true;
                break;
            }
        } catch (IOException e) {
            Log.w(TAG, "cannot unpack " + zip.getName(), e);
        }

        delete(zip);

        if (!taken) {
            Log.w(TAG, "no tune inside " + zip.getName());
            delete(into);
        }

        return taken;
    }

    /** The one medium that arrives in a format only a Spectrum understands. */
    private static boolean isScreenDump(Medium medium) {
        return "scr".equalsIgnoreCase(medium.extension);
    }

    /**
     * Turns the dump that was just fetched into the picture beside it.
     *
     * The {@code .scr} goes either way: converted, it has served its purpose
     * and a second file per game that nothing reads is clutter; unconvertable,
     * it is a truncated download or something that was never a screen, and
     * keeping it would leave {@code Artwork} resolving a file it cannot draw.
     */
    private static boolean convert(Context context, String relativePath,
                                   String folder, File dump, Destination destination) {
        // The folder is passed rather than read back off the file, which was
        // the first attempt and was wrong for every game in a subfolder: what
        // a downloaded file's parent directory is called is the game's own
        // folder - "titlescreens/GOTY 2021/Angels.scr" - and not the media
        // folder at all. It put the picture where nothing looks, for exactly
        // the collections that are organised.
        File into = destination.fileFor(folder, ScreenPicture.EXTENSION);

        boolean converted = ScreenPicture.convert(dump, into);
        delete(dump);

        if (!converted) {
            Log.w(TAG, "not a screen dump: " + dump.getName());
            delete(into);
        }

        return converted;
    }

    private static boolean save(Context context, Http http, String relativePath,
                                Medium medium, Destination destination) throws Http.Refused {
        File into = destination.fileFor(medium.folder, medium.extension);

        try {
            String got = http.save(medium.url, into);

            if (medium.md5 != null && !medium.md5.equalsIgnoreCase(got)) {
                Log.w(TAG, medium.folder + " for " + relativePath + " hashed " + got
                           + ", provider said " + medium.md5 + "; discarding it");
                delete(into);
                return false;
            }

            if (into.length() == 0) {
                // A 200 with nothing behind it. Keeping it would be a file
                // Artwork reads as absent anyway, and clutter that never
                // heals - fileFor would hand back the same name next time.
                delete(into);
                return false;
            }

            // A raw Spectrum screen becomes a picture here and nowhere else,
            // so nothing downstream ever meets one - see ScreenPicture.
            if (ScreenPicture.EXTENSION.equals(medium.extension)) return true;
            if (isScreenDump(medium)) {
                return convert(context, relativePath, medium.folder, into, destination);
            }

            if (isZippedMusic(medium)) {
                return unzip(context, relativePath, medium.folder, into, destination);
            }

            return true;
        } catch (Http.Refused refused) {
            // The status says whether the rest is worth attempting; the caller
            // decides, because only it knows the provider.
            delete(into);
            throw refused;
        } catch (IOException e) {
            // Never the URL in a log line: ScreenScraper's media URLs are API
            // calls with the credentials in the query - see Http.Refused.
            Log.w(TAG, "cannot fetch " + medium.folder + " for " + relativePath
                       + ": " + e.getMessage());
            delete(into);
            return false;
        }
    }

    /** A half-written file must not be left where a reader would take it for
     *  a whole one. */
    private static void delete(File file) {
        if (file.exists() && !file.delete()) Log.w(TAG, "cannot remove " + file);
    }
}
