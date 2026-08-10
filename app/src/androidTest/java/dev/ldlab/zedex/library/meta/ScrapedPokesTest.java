package dev.ldlab.zedex.library.meta;

import dev.ldlab.zedex.cheats.PokeDatabase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * The cheats a scrape fetched, found again and read.
 *
 * The bundled database matches the md5 of the file that is loaded, which is
 * exact and knows about a third of a real collection. This is the other two
 * thirds: a {@code .pok} fetched for the game, found by the store's own key
 * rather than by a hash, and parsed by the same parser the database's own rows
 * go through.
 *
 * The fixture is a real file - Head over Heels', from the archive, trimmed to
 * three of its cheats - because what is being tested is that ZXDB's idea of
 * the format and this app's agree.
 */
@RunWith(AndroidJUnit4.class)
public class ScrapedPokesTest {

    private static final String GAME = "./zedex-test/Head over Heels.tap";

    /**
     * Verbatim from {@code /zxdb/sinclair/pokes/h/}, three cheats of eleven.
     *
     * The third is the one worth keeping: 256 is how the format says "ask the
     * player for a number", and it is the case a parser is most likely to
     * read as a byte and write as 0.
     */
    private static final String POK =
            "NImmunity\n"
            + "Z 8 41837 24 0\n"
            + "NSpringy Jumps\n"
            + "M 8 35333 0 0\n"
            + "Z 8 35334 0 0\n"
            + "NAll the items & ammo=153\n"
            + "M 8 41611 7 0\n"
            + "Z 8 41618 256 0\n"
            + "Y\n";

    private Context context;
    private File file;

    @Before
    public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();

        File root = dev.ldlab.zedex.storage.Storage.root(context);
        assumeTrue("the data folder is not usable on this device: " + root,
                   root.isDirectory()
                   && dev.ldlab.zedex.storage.Storage.isWritable(root));

        file = Artwork.fileFor(context, GAME, "pokes", "pok");

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(POK.getBytes(StandardCharsets.UTF_8));
        }
    }

    @After
    public void tidyUp() {
        if (file != null) file.delete();

        File mine = new File(dev.ldlab.zedex.storage.Storage.mediaDirectory(context),
                             "pokes/zedex-test");
        mine.delete();
    }

    /**
     * Written where a scrape puts it, found where the cheats page looks.
     *
     * The two halves are {@code fileFor} and {@code pokes}, and a scrape that
     * appears to do nothing is what it looks like when they disagree.
     */
    @Test
    public void awrittenPokFileIsFoundAgain() {
        assertNotNull("the cheats page cannot find what a scrape just wrote",
                      Artwork.pokes(context, GAME));

        assertNull("a game nothing was fetched for has none",
                   Artwork.pokes(context, "./zedex-test/Nothing Here.tap"));
        assertNull(Artwork.pokes(context, null));
    }

    /** An empty file is not cheats - the same rule every other fetched file
     *  follows, since a 200 with nothing behind it is a real answer. */
    @Test
    public void anemptyFileIsNotCheats() throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[0]);
        }

        assertNull(Artwork.pokes(context, GAME));
    }

    /**
     * And the archive's own format is the one the app already parses.
     *
     * The point of the whole feature: the parser was written for the bundled
     * database, and a file fetched from ZXDB goes through it unchanged.
     */
    @Test
    public void thearchivesFormatIsTheOneTheAppAlreadyReads() throws IOException {
        String text = new String(Files.readAllBytes(
                Artwork.pokes(context, GAME).toPath()), StandardCharsets.UTF_8);

        List<PokeDatabase.Trainer> trainers = PokeDatabase.parse(text);

        assertEquals(3, trainers.size());
        assertEquals("Immunity", trainers.get(0).name);
        assertEquals(1, trainers.get(0).pokes.size());
        assertEquals(41837, trainers.get(0).pokes.get(0).address);

        assertEquals("two pokes make one cheat", 2, trainers.get(1).pokes.size());

        assertTrue("the cheat that asks for a number was read as a plain byte",
                   trainers.get(2).asks());
    }
}
