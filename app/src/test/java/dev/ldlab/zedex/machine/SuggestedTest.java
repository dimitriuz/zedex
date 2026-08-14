package dev.ldlab.zedex.machine;

import dev.ldlab.zedex.library.meta.Meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
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

    /**
     * Fuse's real list, and all of it - eight, with <b>no keyboard among
     * them</b>.
     *
     * This fixture used to carry a ninth entry called "Keyboard — the
     * profile's keys", and inventing it is what let the dialog ship without a
     * keyboard option: the code looked the keyboard up by name in this list
     * and found it here and never on a device. The pad's keyboard mode is this
     * app's own idea ({@code Controls.JOYSTICK_KEYBOARD}), sitting after
     * Fuse's list rather than in it. {@code SuggestedContractTest} asserts the
     * absence against the real Fuse.
     */
    private static final String[] JOYSTICKS = {
        "None", "Cursor", "Kempston", "Sinclair 1", "Sinclair 2",
        "Timex 1", "Timex 2", "Fuller",
    };

    private static List<String> ids(List<Integer> indices) {
        return indices.stream().map(at -> MACHINE_IDS[at])
                .collect(java.util.stream.Collectors.toList());
    }

    private static List<String> sticks(List<Integer> indices) {
        return indices.stream().map(at -> JOYSTICKS[at])
                .collect(java.util.stream.Collectors.toList());
    }

    /** What the record alone suggests - the file named below where one
     *  matters, and null where the question is only about the vocabulary. */
    private static List<Integer> machines(String said) {
        return Suggested.machines(said, null, MACHINE_IDS);
    }

    private static List<Integer> machines(String said, String file) {
        return Suggested.machines(said, file, MACHINE_IDS);
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
        assertTrue(Suggested.machines("ZX-Spectrum 48K", null, null).isEmpty());
    }

    /** The same machine twice is offered once. */
    @Test
    public void thesameMachineNamedTwiceIsOfferedOnce() {
        assertEquals(Collections.singletonList("48"), ids(machines("ZX-Spectrum 48K/48K")));
    }

    // --- machines the file itself rules out --------------------------------------------

    /** Every machine Fuse gives a built-in TR-DOS, in the order this offers
     *  them - Pentagon first, because that is what most of the .trd world was
     *  written for. */
    private static final List<String> TRDOS_MACHINES =
            Arrays.asList("pentagon", "pentagon512", "pentagon1024", "scorpion");

    /**
     * The bug this section exists for.
     *
     * ZXDB says <em>48 Irons</em> is a "ZX-Spectrum 48K/128K" game, and it is
     * - there is a tape release. The file the catalogue hands over is a
     * TR-DOS disk, and no 48K or 128K can read one: Fuse's {@code utils.c}
     * answers a {@code CLASS_DISK_TRDOS} on a machine without
     * {@code CAPABILITY_TRDOS_DISK} by selecting a Scorpion itself, whatever
     * it was told. So a suggestion of 48K was one the emulator would overrule
     * on every single open - and the app, replaying the same remembered
     * answer each time, put it straight back. That is a loop with no end, and
     * it is what "the game restarts a million times" was.
     *
     * The record is not wrong; it is answering about the game. The file is a
     * harder constraint than the record, so where the two disagree the file
     * wins.
     */
    @Test
    public void atrdosDiskIsNeverOfferedAmachineThatCannotReadOne() {
        assertEquals(TRDOS_MACHINES,
                     ids(machines("ZX-Spectrum 48K/128K", "48IRONS.TRD")));
        assertEquals(TRDOS_MACHINES, ids(machines("ZX-Spectrum 48K", "x.trd")));
        assertEquals(TRDOS_MACHINES, ids(machines("ZX-Spectrum 128K", "x.scl")));
    }

    /**
     * The extension arrives in whichever case the archive wrote it.
     *
     * The file that found this was {@code 48IRONS.TRD}, shouted, straight
     * from ZXDB - so a match on the lower-cased spelling alone would have
     * fixed nothing for the very game it was written for.
     */
    @Test
    public void thefileTypeIsReadWhateverCaseItIsWrittenIn() {
        assertEquals(TRDOS_MACHINES, ids(machines("ZX-Spectrum 48K", "GAME.TRD")));
        assertEquals(TRDOS_MACHINES, ids(machines("ZX-Spectrum 48K", "Game.TrD")));
        assertEquals(TRDOS_MACHINES,
                     ids(machines("ZX-Spectrum 48K", "/a path/with dots.in it/x.trd")));
    }

    /**
     * Where the record already names a machine that can read the file, it
     * keeps its answer.
     *
     * The file only ever <em>narrows</em>. Replacing a record that says
     * "Pentagon 128" with all four TR-DOS machines would throw away the one
     * piece of knowledge worth having and offer a choice nobody asked for.
     */
    @Test
    public void arecordThatAlreadyAgreesWithTheFileIsLeftAlone() {
        assertEquals(Collections.singletonList("pentagon"),
                     ids(machines("Pentagon 128", "x.trd")));
        assertEquals(Collections.singletonList("scorpion"),
                     ids(machines("Scorpion ZS 256", "x.trd")));
        assertEquals(Arrays.asList("pentagon", "scorpion"),
                     ids(machines("Pentagon 128/Scorpion ZS 256", "x.trd")));
    }

    /**
     * A +3 disk is the same rule with a different capability.
     *
     * {@code CLASS_DISK_PLUS3} on a machine without
     * {@code CAPABILITY_PLUS3_DISK} makes Fuse select a +3 by itself, so a
     * .dsk suggested onto a 48K loops in exactly the way a .trd does. Only
     * the +3 and the +3e have that capability - the +2A has +3 <em>memory</em>
     * and no drive.
     */
    @Test
    public void aplus3DiskIsOnlyOfferedTheMachinesWithAdrive() {
        assertEquals(Arrays.asList("plus3", "plus3e"),
                     ids(machines("ZX-Spectrum 48K/128K", "x.dsk")));
        assertEquals(Collections.singletonList("plus3"),
                     ids(machines("ZX-Spectrum 128K +3", "x.dsk")));
    }

    /**
     * A file that rules nothing out changes nothing.
     *
     * Tapes and snapshots load on anything, and every other disk Fuse can
     * open it hands to whatever interface is already plugged in rather than
     * choosing a machine - so there is no disagreement to settle and the
     * record answers alone.
     */
    @Test
    public void afileThatRulesNothingOutLeavesTheRecordToAnswer() {
        assertEquals(Arrays.asList("48", "128"),
                     ids(machines("ZX-Spectrum 48K/128K", "x.tap")));
        assertEquals(Arrays.asList("48", "128"),
                     ids(machines("ZX-Spectrum 48K/128K", "x.tzx")));
        assertEquals(Arrays.asList("48", "128"),
                     ids(machines("ZX-Spectrum 48K/128K", "x.z80")));
        assertEquals(Arrays.asList("48", "128"),
                     ids(machines("ZX-Spectrum 48K/128K", "x.udi")));
        assertEquals(Arrays.asList("48", "128"),
                     ids(machines("ZX-Spectrum 48K/128K", "no extension at all")));
    }

    /**
     * And a file that rules machines out is worth answering even when the
     * record says nothing at all.
     *
     * This is the case that used to offer no choice whatever - an unknown or
     * missing {@code machinetype} meant an empty list and no dialog - while
     * the file alone was enough to name four machines and rule out twelve.
     */
    @Test
    public void thefileAloneIsEnoughToSuggestSomething() {
        assertEquals(TRDOS_MACHINES, ids(machines(null, "x.trd")));
        assertEquals(TRDOS_MACHINES, ids(machines("", "x.trd")));
        assertEquals(TRDOS_MACHINES, ids(machines("Sam Coupe", "x.trd")));
    }

    /**
     * ...but not for a computer this app does not emulate.
     *
     * A ZX81 entry carrying a .trd is a mis-filed record rather than
     * something to offer a Pentagon for, and the refusal is deliberate
     * everywhere else in this table.
     */
    @Test
    public void anotherComputerIsStillRefusedWhateverTheFileIs() {
        assertTrue(machines("ZX81 16K", "x.trd").isEmpty());
    }

    /** No machine list, no suggestion - the file cannot conjure one. */
    @Test
    public void thereIsNoSuggestionWithoutFusesOwnList() {
        assertTrue(Suggested.machines("ZX-Spectrum 48K", "x.trd", null).isEmpty());
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

    // --- against ZXDB's real vocabulary -------------------------------------------------

    /**
     * Every value ZXDB actually uses for {@code machinetype}, read from the
     * service rather than imagined.
     *
     * {@code GET /v3/metadata/} answers with the whole list and how many
     * entries carry each - fetched 2026-08-10, 32 values over 39,666 entries.
     * Until that call was possible the table in {@code Suggested} was written
     * from the handful of values seen in one collection, and it showed: it
     * offered a 16K Spectrum for every ZX81 program in the database, which is
     * the third commonest value there is.
     *
     * In descending order of how many entries carry each, so what matters most
     * is at the top. Anything ZXDB adds later is simply not here, and the
     * failure that causes is the right one: a machine nothing recognises is
     * not offered.
     */
    private static final String[] ZXDB_MACHINE_TYPES = {
        "ZX-Spectrum 48K",        // 24804
        "ZX-Spectrum 16K",        //  4137
        "ZX81 16K",               //  3096
        "ZX-Spectrum 128K",       //  2223
        "ZX-Spectrum 48K/128K",   //  1931
        "ZX-Spectrum 16K/48K",    //   556
        "ZX-Spectrum 128 +3",     //   482
        "Sinclair QL",            //   424
        "ZX81 1K",                //   420
        "SAM Coupé",              //   302
        "ZX-Spectrum Next",       //   280
        "Timex Tx2068",           //   211
        "Pentagon 128",           //   203
        "ZX81 2K",                //   178
        "ZX80",                   //   163
        "ATM",                    //    62
        "Jupiter ACE",            //    56
        "Lambda 8300",            //    22
        "ZX-Spectrum 128 +2A/+3", //    20
        "Timex TC2048",           //    18
        "ZX81 32K",               //    15
        "ZX-Spectrum 128 +2",     //    12
        "Cambridge Z88",          //    11
        "AT Computer System",     //    10
        "ZX-Evolution",           //     7
        "Timex TC2048/Tx2068",    //     5
        "ZX81 64K",               //     4
        "ZX-UNO",                 //     4
        "ZX-Spectrum 128 +2B",    //     4
        "Scorpion",               //     3
        "ZX81 16K/32K",           //     2
        "Baltic",                 //     1
    };

    /** The ones Fuse can be, and so the ones that have to come back with
     *  something. Everything else in the list above is a different computer. */
    private static final String[] FUSE_CAN_RUN = {
        "ZX-Spectrum 48K", "ZX-Spectrum 16K", "ZX-Spectrum 128K",
        "ZX-Spectrum 48K/128K", "ZX-Spectrum 16K/48K", "ZX-Spectrum 128 +3",
        "ZX-Spectrum 128 +2", "ZX-Spectrum 128 +2A/+3", "ZX-Spectrum 128 +2B",
        "Timex Tx2068", "Timex TC2048", "Timex TC2048/Tx2068",
        "Pentagon 128", "Scorpion",
    };

    @Test
    public void everyMachineZxdbNamesThatFuseHasIsSuggested() {
        for (String said : FUSE_CAN_RUN) {
            assertFalse(said + " is a machine Fuse has and nothing is offered for it",
                        machines(said).isEmpty());
        }
    }

    /**
     * <b>A ZX81 is not a 16K Spectrum.</b>
     *
     * "ZX81 16K" contains "16", and the table matched on substrings, so 3,096
     * entries - more than every Pentagon, Timex and Scorpion value in the
     * database put together - offered to run a ZX81 program on a Spectrum.
     * A wrong suggestion is worse than none: nothing offered reads as the
     * database not knowing, and a machine offered reads as the database
     * saying so.
     */
    @Test
    public void nomachineIsSuggestedForAcomputerFuseCannotBe() {
        List<String> others = new ArrayList<>(Arrays.asList(ZXDB_MACHINE_TYPES));
        others.removeAll(Arrays.asList(FUSE_CAN_RUN));

        for (String said : others) {
            assertTrue(said + " is not a Spectrum, and " + ids(machines(said))
                       + " was offered for it", machines(said).isEmpty());
        }
    }

    /** ZXDB writes the two Timex 2068s as one value, so both of Fuse's are
     *  offered and the person decides - the same answer 48K/128K gets. */
    @Test
    public void thetimexUmbrellaOffersBothOfFusesTwo() {
        assertEquals(Arrays.asList("ts2068", "2068"), ids(machines("Timex Tx2068")));
        assertEquals(Arrays.asList("2048", "ts2068", "2068"),
                     ids(machines("Timex TC2048/Tx2068")));
    }

    /** The +2B is a late +2A and not a +2: same ROM, same disk-less shell. */
    @Test
    public void theplusTwoBisAplusTwoA() {
        assertEquals(Collections.singletonList("plus2a"),
                     ids(machines("ZX-Spectrum 128 +2B")));
    }

    // --- the choices offered, which are not Fuse's list ---------------------------------

    /**
     * The keyboard is a choice the dialog can offer and Fuse has no name for.
     *
     * Fuse emulates eight interfaces and the pad's keyboard mode is none of
     * them - it is this app's, {@code Controls.JOYSTICK_KEYBOARD}, appended
     * after Fuse's list everywhere the user is asked to choose one. So the
     * choices are worked out here, in the same terms the rest of the app uses,
     * rather than as indices into an array that cannot express one of them.
     */
    @Test
    public void thekeyboardIsOfferedAlongsideTheInterfaces() {
        assertEquals(Arrays.asList(2, 1, dev.ldlab.zedex.input.Controls.JOYSTICK_KEYBOARD),
                     Suggested.controls(game("ZX-Spectrum 48K", "0:left = o",
                                             "Kempston Joystick", "Cursor",
                                             "Redefineable keys"),
                                        JOYSTICKS));
    }

    /** Last, because an interface the game was written for beats reading the
     *  keys, and the first choice is the one already selected. */
    @Test
    public void thekeyboardIsTheLastChoiceRatherThanTheFirst() {
        List<Integer> offered = Suggested.controls(
                game("ZX-Spectrum 48K", "0:left = o", "Kempston Joystick",
                     "Redefineable keys"), JOYSTICKS);

        assertEquals(dev.ldlab.zedex.input.Controls.JOYSTICK_KEYBOARD,
                     (int) offered.get(offered.size() - 1));
    }

    @Test
    public void nolayoutMeansTheInterfacesAloneAreOffered() {
        assertEquals(Collections.singletonList(2),
                     Suggested.controls(game("ZX-Spectrum 48K", null,
                                             "Kempston Joystick",
                                             "Redefineable keys"),
                                        JOYSTICKS));
    }

    /** A game that reads nothing but its own keys still has one thing to
     *  offer, and it is the whole reason the keyboard is a choice at all. */
    @Test
    public void thekeyboardCanBeTheOnlyChoice() {
        assertEquals(Collections.singletonList(
                             dev.ldlab.zedex.input.Controls.JOYSTICK_KEYBOARD),
                     Suggested.controls(game(null, "0:left = o",
                                             "Redefineable keys"), JOYSTICKS));
    }

    @Test
    public void nothingKnownIsNoChoices() {
        assertTrue(Suggested.controls(null, JOYSTICKS).isEmpty());
        assertTrue(Suggested.controls(game("ZX-Spectrum 48K", null), JOYSTICKS).isEmpty());
    }

    // --- whether to ask at all ----------------------------------------------------------

    @Test
    public void thereIsNothingToAskWhenNothingIsKnown() {
        assertFalse(Suggested.anything(null, "./g.tap", MACHINE_IDS, JOYSTICKS));
        assertFalse(Suggested.anything(Meta.at("./g.tap").name("G").build(),
                                       "./g.tap", MACHINE_IDS, JOYSTICKS));
        assertFalse("a machine nothing recognises is nothing to offer",
                    Suggested.anything(game("Sam Coupe", null), "./g.tap",
                                       MACHINE_IDS, JOYSTICKS));
    }

    @Test
    public void thereIsSomethingToAskWhenEitherHalfIsKnown() {
        assertTrue(Suggested.anything(game("ZX-Spectrum 128K", null), "./g.tap",
                                      MACHINE_IDS, JOYSTICKS));
        assertTrue(Suggested.anything(game(null, null, "Kempston Joystick"),
                                      "./g.tap", MACHINE_IDS, JOYSTICKS));
    }

    /**
     * And a file whose kind names machines is worth asking about on its own.
     *
     * The record here says nothing this app recognises - which is the normal
     * case for the Pentagon demoscene, most of what arrives as .trd - and
     * before the file was consulted that meant no question and no choice.
     */
    @Test
    public void thereIsSomethingToAskWhenOnlyTheFileSaysAnything() {
        assertTrue(Suggested.anything(game("Sam Coupe", null), "./x.trd",
                                      MACHINE_IDS, JOYSTICKS));
        assertTrue(Suggested.anything(Meta.at("./x.trd").name("X").build(),
                                      "./x.trd", MACHINE_IDS, JOYSTICKS));
    }
}
