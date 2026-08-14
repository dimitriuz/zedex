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
            // No file: this asks what the phrase means, not what to offer
            // somebody, and a file would narrow the answer to its own kind.
            if (Suggested.machines(phrase, null, ids).isEmpty()) missed.add(phrase);
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
            List<Integer> found = Suggested.machines(phrase, null, ids);
            if (found.isEmpty()) continue;

            int first = found.get(0);
            assertFalse(phrase + " maps to " + ids[first] + ", which another"
                        + " phrase already claimed - a shorter token is winning",
                        seen.contains(first));
            seen.add(first);
        }
    }

    /**
     * And every machine the <em>file</em> table names is one Fuse has.
     *
     * The same silent failure as above, reached the other way. A row that
     * says a TR-DOS disk runs on four machines and gets three of them past
     * {@code indexOf} offers three, says nothing about the fourth, and leaves
     * somebody looking for the machine their game wants. Counting is the
     * check: a wrong id is not an error anywhere, only an absence.
     *
     * Asked through {@code machines} rather than of the array, so this tests
     * the road the app actually takes to it - the extension is read from a
     * name, which is where {@code 48IRONS.TRD} went wrong once already.
     */
    @Test
    public void everyMachineThefileTableNamesIsOneFuseHas() {
        String[] ids = machineIds();

        List<String> wrong = new ArrayList<>();

        for (String[] row : Suggested.MACHINES_FOR_FILE) {
            int named = row.length - 1;
            int offered = Suggested.machines(null, "game." + row[0], ids).size();

            if (offered != named) {
                wrong.add(row[0] + " names " + named + " machines, Fuse has "
                          + offered + " of them: " + Arrays.toString(row));
            }
        }

        assertTrue("the file table names ids Fuse does not have: " + wrong
                   + "\n  Fuse's own ids: " + Arrays.toString(ids),
                   wrong.isEmpty());
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

    /**
     * Fuse has no joystick called the keyboard, and this is what says so.
     *
     * The pad's keyboard mode is this app's own idea - {@code
     * Controls.JOYSTICK_KEYBOARD}, a number past the end of Fuse's list - and
     * the setup dialog shipped without ever offering it because it went
     * looking for the name in this array. Nothing in it starts with
     * "keyboard", here or ever, which is also what makes {@code
     * Setup.KEYBOARD} safe to store beside Fuse's own names: the two sets
     * cannot collide.
     */
    @Test
    public void fuseHasNoJoystickCalledTheKeyboard() {
        String[] names = joystickNames();

        for (String name : names) {
            assertFalse("Fuse now offers " + name + ", so the app's own"
                        + " keyboard choice needs rethinking: "
                        + Arrays.toString(names),
                        name.toLowerCase(java.util.Locale.US).startsWith("keyboard"));
        }
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
