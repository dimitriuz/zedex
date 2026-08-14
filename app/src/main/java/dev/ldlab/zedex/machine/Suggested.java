package dev.ldlab.zedex.machine;

import dev.ldlab.zedex.input.Controls;
import dev.ldlab.zedex.input.Keymap;
import dev.ldlab.zedex.library.meta.Meta;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a scraped record suggests about how to run a game, in this app's own
 * terms.
 *
 * The translation between two vocabularies, and nothing else: ZXInfo says
 * {@code ZX-Spectrum 48K/128K} and {@code Interface 2 (right)}, Fuse offers
 * fourteen machines and nine joystick interfaces, and something has to decide
 * which of theirs means which of ours. No screen in it and no Fuse in it - the
 * lists are passed in - so every mapping below is testable without a device.
 *
 * <b>It suggests and does not decide.</b> Half of what ZXDB records names two
 * machines at once, because plenty of games genuinely run on either, and a
 * game listing three joysticks means it will listen to all three. Choosing is
 * a person's job; this only works out what the choices are. See {@code SetupUi}
 * for the asking.
 *
 * Matching is by substring against a lower-cased string, deliberately loosely.
 * The full set of values ZXDB uses is not published anywhere this app can read
 * - {@code /metadata/} would answer it, and the service has been unreachable -
 * so an exact table would silently miss whatever it had not been told about.
 * A value nothing matches yields no suggestion, which is the right failure:
 * nothing is offered rather than the wrong thing applied.
 */
public final class Suggested {

    private Suggested() {
    }

    // --- machines -------------------------------------------------------------------

    /**
     * ZXDB's words for a machine, and the Fuse ids each implies, most specific
     * first.
     *
     * Order matters and is the whole trick: {@code ZX-Spectrum 128 +2} holds
     * "128" as well as "+2", so the longer token has to be looked for first or
     * every +2 game would suggest a plain 128K.
     *
     * <b>Checked against the real vocabulary.</b> {@code GET /v3/metadata/}
     * answers with every value ZXDB uses and how many entries carry each;
     * {@code SuggestedTest} holds that list and asserts both directions of
     * this table against it. Before that call was possible this was written
     * from what one collection happened to contain.
     *
     * More than one id where ZXDB names more than one machine: it writes the
     * two Timex 2068s as a single "Tx2068", and Fuse has both, so both are
     * offered and the person decides - the same answer {@code 48K/128K} gets.
     */
    private static final String[][] MACHINES = {
        { "scorpion",      "scorpion" },
        { "pentagon 1024", "pentagon1024" },
        { "pentagon 512",  "pentagon512" },
        { "pentagon",      "pentagon" },
        { "tx2068",        "ts2068", "2068" },
        { "ts2068",        "ts2068" },
        { "tc2068",        "2068" },
        { "tc2048",        "2048" },
        { "+3e",           "plus3e" },
        { "+3",            "plus3" },
        { "+2a",           "plus2a" },
        { "+2b",           "plus2a" },
        { "+2",            "plus2" },
        { "128",           "128" },
        { "48",            "48" },
        { "16",            "16" },
    };

    /*
     * Two of Fuse's sixteen are deliberately absent: "se" is a hobbyist
     * machine ZXDB has no word for, and "48_ntsc" is a region rather than a
     * machine anybody's record names. Scorpion is here because ZXDB does name
     * it and because it is where a good deal of the .trd demoscene runs.
     *
     * The +2B is a late +2A rather than a +2 - the same ROM in the same
     * disk-less shell - so it is pointed at Fuse's plus2a.
     */

    /**
     * The computers this can only get wrong, whatever else the value says.
     *
     * <b>A ZX81 is not a 16K Spectrum.</b> "ZX81 16K" contains "16", and the
     * matching below is by substring, so this table used to offer a 16K
     * Spectrum for the 3,096 entries ZXDB records that way - more than every
     * Pentagon, Timex and Scorpion entry in the database put together. A wrong
     * machine is worse than none: nothing offered reads as the database not
     * knowing, and a machine offered reads as the database saying so.
     *
     * Only these two need naming. Every other computer ZXDB lists - the QL,
     * the SAM Coupé, the Next, the ACE, the Z88, the Russian clones Fuse does
     * not emulate - contains no token this table looks for, so it is refused
     * by finding nothing, which is the same answer arrived at for free.
     */
    private static final String[] NOT_A_SPECTRUM = { "zx81", "zx80" };

