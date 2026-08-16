package dev.ldlab.zedex.storage;

import dev.ldlab.zedex.FakePreferences;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The question the first run turns on.
 *
 * It used to be "is the preferences file empty", which LibraryActivity - the
 * launcher - falsifies in its own onCreate by writing libraryMigrated before
 * EmulatorActivity ever asks. So the folders question was never put on a
 * fresh install, silently, and the app settled where it kept things without
 * asking. Each case below is one of the states that made that possible.
 */
public class WelcomeGateTest {

    @Test
    public void aFreshInstallIsAsked() {
        assertTrue(Prefs.welcomeNeeded(false, new FakePreferences()));
    }

    /** The case the old test got wrong: the launcher has already written
     *  something by the time anyone asks, and that is not an answer. */
    @Test
    public void aFreshInstallIsAskedEvenAfterTheLauncherHasWritten() {
        assertTrue(Prefs.welcomeNeeded(false,
                new FakePreferences().with(Prefs.KEY_LIBRARY_MIGRATED, true)));
    }

    @Test
    public void anAnsweredInstallIsNotAskedAgain() {
        assertFalse(Prefs.welcomeNeeded(false,
                new FakePreferences().with(Storage.KEY_SETUP_DONE, true)));
    }

    /** Somebody who has been playing for a month is not interrogated because
     *  a version arrived with a wizard in it. */
    @Test
    public void anUpdatedInstallIsNeverAsked() {
        assertFalse(Prefs.welcomeNeeded(true, new FakePreferences()));
    }

    /** setupDone still wins over everything, so a hand-set flag settles it. */
    @Test
    public void anAnsweredUpdatedInstallIsNotAsked() {
        assertFalse(Prefs.welcomeNeeded(true,
                new FakePreferences().with(Storage.KEY_SETUP_DONE, true)));
    }
}
