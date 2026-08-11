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
}
