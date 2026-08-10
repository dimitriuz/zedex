package dev.ldlab.zedex;

import dev.ldlab.zedex.machine.Suggested;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * That {@code Suggested}'s tables still mean something to Fuse.
 *
 * {@code SuggestedTest} covers the logic on the JVM against arrays written out
 * by hand, which is fast and complete and cannot notice the one thing that
 * would break this in the field: Fuse renaming an id, or this app's table
 * having guessed one wrong in the first place. A mapping to {@code
 * "pentagon512"} is worth nothing if Fuse calls it something else, and the
 * failure is silent - the machine is simply never offered, which looks like
 * the database not knowing rather than like a bug.
 *
 * So this asks Fuse. It is the pair to that class rather than a duplicate of
 * it: nothing here tests what the mapping decides, only that what it decides
 * exists.
 */
@RunWith(AndroidJUnit4.class)
public class SuggestedContractTest {

    private final Emulator emulator = new Emulator();

    /**
     * Fuse has to be running before it can say what it offers.
     *
     * The first version of this class did not launch anything, so {@code
     * machineIds()} answered empty, every {@code assumeTrue} skipped, and the
     * class reported "OK (4 tests)" having asserted nothing at all. It was
     * caught by pointing one mapping at an id that does not exist and
     * watching the suite stay green.
     *
     * So the arrays are <b>asserted</b> below, never assumed. The only skip
     * left is the one that turns on a fact nothing about timing can change -
     * whether the ROMs are on the bench at all, without which Fuse cannot
     * start.
     */
    @Before
    public void startFuse() {
        emulator.launch();
        assumeFalse("no ROMs in " + emulator.romFolder(), emulator.needsRoms());
    }

    private static String[] machineIds() {
        String[] ids = FuseNative.machineIds();
        assertTrue("Fuse reported no machines, so nothing below tests anything",
                   ids != null && ids.length > 0);
        return ids;
    }

    private static String[] joystickNames() {
        String[] names = FuseNative.joystickTypeNames();
        assertTrue("Fuse reported no joystick types, so nothing below tests anything",
                   names != null && names.length > 0);
        return names;
    }

    /**
     * One phrase per machine this app claims to recognise, in ZXDB's own
     * style.
     *
     * Every one of these has to come back with something, and each names a
     * different Fuse machine - which together is the whole contract.
     */
    private static final String[] MACHINE_PHRASES = {
        "ZX-Spectrum 16K",
        "ZX-Spectrum 48K",
        "ZX-Spectrum 128K",
        "ZX-Spectrum 128K +2",
        "ZX-Spectrum +2A",
        "ZX-Spectrum 128K +3",
        "ZX-Spectrum +3e",
        "Timex TC2048",
        "Timex TC2068",
        "Timex TS2068",
        "Pentagon 128",
        "Pentagon 512",
        "Pentagon 1024",
        "Scorpion ZS 256",
    };

    private static final String[] INPUT_PHRASES = {
        "Kempston Joystick",
        "Cursor",
        "Protek",
        "Interface 2 (left)",
        "Interface 2 (right)",
        "Fuller Joystick",
        "Timex 1 Joystick",
        "Timex 2 Joystick",
    };

    /**
     * Every machine the table names is one Fuse has.
     *
     * The failure this exists for: a mapping to an id Fuse does not use
     * silently suggests nothing, and nothing looks exactly like a game whose
     * machine the database never recorded.
     */
    @Test
    public void everyMachineTheTableNamesIsOneFuseHas() {
        String[] ids = machineIds();

        List<String> missed = new ArrayList<>();

        for (String phrase : MACHINE_PHRASES) {
            if (Suggested.machines(phrase, ids).isEmpty()) missed.add(phrase);
        }

        assertTrue("Suggested maps these to ids Fuse does not have: " + missed
                   + "\n  Fuse's own ids: " + Arrays.toString(ids),
                   missed.isEmpty());
    }

    /** And no two of them land on the same machine, which would mean a token
     *  is being swallowed by a shorter one - the +2A-reads-as-+2 failure. */
    @Test
    public void theonePhrasePerMachineReallyNamesAdifferentMachineEachTime() {
        String[] ids = machineIds();

        List<Integer> seen = new ArrayList<>();

        for (String phrase : MACHINE_PHRASES) {
            List<Integer> found = Suggested.machines(phrase, ids);
            if (found.isEmpty()) continue;

            int first = found.get(0);
            assertFalse(phrase + " maps to " + ids[first] + ", which another"
                        + " phrase already claimed - a shorter token is winning",
                        seen.contains(first));
            seen.add(first);
        }
    }

    /** Every joystick the table names is one Fuse offers. */
    @Test
    public void everyJoystickTheTableNamesIsOneFuseOffers() {
        String[] names = joystickNames();

        List<String> missed = new ArrayList<>();

        for (String phrase : INPUT_PHRASES) {
            if (Suggested.joysticks(java.util.Collections.singletonList(phrase),
                                    names).isEmpty()) {
                missed.add(phrase);
            }
        }

        assertTrue("Suggested maps these to interfaces Fuse does not offer: " + missed
                   + "\n  Fuse's own: " + Arrays.toString(names),
                   missed.isEmpty());
    }

    /** And the two Interface 2 sockets really are two different joysticks -
     *  crossed on purpose, and worthless if both land on the same one. */
    @Test
    public void thetwoInterfaceTwoSocketsAreTwoDifferentJoysticks() {
        String[] names = joystickNames();

        List<Integer> left = Suggested.joysticks(
                java.util.Collections.singletonList("Interface 2 (left)"), names);
        List<Integer> right = Suggested.joysticks(
                java.util.Collections.singletonList("Interface 2 (right)"), names);

        assertFalse(left.isEmpty());
        assertFalse(right.isEmpty());
        assertFalse("both sockets map to " + names[left.get(0)],
                    left.get(0).equals(right.get(0)));
    }
}
