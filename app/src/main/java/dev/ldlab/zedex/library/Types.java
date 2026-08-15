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
 * So there are four questions, not one: {@link #openable} is what the
 * emulator can load, {@link #archive} is what can be walked into instead of
 * loaded, {@link #external} is a music or screenshot import the emulator was
 * never going to open and another app is handed instead, and
 * {@link #supported} - openable, archive or external - is what the library
 * shows. ES-DE's own list, {@link #forEsDe()}, is openable plus {@code .zip}
 * plus the two extras it needs for itself; it used to be the only list here,
 * and {@code EsDe.EXTENSIONS} still has to come out byte-identical to what it
 * always was, so it keeps its own order rather than being rebuilt from the
 * other two. {@link #external} plays no part in it either - ES-DE launches
 * emulators, and has its own media folders for exactly this already.
 */
public final class Types {

    /** What Fuse can actually load. {@code .gz} stays: libspectrum decompresses it itself. */
    private static final String[] OPENABLE = {
        "dsk", "gz", "img", "mgt", "rzx", "scl", "sna", "szx",
        "tap", "trd", "tzx", "udi", "z80",
    };

    /**
     * A tune or a screenshot the catalogue imported - real files under
     * {@code Downloaded/Music} and {@code Downloaded/Graphics}, and until now
     * invisible to the library and unreachable once written: {@code
     * Pick.otherFile} is what actually chooses a Music or Graphics item's
     * download, and it hands back exactly these formats - {@code ogg} for a
     * tune (the rendered file {@code ZxartCatalogue}/{@code ZxInfoCatalogue}
     * both add first, before a tracker module that would never be reached),
     * {@code png}/{@code jpg}/{@code jpeg}/{@code gif} for a screenshot (
     * {@code Pick}'s own {@code PICTURES}, minus {@code scr} - a raw memory
     * dump nothing on Android has a viewer for, so listing one would show a
     * row that can only fail).
     *
     * The emulator was never asked to load any of these - they are not in
     * {@link #OPENABLE} - so a row for one hands the file to whatever else
     * the phone has, via {@code image/*}/{@code audio/*}, both already
     * declared in {@code <queries>} for the catalogue's own "Open" button.
     */
    private static final String[] EXTERNAL = { "ogg", "png", "jpg", "jpeg", "gif" };

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
    /**
     * The same list, for a screen that has to <em>offer</em> the formats
     * rather than test one.
     *
     * A copy, because {@link #OPENABLE} is the answer to "can this app open
     * it" and a caller that could reorder or shorten it would be editing that
     * answer. The catalogue's own format filter is built from this rather than
     * from a list of its own: a second list is a second thing to keep in step,
     * and the one that drifted would offer a filter whose every result the app
     * then refused to open.
     */
    public static String[] openable() {
        return OPENABLE.clone();
    }

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

    /** Whether a name is a music or screenshot import - see {@link #EXTERNAL}
     *  for which formats and why. */
    public static boolean external(String name) {
        String extension = extension(name);
        if (extension.isEmpty()) return false;

        for (String candidate : EXTERNAL) {
            if (candidate.equals(extension)) return true;
        }
        return false;
    }

    /**
     * Whether the library should show a file at all: something the emulator
     * can load, something it can be walked into instead, or something it
     * hands to another app entirely.
     */
    public static boolean supported(String name) {
        return openable(name) || archive(name) || external(name);
    }

    /** The name's extension, lower case and without the dot, or "" if it has none. */
    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
