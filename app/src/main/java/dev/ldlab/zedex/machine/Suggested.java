package dev.ldlab.zedex.machine;

import dev.ldlab.zedex.input.Controls;
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
     * ZXDB's words for a machine, and the Fuse id each implies, most specific
     * first.
     *
     * Order matters and is the whole trick: {@code ZX-Spectrum 128K +2} holds
     * "128" as well as "+2", so the longer token has to be looked for first or
     * every +2 game would suggest a plain 128K.
     */
    private static final String[][] MACHINES = {
        { "scorpion",      "scorpion" },
        { "pentagon 1024", "pentagon1024" },
        { "pentagon 512",  "pentagon512" },
        { "pentagon",      "pentagon" },
        { "ts2068",        "ts2068" },
        { "tc2068",        "2068" },
        { "tc2048",        "2048" },
        { "+3e",           "plus3e" },
        { "+3",            "plus3" },
        { "+2a",           "plus2a" },
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
     */

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
     * @param ids {@code FuseNative.machineIds()}, so this needs no Fuse
     */
    public static List<Integer> machines(String machineType, String[] ids) {
        List<Integer> found = new ArrayList<>();
        if (machineType == null || ids == null) return found;

        Set<Integer> already = new LinkedHashSet<>();

        for (String part : machineType.toLowerCase(Locale.US).split("/")) {
            int index = mostSpecific(part, ids);
            if (index >= 0) already.add(index);
        }

        found.addAll(already);
        return found;
    }

    /** The first token in {@link #MACHINES} this part contains - the table is
     *  ordered most specific first, so "+2a" is found before "+2" and "+2"
     *  before "128". */
    private static int mostSpecific(String part, String[] ids) {
        for (String[] pair : MACHINES) {
            if (part.contains(pair[0])) return indexOf(ids, pair[1]);
        }
        return -1;
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
     */
    public static boolean keyboard(Meta meta) {
        if (meta == null || meta.keymap == null || meta.keymap.isEmpty()) return false;

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

    /** Whether there is anything at all worth asking about. */
    public static boolean anything(Meta meta, String[] machineIds, String[] joystickNames) {
        if (meta == null) return false;

        return !machines(meta.machine, machineIds).isEmpty()
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
