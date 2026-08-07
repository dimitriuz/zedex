package dev.ldlab.zedex.library;

import android.net.Uri;

/**
 * One row the library can show: a folder to walk into, a zip to walk into, or
 * a file the emulator can open. Immutable - a listing hands these out as a
 * snapshot of a folder or an archive at the moment it was read, and nothing
 * about a row is meant to change under whoever is holding it.
 */
public final class Entry {

    /** What a row is, and so what happens when it is opened. */
    public enum Kind { FOLDER, ARCHIVE, FILE }

    public final Kind kind;

    /** The display name - what the row shows, not necessarily a full path. */
    public final String name;

    /**
     * The document this row is reached through. For a plain folder or file
     * this is the row's own document; for something found inside a zip it is
     * the archive's document, because a zip entry is not a document of its
     * own SAF can address.
     */
    public final Uri uri;

    /** The path within the archive named by {@link #uri}, or null outside one. */
    public final String inside;

    /** Size in bytes, or -1 when it could not be determined. */
    public final long size;

    /** Last modified time in epoch millis, or 0 when it could not be determined. */
    public final long modified;

    public Entry(Kind kind, String name, Uri uri, String inside, long size, long modified) {
        this.kind = kind;
        this.name = name;
        this.uri = uri;
        this.inside = inside;
        this.size = size;
        this.modified = modified;
    }

    /**
     * Stable identity for favourites and recents: the document's own uri, and
     * for an entry inside an archive, the path within it as well - two games
     * in the same zip must not collapse onto one key, which the archive's uri
     * alone would do.
     */
    public String key() {
        return inside == null ? uri.toString() : uri.toString() + "#" + inside;
    }
}