    /**
     * What a file's own kind says about the machine, where it says anything.
     *
     * <b>Two of the formats Fuse opens pick a machine whether or not it was
     * asked to.</b> {@code utils.c} answers a {@code CLASS_DISK_TRDOS} on a
     * machine without {@code CAPABILITY_TRDOS_DISK} by selecting a Scorpion
     * itself, and a {@code CLASS_DISK_PLUS3} on one without
     * {@code CAPABILITY_PLUS3_DISK} by selecting a +3. So for those two, a
     * suggestion the file cannot honour is not merely unhelpful - it is one
     * the emulator overrules on every open, and an app that keeps re-applying
     * it never stops. See {@code SuggestedTest}.
     *
     * The ids on the right are exactly the machines libspectrum gives the
     * matching capability ({@code libspectrum.c}), and nothing else: the +2A
     * has +3 <em>memory</em> but no drive, so it is not among the second row.
     * Pentagon leads the first because that is what most of the .trd world
     * was written for, where Scorpion is merely what Fuse falls back to.
     *
     * Every other format is absent on purpose. Tapes and snapshots load on
     * anything, and the remaining disks Fuse hands to whichever interface is
     * already plugged in rather than choosing a machine - so there is no
     * disagreement for this table to settle, and a row for one could only
     * ever narrow a choice that was already right.
     *
     * Public so {@code SuggestedContractTest} can hold it up against the ids
     * Fuse really has, for the same reason {@link #INPUT_WORDS} is: an id
     * Fuse does not use is dropped here without a word, and a row that
     * quietly lost a machine looks exactly like one that never named it.
     */
    public static final String[][] MACHINES_FOR_FILE = {
        { "trd", "pentagon", "pentagon512", "pentagon1024", "scorpion" },
        { "scl", "pentagon", "pentagon512", "pentagon1024", "scorpion" },
        { "dsk", "plus3", "plus3e" },
    };

    /**
     * Every machine the record implies, as indices into {@code ids}.
     *
     * <b>The slash is the disjunction, and everything else is one machine
     * described at length.</b> {@code 48K/128K} is how ZXDB says "either", and
     * it is the commonest value in the database; {@code 128K +2} is a single
     * machine that happens to have "128" in its name. Splitting on the slash
     * first and taking the most specific token within each part tells those
     * two apart, where scanning the whole string for every token cannot -
     * that returns 128 <em>and</em> +2 for the second, offering a choice
     * between one machine and itself.
     *
     * In the record's own order, so {@code 48K/128K} suggests the 48K first:
     * whatever else it runs on, that is what it was written for.
     *
     * <b>The file is asked as well as the record, and where they disagree the
     * file wins.</b> They are answering different questions - the record says
     * which machines the <em>game</em> was released for, the file says which
     * can <em>read</em> it - and a record naming 48K for a game whose TR-DOS
     * disk is what got downloaded is not wrong, merely about another release.
     * Narrowing to the machines that can read it is the only answer the
     * emulator will keep; see {@link #MACHINES_FOR_FILE}.
     *
     * @param file the name or path the game is loaded from, or null when
     *             there is not one to hand - the record then answers alone,
     *             which is right for asking what a phrase means and wrong for
     *             offering somebody a machine
     * @param ids {@code FuseNative.machineIds()}, so this needs no Fuse
     */
    public static List<Integer> machines(String machineType, String file, String[] ids) {
        List<Integer> found = new ArrayList<>();
        if (ids == null) return found;

        // The whole value, not the part: "ZX81 16K/32K" says ZX81 once and
        // then splits, so a part of it read alone is "32k" with nothing left
        // to say which computer that is. Before the file is consulted, too - a
        // ZX81 entry carrying a .trd is a mis-filed record, not a reason to
        // offer a Pentagon.
        if (machineType != null) {
            for (String said : NOT_A_SPECTRUM) {
                if (machineType.toLowerCase(Locale.US).contains(said)) return found;
            }
        }

        Set<Integer> already = new LinkedHashSet<>();

        if (machineType != null) {
            for (String part : machineType.toLowerCase(Locale.US).split("/")) {
                mostSpecific(part, ids, already);
            }
        }

        Set<Integer> canRead = readableOn(file, ids);

        if (canRead != null) {
            // Narrow, and only fall back to the whole family when narrowing
            // leaves nothing: a record that already names a machine the file
            // can be read on is the better answer of the two, and replacing
            // "Pentagon 128" with all four would throw away the one thing
            // known about this game to offer a choice nobody asked for.
            already.retainAll(canRead);
            if (already.isEmpty()) already = canRead;
        }

        found.addAll(already);
        return found;
    }

