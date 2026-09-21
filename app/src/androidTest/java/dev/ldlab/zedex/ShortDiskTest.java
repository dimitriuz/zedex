package dev.ldlab.zedex;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * An extended CPC disk image whose header claims one more track than the file
 * holds - which is how <em>Los 40 Principales Vol. 1</em> is written, and how
 * 1.7.0 came to crash on opening it.
 *
 * <b>The bug is Fuse's, in {@code open_cpc}</b>, and the patch is
 * {@code native/patches/0003-disk-do-not-read-past-the-end-of-a-CPC-image.patch}.
 * Its first pass over the tracks forces the MFM byte of each one -
 * {@code buff[ idx + 0x13 ]} - stepping {@code idx} through the file by the
 * track size table, and it checks neither the size table's zeroes nor the end
 * of the buffer. A header that says 41 tracks over 40 tracks of data reads,
 * and on a zero byte <em>writes</em>, nineteen bytes past the end of the
 * allocation. The main loop below it already handles both cases; only this
 * pre-pass does not.
 *
 * <b>It is a crash on some devices and nothing at all on others</b>, which is
 * what made the report so hard to read: the reporter's tablet died and their
 * phone played the same two files perfectly. Whether an over-read faults is
 * whether the byte after the buffer happens to be mapped, and Android's
 * allocator puts a guard page immediately after a large block - so it is the
 * size of the image and the state of the heap that decide, not anything about
 * the game.
 *
 * <b>No assertion for the crash itself.</b> Instrumentation runs in the app's
 * own process, so a SIGSEGV on the emulation thread takes the whole run down
 * with it - the same reason {@code HandOverTest} needs none. What is asserted
 * after it is that Fuse made sense of the image rather than merely surviving
 * it: the drive holds the disk, and it has the forty tracks the file really
 * has and not the forty-one it claims.
 */
@RunWith(AndroidJUnit4.class)
public class ShortDiskTest {

    /** The +3's own format, and the one the real image uses: 9 sectors of
     *  512 bytes behind a 256 byte track header, so 0x13 pages a track. */
    private static final int SECTORS = 9;
    private static final int SECTOR_SIZE = 512;
    private static final int TRACK_HEADER = 0x100;
    private static final int TRACK_SIZE = TRACK_HEADER + SECTORS * SECTOR_SIZE;

    /** Tracks the header claims, and tracks the file actually carries. The
     *  last size table entry is zero - "unformatted" - which is exactly what
     *  a CPDRead dump of a 40 track disk looks like. */
    private static final int CLAIMED = 41;
    private static final int PRESENT = 40;

    /** Which of the two headers {@code open_cpc} reads, since the walk that
     *  broke is shared by both. */
    private static final boolean EXTENDED = true;
    private static final boolean STANDARD = false;

    private final Emulator emulator = new Emulator();

    private File image;

    @Before
    public void setUp() {
        emulator.useDataFolder();
        emulator.launch();
        assumeFalse("no ROMs in " + emulator.romFolder(), emulator.needsRoms());
    }

    @Test
    public void opensAnImageThatClaimsMoreTracksThanItHas() throws IOException {
        // A .dsk selects a +3 on its own - utils.c does it for any machine
        // without CAPABILITY_PLUS3_DISK - so the machine needs no arranging.
        open("uitest-short.dsk", image(EXTENDED, CLAIMED));
    }

    /**
     * The same walk reads a standard image too, where the track size is one
     * number in the header rather than a table - so the bound has to hold
     * there as well, and the unformatted-track test that does not apply must
     * not get in the way of it.
     */
    @Test
    public void opensAStandardImageThatClaimsMoreTracksThanItHas() throws IOException {
        open("uitest-short-standard.dsk", image(STANDARD, CLAIMED));
    }

    /** And an image whose header tells the truth still opens as it did, which
     *  is what a bound added to a loop can quietly cost. */
    @Test
    public void stillOpensAnImageThatSaysWhatItHas() throws IOException {
        open("uitest-whole.dsk", image(EXTENDED, PRESENT));
    }

    private void open(String name, byte[] bytes) throws IOException {
        image = new File(emulator.context().getCacheDir(), name);
        Files.write(image.toPath(), bytes);

        emulator.open(image);

        assertTrue("the disk never reached a drive", driveHolds(image.getName()));
    }

    /** Whether any drive reports this file. Three strings a drive: its name,
     *  the disk in it, and "1" when modified. */
    private static boolean driveHolds(String name) {
        String[] details = FuseNative.driveDetails();

        for (int at = 1; at < details.length; at += 3) {
            if (details[at] != null && details[at].contains(name)) return true;
        }
        return false;
    }

    /**
     * A CPC image of {@link #PRESENT} tracks whose header claims
     * {@code claimed} of them.
     *
     * Empty tracks, because what is under test is the header walk rather than
     * anything on the disk - but real headers, so that the image is one Fuse
     * would otherwise open without complaint and the test says something
     * about the fix as well as about the crash.
     */
    private static byte[] image(boolean extended, int claimed) {
        byte[] image = new byte[TRACK_HEADER + PRESENT * TRACK_SIZE];

        put(image, 0, extended ? "EXTENDED CPC DSK File\r\nDisk-Info\r\n"
                               : "MV - CPCEMU Disk-File\r\nDisk-Info\r\n");
        put(image, 0x22, "Zedex test\r\n");

        image[0x30] = (byte) claimed;
        image[0x31] = 1;                                 /* one side */

        if (extended) {
            // The track size table, in 256 byte units, one byte a track. The
            // entry for a track that is not there stays zero - "unformatted",
            // which is how a dump of a 40 track disk names its 41st.
            for (int track = 0; track < PRESENT; track++) {
                image[0x34 + track] = (byte) (TRACK_SIZE / 256);
            }
        } else {
            // One size for every track, in bytes rather than pages.
            image[0x32] = (byte) (TRACK_SIZE & 0xff);
            image[0x33] = (byte) (TRACK_SIZE >> 8);
        }

        for (int track = 0; track < PRESENT; track++) {
            int at = TRACK_HEADER + track * TRACK_SIZE;

            put(image, at, "Track-Info\r\n");
            image[at + 0x10] = (byte) track;             /* track */
            image[at + 0x11] = 0;                        /* side */
            image[at + 0x13] = 2;                        /* MFM */
            image[at + 0x14] = 2;                        /* 512 byte sectors */
            image[at + 0x15] = (byte) SECTORS;
            image[at + 0x16] = 0x4e;                     /* gap 3 */
            image[at + 0x17] = (byte) 0xe5;              /* filler */

            for (int sector = 0; sector < SECTORS; sector++) {
                int info = at + 0x18 + 8 * sector;

                image[info] = (byte) track;              /* C */
                image[info + 1] = 0;                     /* H */
                image[info + 2] = (byte) (0xc1 + sector);/* R, the +3's own */
                image[info + 3] = 2;                     /* N, 512 bytes */
                image[info + 6] = (byte) (SECTOR_SIZE & 0xff);
                image[info + 7] = (byte) (SECTOR_SIZE >> 8);
            }
        }

        return image;
    }

    private static void put(byte[] into, int at, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, into, at, bytes.length);
    }
}
