package dev.ldlab.zedex.machine;

import dev.ldlab.zedex.storage.Prefs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "A setting has to be applied as well as stored" - 11.4's number five, and a
 * rule CLAUDE.md states and nothing checked.
 *
 * {@code FuseSettings} is the answer to it: one table carrying both halves of
 * every setting Fuse holds - how to push it into a running machine, and how it
 * appears on the command line a cold start builds. The table exists because
 * the two used to be separate switches and had already drifted, and the
 * comment in {@code SettingsActivity.apply} names the casualty: "divmmc had a
 * case here and no argument there, so switching it on and then restarting came
 * up without it."
 *
 * So what is worth testing is not any one setting - it is that the table is
 * walked whole by both halves, and that nothing the app stores for itself
 * leaks into Fuse.
 *
 * Instrumentation, unavoidably: {@code FuseNative} does {@code
 * System.loadLibrary} in a static block, and every push in the table
 * references it, so the class cannot even be loaded on the JVM.
 *
 * A file of its own for the preferences, not the app's, so pushing defaults at
 * a running machine cannot leave somebody's bench on settings they did not
 * choose. The real ones are pushed back in {@link #putTheMachineBack}.
 */
@RunWith(AndroidJUnit4.class)
public class FuseSettingsTest {

    /** Somewhere to write that is nobody's actual settings. */
    private static final String SCRATCH = "fuse-settings-test";

    private Context context;
    private SharedPreferences scratch;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        scratch = context.getSharedPreferences(SCRATCH, Context.MODE_PRIVATE);
        scratch.edit().clear().commit();
    }

    @After
    public void putTheMachineBack() {
        scratch.edit().clear().commit();

        // Whatever this test pushed, the machine goes back to what is actually
        // stored - pushing a table of defaults at a running emulator is a real
        // change to somebody's bench otherwise.
        FuseSettings.pushAll(
                context.getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE));
    }

    // --- both halves walk the same table ---------------------------------------

    /**
     * Every setting in the table is one {@code push} recognises.
     *
     * The half that would break silently: a key in the table that push does
     * not answer to is a setting the screen writes, the row shows as changed,
     * and the machine never hears about until the next cold start.
     */
    @Test
    public void everySettingInTheTableIsPushed() {
        for (String key : FuseSettings.keys()) {
            assertTrue("push() does not recognise " + key + ", which is in its own table",
                       FuseSettings.push(scratch, key));
        }
    }

    /** The filter's own ten are answered too, as one group - they are not rows
     *  in the table, and a caller cannot tell the difference. */
    @Test
    public void thefilterKeysArePushedAsWell() {
        for (String key : new String[] {
                Prefs.KEY_FILTER, Prefs.KEY_FILTER_SHARPNESS, Prefs.KEY_FILTER_SCANLINE,
                Prefs.KEY_FILTER_CURVE, Prefs.KEY_FILTER_MASK, Prefs.KEY_FILTER_GLOW,
                Prefs.KEY_FILTER_BLEED, Prefs.KEY_FILTER_NOISE, Prefs.KEY_VIDEO }) {
            assertTrue("push() does not recognise the filter key " + key,
                       FuseSettings.push(scratch, key));
        }
    }

    /**
     * And nothing of the app's own is mistaken for Fuse's.
     *
     * "Unknown keys are not an error: most of what this app stores is its own
     * business - which screen to open on, whether the bar fades - and never
     * reaches the emulator at all." A false positive here is not harmless: it
     * would push some other setting every time an unrelated preference
     * changed.
     */
    @Test
    public void whatTheAppKeepsForItselfIsNotPushed() {
        for (String key : new String[] {
                "libraryView", "librarySort", "language", "secondScreen",
                "libraryNames", "libraryVideoAutoplay", "statesRoot", "updateCheck" }) {
            assertFalse("push() claimed " + key + ", which is the app's own and not Fuse's",
                        FuseSettings.push(scratch, key));
        }
    }

    // --- the command line the other half builds ------------------------------------

    /** There is one, and it is not empty - a cold start with no arguments is a
     *  machine running on Fuse's defaults rather than the user's. */
    @Test
    public void thecommandLineCarriesSomething() {
        assertTrue("appendArguments produced nothing at all",
                   commandLine(scratch).size() > 0);
    }

    /**
     * No option is written twice.
     *
     * Two settings claiming one option is a silent conflict - Fuse takes the
     * last, so one of the two rows in Settings would appear to do nothing, and
     * only after a restart.
     */
    @Test
    public void nooptionIsWrittenTwice() {
        Set<String> seen = new HashSet<>();

        for (String token : commandLine(scratch)) {
            if (!token.startsWith("--")) continue;

            // --thing and --no-thing are the same option written two ways, and
            // only one of them may appear.
            String option = token.startsWith("--no-") ? "--" + token.substring(5) : token;
            assertTrue("the command line writes " + option + " more than once",
                       seen.add(option));
        }
    }

    /** Every value is preceded by the option it belongs to - a stray bare
     *  token would be read by Fuse as a filename to load. */
    @Test
    public void everyValueFollowsAnOption() {
        List<String> line = commandLine(scratch);

        for (int at = 0; at < line.size(); at++) {
            if (line.get(at).startsWith("--")) continue;

            assertTrue("the command line begins with a bare value: " + line.get(at),
                       at > 0);
            assertTrue("\"" + line.get(at) + "\" does not follow an option - Fuse would "
                       + "read it as a file to load",
                       line.get(at - 1).startsWith("--"));
        }
    }

    /**
     * And the line actually follows what is stored.
     *
     * The link the whole table exists to keep: a preference written here has
     * to come out the other side. Tape sound, because its option is a plain
     * flag and its default is on, so both spellings are reachable from one
     * assertion each.
     */
    @Test
    public void whatIsStoredIsWhatTheCommandLineSays() {
        scratch.edit().putBoolean(Prefs.KEY_TAPE_SOUND, true).commit();
        assertTrue("--loading-sound is missing with tape sound on",
                   commandLine(scratch).contains("--loading-sound"));

        scratch.edit().putBoolean(Prefs.KEY_TAPE_SOUND, false).commit();
        List<String> off = commandLine(scratch);
        assertTrue("--no-loading-sound is missing with tape sound off",
                   off.contains("--no-loading-sound"));
        assertFalse("both spellings are on the command line at once",
                    off.contains("--loading-sound"));
    }

    /**
     * DivMMC is pushed and deliberately has no argument.
     *
     * The setting the drift comment names. It is the one entry whose
     * {@code arguments} is null - Fuse has no command-line option for it, so
     * it arrives by being pushed after the machine starts, which {@code
     * pushAll} does at every cold start. Both halves of that are asserted
     * here, because "no argument" is indistinguishable from "argument
     * forgotten" unless something says which it is.
     */
    @Test
    public void divmmcIsPushedRatherThanPassedOnTheCommandLine() {
        assertTrue("divmmc is not pushed, so turning it on would do nothing until "
                   + "a restart that also would not carry it",
                   FuseSettings.push(scratch, Prefs.KEY_DIVMMC));

        scratch.edit().putBoolean(Prefs.KEY_DIVMMC, true).commit();

        for (String token : commandLine(scratch)) {
            assertFalse("divmmc grew a command-line option; if Fuse has one now, the "
                        + "comment on Setting.arguments needs changing too",
                        token.contains("divmmc"));
        }
    }

    private static List<String> commandLine(SharedPreferences preferences) {
        List<String> out = new ArrayList<>();
        FuseSettings.appendArguments(preferences, out);
        return out;
    }
}