    /**
     * The machines this file can be read on at all, or null when its kind
     * rules nothing out.
     *
     * Null and empty mean opposite things here and both happen: null is "the
     * file has no opinion", which leaves the record to answer, and an empty
     * set would be "nothing this build of Fuse has can read it", which leaves
     * nothing to offer. Hence a Set rather than a list, and hence the null.
     */
    private static Set<Integer> readableOn(String file, String[] ids) {
        String extension = extensionOf(file);
        if (extension == null) return null;

        for (String[] entry : MACHINES_FOR_FILE) {
            if (!entry[0].equals(extension)) continue;

            Set<Integer> machines = new LinkedHashSet<>();
            for (int at = 1; at < entry.length; at++) {
                int index = indexOf(ids, entry[at]);
                if (index >= 0) machines.add(index);
            }
            return machines;
        }

        return null;
    }

    /**
     * Whatever follows the last dot of the last path segment, lower-cased.
     *
     * Lower-cased because the archive's own spelling is whatever it is:
     * {@code 48IRONS.TRD} is how ZXDB writes it, and a table matched against
     * the quiet spelling alone would have missed the very file this was
     * written for. The last segment, because a folder may hold a dot and the
     * name after it may not.
     */
    private static String extensionOf(String file) {
        if (file == null) return null;

        String name = file.substring(file.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');

        return dot < 0 || dot == name.length() - 1 ? null
                : name.substring(dot + 1).toLowerCase(Locale.US);
    }

    /** The first token in {@link #MACHINES} this part contains, and every id
     *  it implies - the table is ordered most specific first, so "+2a" is
     *  found before "+2" and "+2" before "128". */
    private static void mostSpecific(String part, String[] ids, Set<Integer> into) {
        for (String[] entry : MACHINES) {
            if (!part.contains(entry[0])) continue;

            for (int at = 1; at < entry.length; at++) {
                int index = indexOf(ids, entry[at]);
                if (index >= 0) into.add(index);
            }
            return;
        }
    }

    // --- joysticks -------------------------------------------------------------------

    /**
     * ZXDB's words for an input, and the Fuse interface name each implies.
     *
     * <b>The Interface 2 pair is the one to check.</b> A Sinclair joystick 1
     * reads the 6-0 keys and a joystick 2 reads 1-5, and on the interface
     * itself the right socket is the first and the left socket the second - so
     * they are crossed here on purpose, and it is exactly the sort of thing
     * worth testing against a real game before trusting.
     */
    private static final String[][] INPUTS = {
        { "kempston",            "Kempston" },
        { "cursor",              "Cursor" },
        { "protek",              "Cursor" },
        { "interface 2 (right)", "Sinclair 1" },
        { "interface 2 (left)",  "Sinclair 2" },
        { "sinclair",            "Sinclair 1" },
        { "fuller",              "Fuller" },
        { "timex 2",             "Timex 2" },
        { "timex",               "Timex 1" },
    };

    /** The word ZXDB uses for "it reads the keyboard, and you choose which
     *  keys" - the one input that needs something else to be known before it
     *  can be offered. See {@link #keyboard}. */
    private static final String[] REDEFINEABLE = { "redefineable", "redefinable", "keyboard" };

    /**
     * Every joystick interface the game will listen to, as indices into
     * {@code names}.
     *
     * The keyboard is <em>not</em> among them even when the record says the
     * keys can be redefined, because "the profile's keys" is only a useful
     * answer when something knows which keys - see {@link #keyboard}.
     *
     * @param names {@code FuseNative.joystickTypeNames()}
     */
    public static List<Integer> joysticks(List<String> inputs, String[] names) {
        List<Integer> found = new ArrayList<>();
        if (inputs == null || names == null) return found;

        Set<Integer> already = new LinkedHashSet<>();

        for (String input : inputs) {
            if (input == null) continue;
            String said = input.toLowerCase(Locale.US);

            for (String[] pair : INPUTS) {
                if (!said.contains(pair[0])) continue;

                int index = indexOf(names, pair[1]);
                if (index >= 0) already.add(index);
                break;
            }
        }

        found.addAll(already);
        return found;
    }

    /**
     * Whether the keyboard is worth offering, which needs two things to be
     * true at once.
     *
     * The game has to accept redefineable keys - ZXInfo's fact - and something
     * has to know <em>which</em> keys, which is ScreenScraper's {@code
     * sp2kcfg} and lives in {@link Meta#keymap}. Offering "the profile's keys"
     * for a game nothing has a layout for would put the pad on whatever the
     * last game used, which is worse than not offering it.
     *
     * The two come from different services, which is the first place in this
     * app where scraping from both is worth more than scraping from either.
     *
     * <b>A layout that cannot be read is not a layout.</b> {@link Keymap} is
     * asked, not just whether the field is filled: p2k can name keypads,
     * function keys and a second player's pad, and a file made entirely of
     * those would otherwise offer the keyboard and then change nothing.
     */
    public static boolean keyboard(Meta meta) {
        if (meta == null || !Keymap.readable(meta.keymap)) return false;

        for (String input : meta.inputs) {
            if (input == null) continue;
            String said = input.toLowerCase(Locale.US);

            for (String word : REDEFINEABLE) {
                if (said.contains(word)) return true;
            }
        }

        return false;
    }

    /**
     * Every control the game can be offered, in the app's own terms.
     *
     * <b>Not indices into Fuse's list.</b> Fuse emulates eight interfaces and
     * the pad's keyboard mode is none of them - it is this app's own choice,
     * {@link Controls#JOYSTICK_KEYBOARD}, which sits after Fuse's list
     * wherever the user picks one (see {@code ControlsUi.joystickTypePage}).
     * Looking the keyboard up by name in Fuse's array is how the setup dialog
     * shipped without ever offering it: there is no such name and there never
     * will be. So the choices are worked out here as the same numbers the
     * joystick setting is written with, and one of them is simply not an
     * index.
     *
     * The keyboard comes last, when it comes at all: an interface the game was
     * written for is the better default, and the first choice is the one the
     * dialog arrives with selected.
     *
     * @param names {@code FuseNative.joystickTypeNames()}
     */
    public static List<Integer> controls(Meta meta, String[] names) {
        List<Integer> found = meta == null ? new ArrayList<>()
                                           : joysticks(meta.inputs, names);

        if (keyboard(meta)) found.add(Controls.JOYSTICK_KEYBOARD);

        return found;
    }

    // --- the words themselves, for somebody filling them in by hand --------------------

    /**
     * The machines ZXDB names that Fuse can be, in its own words.
     *
     * Suggestions and not a closed list: the edit screen offers these so
     * nobody has to guess how the database spells a Spectrum, and typing past
     * them still works, because the matching above is by substring and a
     * value this app has never seen is refused rather than mistaken.
     *
     * Every value here comes from {@code GET /v3/metadata/}, which answers
     * with the whole vocabulary - so these are the exact strings a scraped
     * record would carry, and a hand-filled game reads the same as a scraped
     * one everywhere downstream. Ordered as somebody would look for them
     * rather than by how many entries carry each.
     */
    public static final String[] MACHINE_WORDS = {
        "ZX-Spectrum 16K",
        "ZX-Spectrum 48K",
        "ZX-Spectrum 16K/48K",
        "ZX-Spectrum 48K/128K",
        "ZX-Spectrum 128K",
        "ZX-Spectrum 128 +2",
        "ZX-Spectrum 128 +2A/+3",
        "ZX-Spectrum 128 +2B",
        "ZX-Spectrum 128 +3",
        "Pentagon 128",
        "Scorpion",
        "Timex TC2048",
        "Timex Tx2068",
        "Timex TC2048/Tx2068",
    };

    /**
     * The controls a Spectrum game can be listed as reading.
     *
     * <b>Not verified against the service.</b> {@code /metadata/} answers with
     * the machine types, the genres and the features, and not with these -
     * so unlike {@link #MACHINE_WORDS} these are this app's spelling of what
     * ZXDB's own pages show. It costs little: they are suggestions in a box
     * somebody can type past, and every one of them is a phrase {@link
     * #INPUTS} matches, which is the part that has to be true.
     *
     * "Redefineable keys" is ZXDB's own spelling, and is the one that makes
     * the keyboard offerable at all - see {@link #keyboard}.
     */
    public static final String[] INPUT_WORDS = {
        "Kempston Joystick",
        "Cursor",
        "Protek",
        "Sinclair Joystick",
        "Interface 2 (left)",
        "Interface 2 (right)",
        "Fuller Joystick",
        "Timex 1 Joystick",
        "Timex 2 Joystick",
        "Redefineable keys",
    };

    /**
     * Whether there is anything at all worth asking about.
     *
     * The file is asked here for the same reason {@link #machines} asks it:
     * a TR-DOS disk names four machines and rules out twelve all by itself,
     * so a record that says nothing about the machine is still worth a
     * question when the file says that much.
     */
    public static boolean anything(Meta meta, String file, String[] machineIds,
                                   String[] joystickNames) {
        if (meta == null) return false;

        return !machines(meta.machine, file, machineIds).isEmpty()
                || !joysticks(meta.inputs, joystickNames).isEmpty()
                || keyboard(meta);
    }

    private static int indexOf(String[] values, String wanted) {
        for (int at = 0; at < values.length; at++) {
            if (wanted.equalsIgnoreCase(values[at])) return at;
        }
        return -1;
    }
}
