package dev.ldlab.zedex.library.scrape;

/**
 * Something that is not an answer.
 *
 * Typed rather than a message, because a multi-scrape has to tell them apart:
 * a spent quota and a thread limit are reasons to wait, refused credentials
 * and a closed service are reasons to stop and say so, and a game the provider
 * has never heard of is neither - it is an ordinary outcome and is not an
 * exception at all.
 */
public final class ScrapeException extends Exception {

    public enum Kind {
        /** No credentials, so nothing can be asked. Entry points hide rather
         *  than reaching this, but a stale screen can still get here. */
        NOT_CONFIGURED,

        /** The developer account or the user's own was refused. Stop; asking
         *  again will be refused identically. */
        BAD_CREDENTIALS,

        /** The day's allowance is gone. Wait, or come back tomorrow. */
        QUOTA_EXCEEDED,

        /** Too many requests at once for this account. Wait a moment; this one
         *  clears by itself and is the ordinary reason a fast loop stumbles. */
        THREAD_LIMIT,

        /** The service is closed to non-members, or down for everyone. */
        CLOSED,

        /** No network, a timeout, a socket that went away. */
        NETWORK,

        /** A reply arrived and could not be understood. */
        MALFORMED,
    }

    public final Kind kind;

    public ScrapeException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ScrapeException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /** Whether waiting could plausibly help - the one question a multi-scrape
     *  asks of every failure. */
    public boolean worthWaiting() {
        return kind == Kind.THREAD_LIMIT || kind == Kind.NETWORK;
    }
}
