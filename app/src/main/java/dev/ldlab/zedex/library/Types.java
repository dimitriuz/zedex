package dev.ldlab.zedex.library;

import java.util.Locale;

/**
 * What the emulator can open, in one place - kept apart from what ES-DE is
 * told, because the two are not the same question.
 *
 * ES-DE's own extension list mixes in two things that are its business, not
 * the emulator's: {@code .sh} is its convention for a shell-script launcher,
 * and {@code .7z} is there because ES-DE can unpack one before handing the
 * result over. Fuse opens neither. Showing them in the library as ordinary
 * rows would look like any other supported file and then fail the moment one
 * was tapped, which is exactly the "hide what we cannot open" rule this
 * package exists to keep - see docs/LIBRARY.md.
 *
 * So there are three questions, not one: {@link #openable} is what the
 * emulator can load, {@link #archive} is what can be walked into instead of
 * loaded, and {@link #supported} - openable or archive - is what the library
 * shows. ES-DE's own list, {@link #forEsDe()}, is openable plus {@code .zip}
 * plus the two extras it needs for itself; it used to be the only list here,
 * and {@code EsDe.EXTENSIONS} still has to come out byte-identical to what it
 * always was, so it keeps its own order rather than being rebuilt from the
 * other two.
 */
public final class Types {

    /** What Fuse can actually load. {@code .gz} stays: libspectrum decompresses it itself. */
    private static final String[] OPENABLE = {
        "dsk", "gz", "img", "mgt", "rzx", "scl", "sna", "szx",
        "tap", "trd", "tzx", "udi", "z80",
    };

    /**
     * Exactly ES-DE's own historical list: {@link #OPENABLE} interleaved with
     * {@code .sh} in the position it has always held, plus {@code .7z} and
     * {@code .zip} at the end - the order that fixed the byte-identical
     * string in {@code EsDe.EXTENSIONS}. Not derived from the other two
     * arrays, because {@code .sh} sits in the middle of it and neither
     * {@link #openable} nor {@link #archive} would put it there.
     */
    private static final String[] FOR_ESDE = {
        "dsk", "gz", "img", "mgt", "rzx", "scl", "sh", "sna", "szx",
        "tap", "trd", "tzx", "udi", "z80", "7z", "zip",
    };

    private Types() {
    }

    /**
     * The extensions ES-DE is told about, which is not the same list as what
     * the library shows: {@code .sh} and {@code .7z} are ES-DE's own business
     * - a launcher script and something it can unpack itself - and the
     * emulator opens neither.
     */
    public static String[] forEsDe() {
        return FOR_ESDE.clone();
    }

    /** Whether the emulator can load a file directly, judged by its extension. */
    public static boolean openable(String name) {
        String extension = extension(name);
        if (extension.isEmpty()) return false;

        for (String candidate : OPENABLE) {
            if (candidate.equals(extension)) return true;
        }
        return false;
    }

    /** Whether a name is a {@code .zip} - the only archive entered, for now. */
    public static boolean archive(String name) {
        return "zip".equals(extension(name));
    }

    /**
     * Whether the library should show a file at all: something the emulator
     * can load, or something it can be walked into instead.
     */
    public static boolean supported(String name) {
        return openable(name) || archive(name);
    }

    /** The name's extension, lower case and without the dot, or "" if it has none. */
    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
