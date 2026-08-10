package dev.ldlab.zedex.library;

import dev.ldlab.zedex.storage.Storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
 * What somebody decided about one game, remembered.
 *
 * The point of this file existing at all is that it is <em>not</em> part of the
 * scraped record: a decision kept in {@code Meta} would be wiped by the next
 * sweep, silently, and the game would start asking again with nothing to
 * explain why. So the round trip is worth pinning down, and so is the fact
 * that a refusal is itself an answer.
 *
 * The bench's own file is moved aside and put back.
 */
@RunWith(AndroidJUnit4.class)
public class SetupTest {

    private static final String PATH = "./zedex-test/Head over Heels.tap";

    private Context context;
    private File file;
    private byte[] theirs;

    @Before
    public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();

        File root = Storage.root(context);
        assumeTrue("the data folder is not usable on this device: " + root,
                   root.isDirectory() && Storage.isWritable(root));

        file = new File(root, "setup.json");
        theirs = file.isFile() ? Files.readAllBytes(file.toPath()) : null;
        file.delete();
    }

    @After
    public void putItBack() throws IOException {
        if (file == null) return;

        if (theirs == null) {
            file.delete();
        } else {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(theirs);
            }
        }
    }

    /** Nobody has been asked, which is not the same as anybody having said no. */
    @Test
    public void nobodyAskedIsNull() {
        assertNull(Setup.remembered(context, PATH));
        assertNull(Setup.remembered(context, null));
    }

    @Test
    public void ananswerSurvivesBeingWrittenAndReadBack() {
        Setup.remember(context, PATH, new Setup.Answer(false, "128", "Kempston"));

        Setup.Answer back = Setup.remembered(context, PATH);

        assertFalse(back.skip);
        assertEquals("128", back.machine);
        assertEquals("Kempston", back.joystick);
        assertTrue(back.anything());
    }

    /**
     * A refusal is an answer, and the difference between declining once and
     * declining every time.
     *
     * It has to read back as something rather than as nothing, or the question
     * comes round again on the next launch - which is the whole thing somebody
     * ticking "remember" was trying to avoid.
     */
    @Test
    public void arefusalIsRememberedAsARefusal() {
        Setup.remember(context, PATH, new Setup.Answer(true, null, null));

        Setup.Answer back = Setup.remembered(context, PATH);

        assertTrue("a declined question read back as never asked", back.skip);
        assertFalse("there is nothing to apply", back.anything());
    }

    /** Half an answer is legitimate: a record can name a machine and no
     *  joystick, or the other way about. */
    @Test
    public void halfAnAnswerIsAnAnswer() {
        Setup.remember(context, PATH, new Setup.Answer(false, "pentagon", null));

        Setup.Answer back = Setup.remembered(context, PATH);

        assertEquals("pentagon", back.machine);
        assertNull(back.joystick);
        assertTrue(back.anything());
    }

    /** One game's answer is not another's. */
    @Test
    public void answersAreKeptPerGame() {
        Setup.remember(context, PATH, new Setup.Answer(false, "128", "Kempston"));
        Setup.remember(context, "./other.tap", new Setup.Answer(true, null, null));

        assertEquals("128", Setup.remembered(context, PATH).machine);
        assertTrue(Setup.remembered(context, "./other.tap").skip);
    }

    /** Answering again replaces the answer rather than adding to it. */
    @Test
    public void answeringAgainReplacesTheAnswer() {
        Setup.remember(context, PATH, new Setup.Answer(false, "48", "Cursor"));
        Setup.remember(context, PATH, new Setup.Answer(false, "128", "Kempston"));

        assertEquals("128", Setup.remembered(context, PATH).machine);
        assertEquals("Kempston", Setup.remembered(context, PATH).joystick);
    }

    /** And forgetting one makes it ask again. */
    @Test
    public void forgettingMakesItAskAgain() {
        Setup.remember(context, PATH, new Setup.Answer(true, null, null));
        Setup.forget(context, PATH);

        assertNull(Setup.remembered(context, PATH));
    }

    /**
     * Stored by name, not by index.
     *
     * The file is what survives an update, and an index into Fuse's machine
     * list only means anything against the build that wrote it: a machine
     * added upstream would silently repoint every remembered answer at its
     * neighbour, and nobody would ever connect the two.
     */
    @Test
    public void themachineIsStoredByNameSoAnUpdateCannotShiftIt() throws IOException {
        Setup.remember(context, PATH, new Setup.Answer(false, "pentagon512", "Sinclair 2"));

        String written = new String(Files.readAllBytes(file.toPath()),
                                    java.nio.charset.StandardCharsets.UTF_8);

        assertTrue("the machine was not written by name: " + written,
                   written.contains("pentagon512"));
        assertTrue(written.contains("Sinclair 2"));
    }

    /** A file that will not parse reads as nobody having decided anything,
     *  rather than as a crash on opening a game. */
    @Test
    public void arubbishFileIsReadAsNoDecisionsAtAll() throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("this is not json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        assertNull(Setup.remembered(context, PATH));

        // And it can still be written to afterwards.
        Setup.remember(context, PATH, new Setup.Answer(false, "128", null));
        assertEquals("128", Setup.remembered(context, PATH).machine);
    }
}
