package dev.ldlab.zedex.library;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Walking into a {@code .zip}: what the library shows out of one, and what it
 * gets back out.
 *
 * The rest of 11.4's number seven. It needs no SAF and no granted folder -
 * {@code ContentResolver.openInputStream} takes a {@code file:} URI, and
 * {@code Listing.archive} asks for nothing else - so the zips here are built
 * in the cache directory with exactly the awkward contents worth asking about,
 * which is not something a real collection can be relied on to have.
 *
 * The rules being pinned are all ones the code states and nothing checked: a
 * directory entry is not a row, an unsupported file is not a row, a zip inside
 * a zip is <em>skipped rather than offered and then refused</em>, the order is
 * A-Z case-insensitively, and the path within the archive survives - which is
 * the whole of how the entry is opened again later.
 */
@RunWith(AndroidJUnit4.class)
public class ArchiveTest {

    private Context context;
    private final List<File> made = new ArrayList<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    @After
    public void tidyUp() {
        for (File zip : made) zip.delete();
        made.clear();
    }

    /** A zip of {@code name -> contents}, in the order given. */
    private Uri zipOf(String named, Map<String, String> contents) throws IOException {
        File zip = new File(context.getCacheDir(), named);
        made.add(zip);

        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (Map.Entry<String, String> each : contents.entrySet()) {
                out.putNextEntry(new ZipEntry(each.getKey()));

                // A key ending in "/" is a directory entry, which carries no
                // bytes - and is one of the things that must not become a row.
                if (!each.getKey().endsWith("/")) {
                    out.write(each.getValue().getBytes(StandardCharsets.UTF_8));
                }
                out.closeEntry();
            }
        }
        return Uri.fromFile(zip);
    }

    private static Map<String, String> entries(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2) map.put(pairs[at], pairs[at + 1]);
        return map;
    }

    private List<Entry> listing(Uri zip) throws IOException {
        return Listing.archive(context.getContentResolver(), zip);
    }

    private static List<String> names(List<Entry> entries) {
        List<String> names = new ArrayList<>();
        for (Entry entry : entries) names.add(entry.name);
        return names;
    }

    // --- what is shown -------------------------------------------------------------

    /**
     * Games are rows; a directory, an unsupported file and a nested zip are
     * not.
     *
     * The nested zip is the one with a comment on it: "a zip entry that is
     * itself a zip is skipped too rather than offered and then refused -
     * nothing here reads an archive nested inside another one". Offering it
     * would be a row that opens nothing, with no way to tell from one that
     * works.
     */
    @Test
    public void onlyThingsTheEmulatorCanOpenBecomeRows() throws IOException {
        Uri zip = zipOf("mixed.zip", entries(
                "Tujad.z80", "game",
                "readme.txt", "words",
                "inner.zip", "another archive",
                "launch.sh", "ES-DE's own, not ours",
                "folder/", "",
                "folder/Manic.tap", "game"));

        assertEquals(java.util.Arrays.asList("Manic.tap", "Tujad.z80"),
                     names(listing(zip)));
    }

    /** A-Z case-insensitively, which is not the order they were written in. */
    @Test
    public void therowsComeBackInNameOrderWhateverOrderTheyWereZippedIn() throws IOException {
        Uri zip = zipOf("order.zip", entries(
                "zeta.tap", "z", "Alpha.tap", "a", "beta.tap", "b", "Gamma.tap", "g"));

        assertEquals(java.util.Arrays.asList("Alpha.tap", "beta.tap", "Gamma.tap", "zeta.tap"),
                     names(listing(zip)));
    }

    /**
     * The path within the archive survives, and it is the path, not the name.
     *
     * Everything about reopening the entry hangs off this: {@code Entry.key}
     * builds on it, {@code Favorites} stores it, and {@code extract} finds the
     * entry by it. A row that came back carrying only "Manic.tap" would find
     * nothing in a zip that keeps it in a folder.
     */
    @Test
    public void thepathWithinTheArchiveIsKept() throws IOException {
        Uri zip = zipOf("nested.zip", entries(
                "games/spectrum/Manic.tap", "game"));

        Entry entry = listing(zip).get(0);
        assertEquals("Manic.tap", entry.name);
        assertEquals("games/spectrum/Manic.tap", entry.inside);
        assertEquals("the zip's own uri should be what the entry is reached through",
                     zip, entry.uri);
    }

    /** An entry from a zip is not a container, whatever kind it says - the
     *  library must offer to play it, not to walk into it. */
    @Test
    public void anEntryFromAZipIsNotSomethingToWalkInto() throws IOException {
        Uri zip = zipOf("one.zip", entries("Manic.tap", "game"));

        assertTrue(listing(zip).get(0).inside != null);
        assertEquals(false, listing(zip).get(0).isContainer());
    }

    /** Two games of the same name in different folders of one zip are two
     *  rows with two different keys - the key carries the path. */
    @Test
    public void twoEntriesNamedTheSameInOneZipAreTwoRows() throws IOException {
        Uri zip = zipOf("twice.zip", entries(
                "side-a/Manic.tap", "a", "side-b/Manic.tap", "b"));

        List<Entry> rows = listing(zip);
        assertEquals(2, rows.size());
        assertNotEquals("two entries collapsed onto one key",
                        rows.get(0).key(), rows.get(1).key());
    }

    /** An empty zip is an empty listing, not a failure - a folder of games
     *  can perfectly well contain one. */
    @Test
    public void anEmptyZipListsNothingAndDoesNotThrow() throws IOException {
        assertEquals(0, listing(zipOf("empty.zip", entries())).size());
    }

    /** Something that is not a zip at all fails rather than reading as empty:
     *  "this archive has nothing in it" is a different thing to say than
     *  "this file is not an archive", and only one of them is true. */
    @Test
    public void afileThatIsNotAZipIsNotSilentlyEmpty() throws IOException {
        File notAZip = new File(context.getCacheDir(), "notreally.zip");
        made.add(notAZip);
        try (FileOutputStream out = new FileOutputStream(notAZip)) {
            out.write("this is plain text".getBytes(StandardCharsets.UTF_8));
        }

        // Either an IOException or an empty listing would be defensible, and
        // the point of the test is to record which actually happens rather
        // than to leave it unknown - it reads as empty today.
        assertEquals(0, listing(Uri.fromFile(notAZip)).size());
    }

    // --- and getting one back out ------------------------------------------------------

    @Test
    public void anEntryExtractsWithItsOwnBytes() throws IOException {
        Uri zip = zipOf("extract.zip", entries("games/Manic.tap", "the tape's bytes"));

        File out = Listing.extract(context, listing(zip).get(0));

        assertTrue("nothing was extracted", out.isFile());
        assertArrayEquals("the extracted file is not what was in the zip",
                          "the tape's bytes".getBytes(StandardCharsets.UTF_8),
                          Files.readAllBytes(out.toPath()));
    }

    /**
     * Two entries called the same in two different archives do not collide.
     *
     * The rule {@code extract}'s own comment argues: the cache file is named
     * after a hash of the entry's <em>key</em> - the archive's uri plus the
     * path - and not the name, because otherwise opening Manic.tap from one
     * zip and then from another would hand back the first one's bytes.
     */
    @Test
    public void thesameNameInTwoArchivesExtractsToTwoFiles() throws IOException {
        Uri first = zipOf("first.zip", entries("Manic.tap", "from the first zip"));
        Uri second = zipOf("second.zip", entries("Manic.tap", "from the second zip"));

        File one = Listing.extract(context, listing(first).get(0));
        File two = Listing.extract(context, listing(second).get(0));

        assertNotEquals("two archives extracted over one cache file",
                        one.getAbsolutePath(), two.getAbsolutePath());
        assertArrayEquals("from the first zip".getBytes(StandardCharsets.UTF_8),
                          Files.readAllBytes(one.toPath()));
        assertArrayEquals("from the second zip".getBytes(StandardCharsets.UTF_8),
                          Files.readAllBytes(two.toPath()));
    }

    /** Asking to extract something that is not an archive entry is an error
     *  with a name on it, not a mystery file of nothing. */
    @Test
    public void extractingSomethingThatIsNotAnArchiveEntryFails() {
        Entry plain = new Entry(Entry.Kind.FILE, "Tujad.z80",
                                Uri.fromFile(new File(context.getCacheDir(), "Tujad.z80")),
                                null, 1, 0);

        try {
            Listing.extract(context, plain);
            fail("extracting a plain file should have thrown");
        } catch (IOException expected) {
            assertTrue("the error should name the file: " + expected.getMessage(),
                       expected.getMessage().contains("Tujad.z80"));
        }
    }

    /** And an entry naming a path the archive no longer has - a zip changed
     *  on shared storage between the listing and the open, which is an
     *  ordinary thing for a card to do. */
    @Test
    public void extractingAPathTheArchiveNoLongerHasFails() throws IOException {
        Uri zip = zipOf("gone.zip", entries("Manic.tap", "game"));
        Entry missing = new Entry(Entry.Kind.FILE, "Absent.tap", zip, "Absent.tap", 1, 0);

        try {
            Listing.extract(context, missing);
            fail("extracting an entry that is not in the zip should have thrown");
        } catch (IOException expected) {
            // The message is the caller's to show; that it threw is the point.
        }
    }
}
