package dev.ldlab.zedex.storage;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * Card images for the DivMMC, and the one format they have to be in.
 *
 * libspectrum reads exactly one kind of mass storage image: an HDF, which is a
 * 128 byte header saying how big the drive is followed by its sectors. A card
 * image from anywhere else - a {@code .vhd} off a MiSTer, an {@code .img}
 * written by dd, whatever a card reader produced - is those sectors with no
 * header at all, and Fuse turns it away as "not a valid HDF file".
 *
 * So one is written round it. The header is the only difference between the two
 * formats, and the sectors are copied through unchanged, which means the games
 * on a card stay exactly where the card said they were.
 *
 * <h2>Why the copy, and why it is a copy</h2>
 *
 * The header goes at the <em>front</em>, so it cannot be added in place without
 * moving 64 megabytes of card up by 128 bytes - and a card is written to, so
 * the copy has to live somewhere permanent. It goes in the {@code cards} folder
 * beside the tapes and the disks, and it is the copy the machine then uses:
 * writes from esxDOS land in it and not in whatever the user picked.
 *
 * <h2>The rounding</h2>
 *
 * libspectrum wants a whole number of 1024-sector blocks - an SD card's
 * capacity is measured in half megabytes and it refuses anything that is not a
 * multiple of one - and a real card image usually is not: the MiSTer image this
 * was written against is a 64MB partition with a partition table in front of
 * it, one sector over. The last block is therefore padded with zeros rather
 * than the geometry being rounded down, because rounding down would cut the
 * end off the filesystem, and a sector the card claims to have but the file
 * does not is a read error waiting for whatever lands there.
 */
public final class CardImage {

    private static final String TAG = "Zedex";

    /** {@code RS-IDE}, then 0x1a: how libspectrum knows an HDF. */
    private static final byte[] SIGNATURE = {
        'R', 'S', '-', 'I', 'D', 'E', 0x1a
    };

    /** The header, and so where the sectors start. */
    private static final int HEADER = 0x80;

    private static final int SECTOR = 512;

    /** libspectrum's own minimum, and the granularity of an SD card. */
    private static final int BLOCK = 1024;

    /** Geometry: heads and sectors fixed, cylinders whatever makes the total. */
    private static final int HEADS = 16;
    private static final int SECTORS = 64;

    private CardImage() {
    }

    /**
     * Copies a card image into {@code target}, adding an HDF header if it needs
     * one. Returns the file the machine should use, or null if the copy failed.
     *
     * An image that is already an HDF is copied byte for byte: it says its own
     * size, and second-guessing that would be inventing geometry a real drive
     * had. Which it is is read off the front of the stream, since a picked
     * document is a stream and not a file until it has been copied.
     */
    public static File wrap(InputStream source, File target) {
        // Through a part file, then renamed over the target. Picking the card
        // that is already in the slot is an obvious thing to do - it is in the
        // picker like anything else - and writing straight to the target would
        // then be reading and truncating the same file at once, which is to say
        // deleting a card full of games.
        File part = new File(target.getPath() + ".part");

        try (java.io.BufferedInputStream in =
                     new java.io.BufferedInputStream(source, 64 * 1024);
             RandomAccessFile out = new RandomAccessFile(part, "rw")) {

            boolean hdf = looksLikeHdf(in);

            out.setLength(0);
            if (!hdf) out.write(new byte[HEADER]);

            long written = copy(in, out);

            if (!hdf) writeHeader(out, pad(out, written));
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "cannot write the card image " + part, e);
            part.delete();
            return null;
        }

        // Renaming over the original is safe while it is still open for
        // reading: the stream keeps the file it was given, and the name is all
        // that moves.
        if (!target.delete() && target.exists()) {
            Log.w(TAG, "cannot replace " + target);
            part.delete();
            return null;
        }

        if (!part.renameTo(target)) {
            Log.w(TAG, "cannot rename " + part + " to " + target);
            part.delete();
            return null;
        }

        return target;
    }

    /** Whether the stream starts with an HDF header, leaving it where it was. */
    private static boolean looksLikeHdf(java.io.BufferedInputStream in)
            throws IOException {
        byte[] head = new byte[SIGNATURE.length];

        in.mark(head.length);
        boolean whole = in.read(head) == head.length;
        in.reset();

        if (!whole) return false;

        for (int i = 0; i < SIGNATURE.length; i++) {
            if (head[i] != SIGNATURE[i]) return false;
        }
        return true;
    }

    private static long copy(InputStream in, RandomAccessFile out)
            throws IOException {
        byte[] buffer = new byte[256 * 1024];
        long total = 0;
        int read;

        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            total += read;
        }

        return total;
    }

    /** Zeros up to the next whole block, and says how many sectors that is. */
    private static long pad(RandomAccessFile out, long written)
            throws IOException {
        byte[] zeros = new byte[SECTOR];

        // The tail of a part-written sector first, so what follows starts on a
        // sector boundary; then whole sectors to the end of the block.
        int part = (int) (written % SECTOR);
        if (part != 0) out.write(zeros, 0, SECTOR - part);

        long sectors = (written + SECTOR - 1) / SECTOR;
        long total = ((sectors + BLOCK - 1) / BLOCK) * BLOCK;

        // One block is libspectrum's smallest card, so even an empty file is
        // rounded up to something it will accept rather than to nothing.
        if (total < BLOCK) total = BLOCK;

        for (long i = sectors; i < total; i++) out.write(zeros);

        return total;
    }

    /**
     * The header. Revision 1.1, 512 byte sectors, data at 0x80, and the
     * geometry in the drive identity block - words 1, 3 and 6 of it, little
     * endian, which is where libspectrum's {@code GET_WORD} looks.
     */
    private static void writeHeader(RandomAccessFile out, long sectors)
            throws IOException {
        byte[] header = new byte[HEADER];

        System.arraycopy(SIGNATURE, 0, header, 0, SIGNATURE.length);
        header[0x07] = 0x11;                    // revision 1.1
        header[0x08] = 0x00;                    // whole sectors, not halves
        header[0x09] = (byte) (HEADER & 0xff);  // where the data starts
        header[0x0a] = (byte) (HEADER >> 8);

        int cylinders = (int) (sectors / (HEADS * SECTORS));

        word(header, 1, cylinders);
        word(header, 3, HEADS);
        word(header, 6, SECTORS);

        out.seek(0);
        out.write(header);
    }

    /** One word of the drive identity block, which starts at 0x16. */
    private static void word(byte[] header, int index, int value) {
        header[0x16 + index * 2] = (byte) (value & 0xff);
        header[0x16 + index * 2 + 1] = (byte) (value >> 8);
    }

    /**
     * What a wrapped copy is called: the picked name with {@code .hdf} on it,
     * so the folder says what these files are and a second import of the same
     * card overwrites the first rather than filling storage with copies.
     */
    public static String nameFor(String picked) {
        int dot = picked.lastIndexOf('.');
        String base = dot > 0 ? picked.substring(0, dot) : picked;

        return base.isEmpty() ? "card.hdf" : base + ".hdf";
    }
}
