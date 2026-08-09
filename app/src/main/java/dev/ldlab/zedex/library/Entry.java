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

    /**
     * Whether this row is a folder or a zip to walk into, rather than a game
     * to select.
     *
     * Decided by {@link #inside} rather than by {@link #kind} alone: {@link
     * Favorites#all} hands back {@link Kind#ARCHIVE} for a favourite that
     * merely <em>lives</em> inside a zip - it has nothing else to call an
     * entry it cannot enter - but such an entry always carries its path
     * within the archive, and a real container never does.
     *
     * Here rather than on whoever is showing the row, which is what it used
     * to be: the list decides what a tap does with it, the pane decides what
     * its own button says, and both were asking the same question of the same
     * object. Nothing about the answer depends on which of them is asking, or
     * on which tab the row came from - checking {@code inside} makes it true
     * everywhere at once rather than everywhere a tab happens to agree.
     */
    public boolean isContainer() {
        return inside == null && (kind == Kind.FOLDER || kind == Kind.ARCHIVE);
    }
}
