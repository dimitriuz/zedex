package dev.ldlab.zedex.machine;

import dev.ldlab.zedex.library.meta.Meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Translating one database's words into this app's.
 *
 * On the JVM rather than a device, because none of it touches Android: the
 * machine and joystick lists are passed in, which is what makes every case
 * below cheap enough to write out in full.
 *
 * The arrays here stand in for what Fuse reports. That they still match what
 * Fuse really says is a different question, and a real one - a fixture cannot
 * notice the day an id is renamed. {@code SuggestedContractTest} asks Fuse
 * itself, on a device, and the two together are what make this trustworthy.
 */
public class SuggestedTest {

    /** Fuse's real list, read off a device rather than imagined - all sixteen,
     *  including the two nothing maps to. */
    private static final String[] MACHINE_IDS = {
        "16", "48", "48_ntsc", "128", "plus2", "plus2a", "plus3", "plus3e",
        "2048", "2068", "ts2068", "pentagon", "pentagon512", "pentagon1024",
        "scorpion", "se",
    };

    private static final String[] JOYSTICKS = {
        "None", "Cursor", "Kempston", "Sinclair 1", "Sinclair 2",
        "Timex 1", "Timex 2", "Fuller", "Keyboard — the profile's keys",
    };

    private static List<String> ids(List<Integer> indices) {
        return indices.stream().map(at -> MACHINE_IDS[at])
                .collect(java.util.stream.Collectors.toList());
    }

    private static List<String> sticks(List<Integer> indices) {
        return indices.stream().map(at -> JOYSTICKS[at])
                .collect(java.util.stream.Collectors.toList());
    }

    private static List<Integer> machines(String said) {
        return Suggested.machines(said, MACHINE_IDS);
    }

    // --- machines ---------------------------------------------------------------------

    @Test
    public void oneMachineNamedPlainlyIsOneSuggestion() {
        assertEquals(Collections.singletonList("48"), ids(machines("ZX-Spectrum 48K")));
        assertEquals(Collections.singletonList("128"), ids(machines("ZX-Spectrum 128K")));
        assertEquals(Collections.singletonList("16"), ids(machines("ZX-Spectrum 16K")));
    }

    /**
     * The slash means "either", and it is the commonest value in the database.
     *
     * In the record's own order, so the 48K comes first: whatever else a game
     * runs on, that is what it was written for.
     */
    @Test
    public void aslashMeansEitherAndBothAreOffered() {
        assertEquals(Arrays.asList("48", "128"), ids(machines("ZX-Spectrum 48K/128K")));
        assertEquals(Arrays.asList("128", "48"), ids(machines("ZX-Spectrum 128K/48K")));
    }

    /**
     * The case that broke the first attempt at this.
     *
     * "128K +2" is one machine described at length, not two. Scanning the
     * whole string for every token finds "128" as well as "+2" and offers a
     * choice between a machine and itself; splitting on the slash first and
     * taking the most specific token within each part does not.
     */
    @Test
    public void amachineDescribedAtLengthIsStillOneMachine() {
        assertEquals(Collections.singletonList("plus2"),
                     ids(machines("ZX-Spectrum 128K +2")));
        assertEquals(Collections.singletonList("plus3"),
                     ids(machines("ZX-Spectrum 128K +3")));
    }

    /** And the longer token wins inside a part, or every +2A would read as a
     *  +2 and every +3e as a +3. */
    @Test
    public void thelongerNameWinsOverTheShorterOneItContains() {
        assertEquals(Collections.singletonList("plus2a"), ids(machines("ZX-Spectrum +2A")));
        assertEquals(Collections.singletonList("plus3e"), ids(machines("ZX-Spectrum +3e")));
        assertEquals(Arrays.asList("plus2a", "plus3"), ids(machines("ZX-Spectrum +2A/+3")));
    }

    /** Scorpion is a machine in its own right, and where a good deal of the
     *  .trd demoscene runs. */
    @Test
    public void thescorpionIsRecognised() {
        assertEquals(Collections.singletonList("scorpion"),
                     ids(machines("Scorpion ZS 256")));
    }

    /** Pentagon is its own family, and the sizes are three machines. */
    @Test
    public void thepentagonsAreToldApart() {
        assertEquals(Collections.singletonList("pentagon"), ids(machines("Pentagon 128")));
        assertEquals(Collections.singletonList("pentagon512"), ids(machines("Pentagon 512")));
        assertEquals(Collections.singletonList("pentagon1024"), ids(machines("Pentagon 1024")));
    }

