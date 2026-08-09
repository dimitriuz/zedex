package dev.ldlab.zedex.library.scrape;

/**
 * How much asking is left today, as the last reply reported it.
 *
 * ScreenScraper puts all three of these in every single response, which is the
 * only reason a multi-scrape can pace itself instead of finding the limit by
 * hitting it. A scrape of eight hundred games against an allowance of a few
 * hundred is not a failure to handle at the end - it is something to say
 * before starting.
 */
public final class Quota {

    /** Requests made today, or -1 when the provider did not say. */
    public final int used;

    /** Allowed today, or -1. */
    public final int allowed;

    /** How many requests may be in flight at once, or 1 when unstated - the
     *  safe assumption, and what an account without a subscription gets. */
    public final int threads;

    public Quota(int used, int allowed, int threads) {
        this.used = used;
        this.allowed = allowed;
        this.threads = threads < 1 ? 1 : threads;
    }

    /** Unknown, which is what it is before anything has been asked. */
    public static Quota unknown() {
        return new Quota(-1, -1, 1);
    }

    /** How many more can be asked, or -1 when it cannot be worked out. */
    public int left() {
        if (used < 0 || allowed < 0) return -1;
        return Math.max(0, allowed - used);
    }

    /** Whether there is definitely nothing left - false when unknown, since
     *  refusing to try on a guess is worse than one refused request. */
    public boolean spent() {
        return left() == 0;
    }
}
