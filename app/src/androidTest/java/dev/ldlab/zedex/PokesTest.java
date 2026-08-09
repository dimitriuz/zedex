package dev.ldlab.zedex;

import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.cheats.Pokes;
import dev.ldlab.zedex.screen.SettingsActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * The cheats, both halves: the database that ships with the app and the pokes
 * somebody types.
 *
 * The database is searched by name here rather than by fingerprint, because a
 * fingerprint needs a particular file and no test can rely on one being on the
 * device. The search is the same lookup either way — it reaches the same index
 * and returns the same games — and it exercises the part that was easy to get
 * wrong: a name is reduced before it is searched, since files are named the way
 * TOSEC names them and none of that apparatus is in a game's title.
 *
 * A poke of one's own is checked all the way to memory: it is written into a
 * running machine, and the machine is asked what is there.
 */
@RunWith(AndroidJUnit4.class)
public class PokesTest {

    /**
     * A game the collection certainly has, and enough of a name that the search
     * has to do some work: several games have "manic" in them.
     */
    private static final String SEARCHED = "manic";
    private static final String GAME = "Manic Miner";

    /**
     * Somewhere nothing will touch, and a value to find there.
     *
     * Above RAMTOP, which the reporter lowers to 32767 on its first line: BASIC
     * keeps its program, variables and workspace below it and never writes
     * above. The printer buffer at 23296 was the first choice and it is only
     * harmless on a 48K - on a 128 the editor uses it, and the byte was gone
     * before the next PEEK saw it.
     */
    private static final int ADDRESS = 32768;
    private static final int VALUE = 77;

    /** Fuse's own numbering, and the two the reporter uses. */
    private static final int GREEN = 4;
    private static final int RED = 2;

    /**
     * Asks what is at the address and says so in the border: green for the value
     * that was poked, red for anything else. Autostarted, so nothing is typed.
     */
    private static final TapeProgram REPORTER = new TapeProgram()
            .line(10, "CLEAR 32767")
            .line(20, "BORDER 1")
            .line(30, "IF PEEK " + ADDRESS + "=" + VALUE + " THEN BORDER 4")
            .line(40, "IF PEEK " + ADDRESS + "<>" + VALUE + " THEN BORDER 2")
            .line(50, "GO TO 30")
            .startingAt(10);

    private final Emulator emulator = new Emulator();

    @Before
    public void setUp() {
        emulator.useDataFolder();
        emulator.launch();
        assumeFalse("no ROMs in " + emulator.romFolder(), emulator.needsRoms());

        forgetStoredPokes();
    }

    @After
    public void tearDown() {
        forgetStoredPokes();
    }

    /**
     * The shipped database, by name.
     *
     * The count is not asserted exactly - the collection is somebody else's and
     * grows - only that a search for a word several games share finds more than
     * one of them, and that the game asked for is among them and has cheats
     * behind it.
     */
    @Test
    public void findsAGameInTheShippedDatabaseAndListsItsCheats() {
        emulator.menu("Pokes", "Search the cheat database");
        emulator.enterText(SEARCHED);
        emulator.tap("Search");

        assertTrue("no results for " + SEARCHED, emulator.isShowing("GAMES"));
        assertTrue("the results do not include " + GAME, emulator.isShowing(GAME));

        emulator.tap(GAME);

        assertTrue("no cheats listed for " + GAME,
                   emulator.isShowing("Infinite Lives")
                   || emulator.isShowing("Infinite"));
        emulator.closeMenu();
    }

    /**
     * A poke of one's own, from typing it to the byte being in memory.
     *
     * Stored rather than applied once, because storing is the half with a file
     * behind it and the row it makes is what gets pressed afterwards.
     */
    @Test
    public void aStoredPokeReachesTheMachinesMemory() {
        runTheReporter();

        assertEquals("the address should not hold the value yet",
                     RED, emulator.borderColour());

        emulator.menu("Pokes", "Add a poke");
        fillIn(0, "uitest");
        fillIn(1, String.valueOf(ADDRESS));
        fillIn(2, String.valueOf(VALUE));
        emulator.tap("Add a poke");

        List<Pokes.Poke> stored = Pokes.all(preferences());
        assertEquals("the poke was not stored", 1, stored.size());
        assertEquals("the wrong address was stored", ADDRESS, stored.get(0).address);
        assertEquals("the wrong value was stored", VALUE, stored.get(0).value);

        // The row is a thing to press: nothing is applied by being on the list.
        emulator.menu("Pokes", "uitest");
        emulator.idle(2 * Emulator.SECOND);

        assertEquals("the poke never reached memory",
                     GREEN, emulator.borderColour());
    }

    /** The bin on the row, which asks before it does anything. */
    @Test
    public void theBinForgetsAStoredPoke() {
        emulator.menu("Pokes", "Add a poke");
        fillIn(0, "uitest");
        fillIn(1, String.valueOf(ADDRESS));
        fillIn(2, String.valueOf(VALUE));
        emulator.tap("Add a poke");

        assertEquals(1, Pokes.all(preferences()).size());

        emulator.menu("Pokes");
        emulator.tap("Forget");                 // the trailing button's name
        emulator.tap("Forget");                 // and the page it opens

        assertTrue("the poke was not forgotten",
                   Pokes.all(preferences()).isEmpty());
    }

    // --- odds and ends -------------------------------------------------------

    /**
     * One of several fields on a page, by position.
     *
     * {@link Emulator#enterText} takes the only field there is, and these pages
     * have three - a name, an address and a value - so they are addressed by
     * index instead.
     */
    private void fillIn(int index, String text) {
        List<androidx.test.uiautomator.UiObject2> fields =
                emulator.device().findObjects(
                        androidx.test.uiautomator.By.clazz(
                                android.widget.EditText.class));

        assertTrue("expected at least " + (index + 1) + " fields, found "
                   + fields.size(), fields.size() > index);
        fields.get(index).setText(text);
    }

    private void runTheReporter() {
        emulator.menu("Machine", "Reset", "Reset");
        emulator.idle(Emulator.BOOT);

        java.io.File tape = new java.io.File(emulator.context().getCacheDir(),
                                             "pokes.tap");
        try {
            REPORTER.writeTo(tape, "pokes");
        } catch (java.io.IOException e) {
            throw new AssertionError("could not write " + tape, e);
        }

        // Straight into Fuse, which is what Emulator.open is for: the picker
        // cannot be driven and this is the same call the app makes.
        emulator.open(tape);
        emulator.idle(4 * Emulator.SECOND);
    }

    private SharedPreferences preferences() {
        return emulator.context().getSharedPreferences(
                Prefs.PREFS, Context.MODE_PRIVATE);
    }

    private void forgetStoredPokes() {
        preferences().edit().remove(Pokes.KEY_POKES).commit();
    }
}
