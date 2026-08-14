package dev.ldlab.zedex.library.catalogue;

import java.util.List;

/**
 * Which version, and which file of it, one tap means.
 *
 * The choice is invisible from the outside: whatever this returns, something
 * loads and the game runs. A wrong order here is never reported as a bug - it
 * is a collection that quietly became snapshots - which is why the order is a
 * written-down constant and why {@code PickTest} mutation-checks it.
 */
public final class Pick {

    /**
     * Tape, then disk, then snapshot.
     *
     * Tape images first because they carry the loading scheme and the custom
     * loader that a snapshot has already thrown away, and half of what a
     * Spectrum game is remembered for happens during loading. Disk images
     * next, in the order the machines that read them appear. Snapshots last:
     * they always work and they always start after the part worth seeing.
     *
     * {@code gz} is deliberately absent - it is a wrapper rather than a
     * format. {@code rzx} is deliberately absent for a different reason: it is
     * somebody playing the game, not the game. See {@link #recording}.
     */
    public static final String[] PREFERENCE = {
        "tzx", "tap", "trd", "scl", "dsk", "mgt", "img", "udi", "szx", "z80", "sna",
    };

    /** What this app can play back but must never mistake for the program. */
    private static final String RECORDING = "rzx";

    private Pick() {
    }

    /**
     * The best file of the original release.
     *
     * The original is whichever the catalogue lists first, and nothing here
     * re-sorts them: their order is the catalogue's own statement about which
     * came first, and this app has no better source. A version with nothing
     * openable in it falls through to the next rather than failing the whole
     * entry - a release that is only a scan is an ordinary thing to find.
     *
     * @return null when nothing anywhere in the item can be opened, which is
     *         an answer: the screen says so rather than importing a file the
     *         emulator will refuse.
     */
    public static Catalogue.Download forGame(Catalogue.Item item) {
        if (item == null) return null;

        for (Catalogue.Version version : item.versions()) {
            Catalogue.Download found = forGame(version);
            if (found != null) return found;
        }

        return null;
    }

    /** The best file of one version, or null. */
    public static Catalogue.Download forGame(Catalogue.Version version) {
        if (version == null) return null;

        List<Catalogue.Download> files = version.files();

        for (String wanted : PREFERENCE) {
            for (Catalogue.Download file : files) {
                if (wanted.equals(file.format())) return file;
            }
        }

        return null;
    }

    /**
     * The recording, if the item has one.
     *
     * Looked for across every version rather than only the original: a
     * recording is of a playthrough, not of a release, and which version it
     * hangs off is an accident of how it was catalogued. 13.5% of ZXDB's
     * entries have one.
     */
    public static Catalogue.Download recording(Catalogue.Item item) {
        if (item == null) return null;

        for (Catalogue.Version version : item.versions()) {
            for (Catalogue.Download file : version.files()) {
                if (isRecording(file)) return file;
            }
        }

        return null;
    }

    public static boolean isRecording(Catalogue.Download file) {
        return file != null && RECORDING.equals(file.format());
    }

    /** What a scan or a cover arrives as, and what a book is not.
     *
     *  {@code scr} is here because a Spectrum screen dump is a picture: zxart's
     *  graphics entries carry the rendered PNG and the original .scr, and
     *  without this the .scr counted as "not a picture", won, and was handed to
     *  a phone that has nothing to open one with. */
    private static final String[] PICTURES = { "jpg", "jpeg", "png", "gif", "scr" };

    /**
     * The best file that is <em>not</em> for the machine - a book's PDF, a
     * magazine, a scanned inlay.
     *
     * A fifth of what this catalogue lists is not a program at all: 1,570
     * books, 1,819 electronic magazines, 1,147 hardware entries and the rest,
     * counted from the service's own {@code /metadata/}. Those used to end at
     * "Nothing here the Spectrum can open", which is true and was the whole
     * answer - the entry was on screen, the file was one tap away in the
     * record, and the app's reply was that it would not be fetching it. A
     * thing this app cannot run is still a thing somebody chose to open.
     *
     * The order is the catalogue's own, with one rule over it: a picture goes
     * last. A book's <em>cover</em> is a picture and the book is a PDF, both
     * are on the same entry, and answering with the cover would be answering
     * with the wrapper rather than the thing. An entry whose only file is a
     * picture - an advertisement, a photographed cassette - still answers with
     * it.
     *
     * Never a game and never a recording: those have their own two answers,
     * and a file this returns is by construction one neither of them would.
     *
     * @return null when the entry has no file at all, which is common - a row
     *         can exist for a title nobody has uploaded anything for
     */
    public static Catalogue.Download otherFile(Catalogue.Item item) {
        if (item == null) return null;

        Catalogue.Download picture = null;

        for (Catalogue.Version version : item.versions()) {
            for (Catalogue.Download file : version.files()) {
                if (file == null || file.url() == null || file.url().isEmpty()) continue;
                if (isRecording(file) || isForTheMachine(file)) continue;

                if (isPicture(file)) {
                    if (picture == null) picture = file;
                    continue;
                }

                return file;
            }
        }

        return picture;
    }

    /** Whether {@link #forGame} would have taken it - asked here rather than
     *  assumed from "forGame answered null", so this can be called on its own
     *  and still never offer the same file twice under two names. */
    private static boolean isForTheMachine(Catalogue.Download file) {
        for (String playable : PREFERENCE) {
            if (playable.equals(file.format())) return true;
        }
        return false;
    }

    private static boolean isPicture(Catalogue.Download file) {
        for (String picture : PICTURES) {
            if (picture.equals(file.format())) return true;
        }
        return false;
    }
}