    /**
     * A value nothing matches suggests nothing, which is the right failure.
     *
     * The full list of what ZXDB writes here is not published anywhere this
     * app can read, so meeting an unknown one is expected rather than
     * exceptional - and offering nothing is better than offering a guess that
     * resets somebody's machine.
     */
    @Test
    public void anunknownMachineSuggestsNothingRatherThanGuessing() {
        assertTrue(machines("Sam Coupe").isEmpty());
        assertTrue(machines("").isEmpty());
        assertTrue(machines(null).isEmpty());
        assertTrue(Suggested.machines("ZX-Spectrum 48K", null).isEmpty());
    }

    /** The same machine twice is offered once. */
    @Test
    public void thesameMachineNamedTwiceIsOfferedOnce() {
        assertEquals(Collections.singletonList("48"), ids(machines("ZX-Spectrum 48K/48K")));
    }

    // --- joysticks --------------------------------------------------------------------

    private static List<Integer> joysticks(String... inputs) {
        return Suggested.joysticks(Arrays.asList(inputs), JOYSTICKS);
    }

    @Test
    public void thejoysticksAGameListensToAreOffered() {
        assertEquals(Arrays.asList("Kempston", "Cursor"),
                     sticks(joysticks("Kempston Joystick", "Cursor")));
        assertEquals(Collections.singletonList("Fuller"),
                     sticks(joysticks("Fuller Joystick")));
    }

    /**
     * Interface 2's two sockets are not the same joystick, and they are
     * crossed.
     *
     * A Sinclair joystick 1 reads the 6-0 keys and a joystick 2 reads 1-5;
     * on the interface the right socket is the first and the left the second.
     * Pinned down here because it is the one mapping in this class that is
     * easy to get backwards and impossible to notice without a real game.
     */
    @Test
    public void theinterfaceTwoSocketsAreCrossed() {
        assertEquals(Collections.singletonList("Sinclair 1"),
                     sticks(joysticks("Interface 2 (right)")));
        assertEquals(Collections.singletonList("Sinclair 2"),
                     sticks(joysticks("Interface 2 (left)")));
    }

    /** Protek is a cursor interface by another name, and plenty of records
     *  say so. */
    @Test
    public void protekIsCursorUnderAnotherName() {
        assertEquals(Collections.singletonList("Cursor"), sticks(joysticks("Protek")));
    }

    /** Two names for one interface are offered once. */
    @Test
    public void twoWaysOfSayingOneInterfaceAreOfferedOnce() {
        assertEquals(Collections.singletonList("Cursor"),
                     sticks(joysticks("Cursor", "Protek")));
    }

    @Test
    public void anunknownInputIsIgnoredAndTheRestSurvive() {
        assertEquals(Collections.singletonList("Kempston"),
                     sticks(joysticks("Rotronics Wafadrive", "Kempston Joystick")));
        assertTrue(Suggested.joysticks(null, JOYSTICKS).isEmpty());
    }

    // --- the keyboard, which needs both services --------------------------------------

    private static Meta game(String machine, String keymap, String... inputs) {
        return Meta.at("./g.tap")
                .machine(machine).keymap(keymap)
                .inputs(Arrays.asList(inputs))
                .build();
    }

    /**
     * The keyboard is offered only when two different services agree.
     *
     * ZXInfo says the game reads redefineable keys; ScreenScraper's sp2kcfg
     * says which keys. Offering "the profile's keys" without a layout would
     * leave the pad on whatever the last game used - worse than not offering
     * it - so this is the first place in the app where having scraped from
     * both is worth more than either alone.
     */
    @Test
    public void thekeyboardNeedsBothTheFactAndTheLayout() {
        assertTrue(Suggested.keyboard(
                game("ZX-Spectrum 48K", "0:left = o", "Redefineable keys")));

        assertFalse("no layout, so nothing to bind to",
                    Suggested.keyboard(game("ZX-Spectrum 48K", null, "Redefineable keys")));
        assertFalse("a layout, but the game does not read the keys",
                    Suggested.keyboard(game("ZX-Spectrum 48K", "0:left = o", "Kempston Joystick")));
        assertFalse(Suggested.keyboard(null));
    }

    // --- whether to ask at all ----------------------------------------------------------

    @Test
    public void thereIsNothingToAskWhenNothingIsKnown() {
        assertFalse(Suggested.anything(null, MACHINE_IDS, JOYSTICKS));
        assertFalse(Suggested.anything(Meta.at("./g.tap").name("G").build(),
                                       MACHINE_IDS, JOYSTICKS));
        assertFalse("a machine nothing recognises is nothing to offer",
                    Suggested.anything(game("Sam Coupe", null), MACHINE_IDS, JOYSTICKS));
    }

    @Test
    public void thereIsSomethingToAskWhenEitherHalfIsKnown() {
        assertTrue(Suggested.anything(game("ZX-Spectrum 128K", null),
                                      MACHINE_IDS, JOYSTICKS));
        assertTrue(Suggested.anything(game(null, null, "Kempston Joystick"),
                                      MACHINE_IDS, JOYSTICKS));
    }
}
