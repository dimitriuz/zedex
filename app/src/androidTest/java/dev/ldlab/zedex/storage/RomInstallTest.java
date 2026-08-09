package dev.ldlab.zedex.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * {@code installRoms} only ever adds, and what counts as "already there".
 *
 * Part of 11.4's number one. {@code Storage} is the largest class in the app
 * and CLAUDE.md records five separate shipped bugs in its problem space; most
 * of them are MediaProvider behaviour a device is the only witness to, which is
 * why this is instrumentation. Two rules in particular are worth pinning, and
 * both have been wrong in a shipped build:
 *
 *  - <b>{@code exists()} is not "already there".</b> A file left behind by an
 *    install that is gone exists and reports the right length and cannot be
 *    opened - MediaProvider drops ownership and a reinstall gets a new uid - and
 *    the old code read that as the user's own and skipped <em>all twenty-nine
 *    ROMs</em>. The test is {@code canRead() && length() > 0}, and a
 *    zero-length file is the half of it a test can actually produce.
 *  - <b>A ROM of the user's own with the same name is theirs, and stays.</b>
 *    The ROM folder is the one place the app invites somebody to put their own
 *    files, so "only ever adds" is not an implementation detail - it is the
 *    promise that makes the invitation safe.
 *
 * Every ROM this touches is one the app ships, so the worst this can do to a
 * bench is provoke {@code installRoms} into putting the shipped copy back -
 * which is what it is for. The originals are restored in {@link #putItBack}
 * regardless.
 */
@RunWith(AndroidJUnit4.class)
public class RomInstallTest {

    /** One the app ships, so it can always be recreated by the very method
     *  under test. */
    private static final String SHIPPED = "48.rom";

    private Context context;
    private File rom;

    /** What was there before, put back whatever happens. */
    private byte[] before;

    @Before
    public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();

        // The folder has to be usable at all, which on a bench without the
        // permission it may not be - and that is WritableTest's subject, not
        // this one's.
        File folder = Storage.romsDirectory(context);
        assumeTrue("the ROM folder is not usable on this device: " + folder,
                   folder.isDirectory() && Storage.isWritable(folder));

        rom = new File(folder, SHIPPED);

        assumeTrue(SHIPPED + " is not installed, so there is nothing to preserve",
                   rom.canRead() && rom.length() > 0);

        before = Files.readAllBytes(rom.toPath());
    }

    @After
    public void putItBack() throws IOException {
        if (before == null) return;

        try (FileOutputStream out = new FileOutputStream(rom)) {
            out.write(before);
        }
    }

    /**
     * A ROM the user replaced with their own is left exactly as it is.
     *
     * The promise the ROM folder is offered on. A shipped ROM written over
     * with something else - which is precisely what somebody swapping in their
     * own dump does - must survive every later start, and installRoms runs on
     * every start.
     */
    @Test
    public void aromTheUserReplacedIsNotOverwritten() throws IOException {
        byte[] theirs = "not the shipped ROM at all".getBytes();

        try (FileOutputStream out = new FileOutputStream(rom)) {
            out.write(theirs);
        }

        Storage.installRoms(context);

        assertArrayEquals("installRoms overwrote a ROM the user had put there",
                          theirs, Files.readAllBytes(rom.toPath()));
    }

    /** And a missing one comes back - "adds only" has to add. */
    @Test
    public void amissingRomIsPutBack() throws IOException {
        assertTrue("cannot remove " + rom, rom.delete());

        Storage.installRoms(context);

        assertTrue(SHIPPED + " was not restored", rom.canRead());
        assertEquals("what came back is not the shipped ROM",
                     before.length, rom.length());
        assertArrayEquals(before, Files.readAllBytes(rom.toPath()));
    }

    /**
     * A zero-length file is not "already there".
     *
     * The half of the {@code canRead() && length() > 0} rule that a test can
     * produce on purpose. The other half - a file that exists, has the right
     * length and cannot be opened - needs a second package to have written it
     * and then been uninstalled, which no test can arrange; this is the
     * condition that stands for it, and it is the one an interrupted first run
     * actually leaves behind.
     */
    @Test
    public void azeroLengthRomIsTreatedAsMissingAndRefilled() throws IOException {
        try (FileOutputStream out = new FileOutputStream(rom)) {
            // Truncated, which is what an install that died mid-copy leaves.
        }
        assertEquals("the file should be empty for this test to mean anything",
                     0, rom.length());

        Storage.installRoms(context);

        assertTrue("a zero-length ROM was taken for one already there",
                   rom.length() > 0);
        assertArrayEquals(before, Files.readAllBytes(rom.toPath()));
    }

    /** Running it twice changes nothing - it is called on every single start,
     *  so "adds only" has to be idempotent or the folder churns forever. */
    @Test
    public void installingTwiceChangesNothing() throws IOException {
        Storage.installRoms(context);
        long modified = rom.lastModified();
        byte[] after = Files.readAllBytes(rom.toPath());

        Storage.installRoms(context);

        assertArrayEquals("a second install rewrote a ROM that was already there",
                          after, Files.readAllBytes(rom.toPath()));
        assertEquals("a second install touched a ROM that was already there",
                     modified, rom.lastModified());
    }

    /** And the folder is left with ROMs in it, which is the question the
     *  machine actually asks before it will start. */
    @Test
    public void thefolderHasRomsAfterwards() {
        Storage.installRoms(context);

        assertTrue("haveRoms says there are none after installRoms",
                   Storage.haveRoms(context));
    }
}
