package dev.ldlab.zedex.media;

import dev.ldlab.zedex.EmulatorActivity;
import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.cheats.PokeDatabase;
import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.Listing;
import dev.ldlab.zedex.screen.SettingsActivity;
import dev.ldlab.zedex.storage.CardImage;
import dev.ldlab.zedex.storage.Recents;
import dev.ldlab.zedex.storage.States;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.view.MenuDrawer;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything that can be put into the machine: the tape deck, the floppy drives
 * and the DivMMC's card slot.
 *
 * One class because it is one job three times over — pick a document, copy it
 * somewhere Fuse can open by path, write it back, name it. The differences are
 * small: a tape plays, a drive can be blanked, and a card lives outside the cache
 * because the machine writes to it and the cache is Android's to empty.
 *
 * {@link #stage} is the awkward part. Fuse opens files by path and Android hands
 * out {@code content://} URIs, so a picked document is copied into the cache under
 * its own name — libspectrum uses the extension as a hint — and its md5 taken on
 * the way past, since the bytes are going by anyway and that is what finds the
 * game's cheats.
 */
public final class Media {

    private static final String TAG = "Zedex";

    /** Request codes; the activity hands its results here. */
    private static final int REQUEST_OPEN_FILE = 1;
    private static final int REQUEST_LOAD_DISK = 4;
    private static final int REQUEST_LOAD_CARD = 6;

    /** Where picked files are staged for Fuse to open. */
    private static final String MEDIA_DIR = "media";

    /** Where a disk is written before it goes back over the file it came from. */
    private static final String WRITEBACK_DIR = "writeback";

    /**
     * How long to give the emulation thread to write a disk out.
     *
     * A write goes through the command queue and there is nothing to answer
     * back with, so the file itself is the only report. Generous because the
     * queue is drained once a frame and this waits in the background.
     */
    private static final long WRITE_TIMEOUT_MS = 3000;
    private static final long WRITE_POLL_MS = 60;

    /**
     * The card image in the DivMMC, so it is still there next time.
     *
     * Remembered here rather than left to Fuse: its own {@code divmmc_file}
     * setting is in the configuration file it writes on exit, and this port
     * never gets an orderly exit - Android stops the process.
     */
    public static final String PREF_CARD = "card";

    /** What the machine's side of the app has to lend this one. */
    public interface Host {
        /** Says an action happened; Fuse itself is silent about most of them. */
        void note(int message, Object... arguments);

        /** The ☰ sheet, for the one page here that navigates rather than acts. */
        MenuDrawer sheet();

        /** Something was opened, and states and pokes are named after it. */
        void opened(String name);
    }

    private final Activity activity;
    private final SharedPreferences preferences;
    private final Host host;

    /**
     * Which document each staged file came from, so a disk can be written back
     * over it. Keyed by the staged name, which is what a drive reports.
     *
     * Concurrent because it is filled on the staging thread and read on the UI
     * thread when the Media page is built.
     */
    private final Map<String, Uri> origins = new ConcurrentHashMap<>();

    /**
     * The md5 of the last file staged, which is how its cheats are found.
     *
     * Volatile for the same reason {@link #origins} above is concurrent: it is
     * written on the staging thread and read on the UI thread, when the Pokes
     * page asks which game is loaded. Without it there is no happens-before
     * edge between the two, and the page can be built from the *previous*
     * game's md5 - which offers the previous game's cheats, and a poke writes
     * straight into the running machine's memory. The other way it can go
     * wrong is quieter: the interim null this is set to at the start of a
     * staging reads as "no cheats known" for a game the database does have.
     */
    private volatile byte[] hash;

    /** Which drive a pending "load disk" belongs to. */
    private int pendingDrive = -1;

    /**
     * The last file opened through the picker, and so the folder the next one
     * starts in. Deliberately not stored anywhere: a fresh start goes back to
     * the content folder. See {@link #start}.
     */
    private Uri lastPicked;

    public Media(Activity activity, SharedPreferences preferences, Host host) {
        this.activity = activity;
        this.preferences = preferences;
        this.host = host;
    }

    /** The fingerprint of what is loaded, or null when nothing has been. */
    public byte[] hash() {
        return hash;
    }

    // --- the picker ----------------------------------------------------------

    /** Opens anything: Fuse decides what it is by content. */
    public void pick() {
        // Spectrum media has no registered MIME types, so anything goes.
        //
        // Write access is asked for so that a disk can be written back over the
        // file it came from; the picker grants it for documents that can take
        // it, and Save over is only offered once a write has somewhere to go.
        // Persistable because the recent list outlives this launch and a plain
        // grant does not.
        start(REQUEST_OPEN_FILE, true);
    }

    private void loadDiskInto(int id) {
        pendingDrive = id;
        start(REQUEST_LOAD_DISK, true);
    }

    private void loadCard() {
        start(REQUEST_LOAD_CARD, false);
    }

    private void start(int request, boolean writable) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        if (writable) {
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                          | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }

        // Where the browsing starts: the content folder the first time, and
        // after that wherever the last file came from.
        //
        // A field and not a preference, so it lasts exactly as long as the
        // process. Somebody working through one folder of games should not be
        // put back at the top for each of them; somebody coming back tomorrow
        // is starting again, and the folder they chose once is the better guess
        // than whatever they happened to open last.
        //
        // The picked document rather than its parent: the picker opens the
        // folder that holds it, which is what "where the last file came from"
        // means, and works it out per provider - which string surgery on a
        // document id would not.
        Uri from = lastPicked != null ? lastPicked
                                      : Storage.contentFolder(activity);
        if (from != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, from);
        }

        try {
            activity.startActivityForResult(intent, request);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Whether this result was one of ours; the activity asks first. */
    public boolean onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_OPEN_FILE && request != REQUEST_LOAD_DISK
                && request != REQUEST_LOAD_CARD) {
            return false;
        }

        if (result != Activity.RESULT_OK || data == null) return true;

        Uri uri = data.getData();
        if (uri == null) return true;

        lastPicked = uri;

        if (request == REQUEST_LOAD_DISK) {
            int drive = pendingDrive;
            pendingDrive = -1;

            if (drive >= 0) {
                new Thread(() -> {
                    File staged = stage(uri);
                    if (staged == null) return;

                    FuseNative.insertDisk(drive >> 8, drive & 0xff,
                                          staged.getAbsolutePath());
                    host.note(R.string.disk_inserted, staged.getName());
                }).start();
            }
        } else if (request == REQUEST_LOAD_CARD) {
            // Off the UI thread: a card image is tens of megabytes and it is
            // copied whole.
            new Thread(() -> insertCard(uri)).start();
        } else {
            new Thread(() -> stageAndOpen(uri)).start();
        }

        return true;
    }

    /**
     * Fuse opens files by path, so the picked document is copied into the cache
     * first. The original name is kept because libspectrum uses the extension
     * as a hint when identifying the file.
     */
    public void stageAndOpen(Uri uri) {
        File staged = stage(uri);
        if (staged == null) return;

        FuseNative.openFile(staged.getAbsolutePath());
        host.note(R.string.file_opened, staged.getName());

        host.opened(Storage.withoutExtension(staged.getName()));
    }

    /**
     * Copies a picked document somewhere Fuse can open by path, and takes its
     * md5 on the way through.
     *
     * On the way through because the bytes are already going past: hashing here
     * costs one pass that was happening anyway, where reading the file again
     * afterwards would cost another. The hash is what finds a game's cheats -
     * see {@link PokeDatabase} - and it is the file as distributed that is
     * hashed, which is what the fingerprints in that database are of.
     */
    public File stage(Uri uri) {
        String name = Storage.displayName(activity, uri);
        File staged;

        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot read " + uri);
            staged = copyAndHash(in, name);
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "failed to stage " + uri, e);
            reportOpenFailed();
            return null;
        }

        if (staged == null) {
            reportOpenFailed();
            return null;
        }

        origins.put(staged.getName(), uri);
        Recents.remember(activity.getContentResolver(), preferences, uri,
                         staged.getName());

        return staged;
    }

    /**
     * Opens one entry from inside a zip - the library's own way of loading a
     * game, alongside {@link #stageAndOpen} for a plain file. See
     * docs/LIBRARY.md, "How a game is opened".
     *
     * Fuse opens files by path and a zip entry has no path of its own, so it
     * is extracted to the cache first - see {@link Listing#extract} - and then
     * staged exactly as a picked file is: copied under its own name and hashed
     * on the way, so the md5 the poke database matches on is the entry as
     * distributed inside the zip rather than the zip itself.
     *
     * Remembered in {@link Recents} against the <em>archive's</em> uri, with
     * {@code inside} alongside it - {@link Recents.Item} can say which entry
     * of an archive it was, so this reaches Recents exactly as a plain file
     * does; see {@code Recents.remember(ContentResolver, SharedPreferences,
     * Uri, String, String)}. The name carries the archive's own alongside the
     * entry's - "turbotest.tap — bundle.zip" rather than "turbotest.tap" alone
     * - since a bare filename does not say which of several archives holding
     * the same name this row means. Not recorded as a drive's origin, unlike
     * {@link #stage}: there is nothing to write a disk back over inside a zip.
     */
    public void stageAndOpenEntry(Uri archive, String inside) {
        String name = Storage.filename(inside);
        Entry entry = new Entry(Entry.Kind.FILE, name, archive, inside, -1, 0);

        File extracted;
        try {
            extracted = Listing.extract(activity, entry);
        } catch (IOException e) {
            Log.e(TAG, "cannot extract " + inside + " from " + archive, e);
            reportOpenFailed();
            return;
        }

        File staged;
        try (InputStream in = new FileInputStream(extracted)) {
            staged = copyAndHash(in, name);
        } catch (IOException e) {
            Log.e(TAG, "cannot stage " + inside, e);
            reportOpenFailed();
            return;
        }

        if (staged == null) {
            reportOpenFailed();
            return;
        }

        FuseNative.openFile(staged.getAbsolutePath());
        host.note(R.string.file_opened, staged.getName());
        host.opened(Storage.withoutExtension(staged.getName()));

        String archiveName = Storage.displayName(activity, archive);
        Recents.remember(activity.getContentResolver(), preferences, archive,
                         name + " — " + archiveName, inside);
    }

    /**
     * Copies a stream into the cache under {@code name}, taking its md5 on the
     * way through - the shared half of {@link #stage} and
     * {@link #stageAndOpenEntry}, which differ only in where their bytes come
     * from and what happens once the file is in place.
     *
     * @return the staged file, or null if the cache folder itself could not be
     *         made - reported to the user by whichever caller this was, since
     *         only it knows which message that is.
     */
    private File copyAndHash(InputStream in, String name) throws IOException {
        File dir = new File(activity.getCacheDir(), MEDIA_DIR);
        File staged = new File(dir, name);

        if (!dir.isDirectory() && !dir.mkdirs()) return null;

        hash = null;

        try (OutputStream out = new FileOutputStream(staged)) {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                md5.update(buffer, 0, read);
            }

            hash = md5.digest();
        } catch (NoSuchAlgorithmException e) {
            // MD5 is in every Android; if it were not, the copy still stands.
            Log.e(TAG, "no MD5 on this device", e);
        }

        return staged;
    }

    private void reportOpenFailed() {
        activity.runOnUiThread(() -> Toast.makeText(activity, R.string.open_failed,
                                                    Toast.LENGTH_LONG).show());
    }

    // --- the page ------------------------------------------------------------

    /**
     * What is in the machine: its tape, every drive it has, and the card slot.
     *
     * The tape and the disks used to be separate menus, which put them at
     * different depths for no reason a user would recognise — they are the same
     * question. The machine can write to its tape as well as read from it, since
     * Fuse's tape traps catch the ROM's save routine and a BASIC
     * {@code SAVE "name"} appends to the tape held in memory; that is what
     * <em>Save tape…</em> writes out.
     */
    public void fill(MenuDrawer sheet) {
        // The deck's transport, and only while there is something on the tape:
        // Fuse's own play refuses an empty one, and a row that cannot do
        // anything is worse than no row.
        //
        // Stop rather than pause because that is what Fuse has, and it is the
        // same thing - the position is kept, so playing again carries on from
        // there. Rewind goes to the first block; there is no winding.
        boolean tape = FuseNative.hasTape();

        sheet.addItem(text(R.string.tape_load), R.drawable.ic_folder, this::pick);

        if (tape) {
            boolean playing = FuseNative.tapePlaying();

            sheet.addItem(text(playing ? R.string.tape_stop : R.string.tape_play),
                          playing ? R.drawable.ic_stop : R.drawable.ic_play,
                          () -> playTape(!playing));
            sheet.addItem(text(R.string.tape_rewind), R.drawable.ic_rewind,
                          this::rewindTape);
            sheet.addItem(text(R.string.tape_browser), R.drawable.ic_tape,
                          this::showTapeBrowser);
            // Only with something on the tape: it used to be here always and
            // answered a tap with a toast saying there was nothing to write,
            // which is a row that exists to say it does not work.
            sheet.addSubmenu(text(R.string.tape_save), R.drawable.ic_save,
                             saveTape());
        }

        sheet.addSubmenu(text(R.string.tape_new), R.drawable.ic_plus, newTape());

        sheet.addRule();
        sheet.addSection(text(R.string.menu_disks_section));

        // The drives follow the machine rather than being a fixed A: to D:,
        // so they are asked for every time this page is shown.
        String[] details = FuseNative.driveDetails();
        int[] ids = FuseNative.driveIds();
        int count = Math.min(ids.length, details.length / 3);

        if (count == 0) sheet.addNote(text(R.string.disk_no_drives));

        for (int i = 0; i < count; i++) {
            String name = details[i * 3];
            String disk = details[i * 3 + 1];
            boolean modified = "1".equals(details[i * 3 + 2]);
            int id = ids[i];

            String state = disk.isEmpty() ? text(R.string.disk_empty)
                    : modified ? text(R.string.disk_modified, disk) : disk;

            sheet.addSubmenu(name + "\n" + state, R.drawable.ic_disk,
                             page -> fillDrive(page, name, id, disk));
        }

        fillCard(sheet);
    }

    /**
     * The DivMMC's card slot.
     *
     * Here rather than among the drives because it is not one: Fuse's drive list
     * is floppy drives, the card is one slot whatever the machine, and what it
     * holds is a filesystem rather than a disk image. Its own section says both
     * things at once.
     */
    private void fillCard(MenuDrawer sheet) {
        String card = FuseNative.cardName();
        boolean loaded = !card.isEmpty();

        sheet.addRule();
        sheet.addSection(text(R.string.menu_card_section));

        // Said before the rows rather than instead of them: a card can be put in
        // before the interface is switched on, and it will be waiting when it is.
        if (!Storage.divmmcFirmware(activity).isFile()) {
            sheet.addNote(text(R.string.card_no_firmware));
        } else if (!FuseNative.hasDivmmc()) {
            sheet.addNote(text(R.string.card_off));
        }

        if (loaded) sheet.addNote(card);

        sheet.addItem(text(loaded ? R.string.card_replace : R.string.card_insert),
                      R.drawable.ic_folder, this::loadCard);

        if (loaded) {
            sheet.addItem(text(R.string.card_save), R.drawable.ic_save,
                          this::writeCard);
            sheet.addSubmenu(text(R.string.card_eject), R.drawable.ic_eject,
                             ejectCard());
        }
    }

    /**
     * One drive: what to put in it, and what to do with what is in it.
     *
     * {@code disk} is the file the drive reports, which is also how the document
     * it was opened from is found - see {@link #origins}. When there is one,
     * <em>Save over</em> comes first: a disk that came from a file is nearly
     * always meant to go back to it, and <em>Save as…</em> is the copy.
     */
    private void fillDrive(MenuDrawer sheet, String name, int id, String disk) {
        boolean loaded = !disk.isEmpty();

        sheet.addItem(text(R.string.disk_load), R.drawable.ic_folder,
                      () -> loadDiskInto(id));

        // A blank disk over a loaded one is worth asking about; over an empty
        // drive there is nothing to lose and nothing to ask.
        if (loaded) {
            sheet.addSubmenu(text(R.string.disk_new), R.drawable.ic_plus,
                             replaceDisk(name, id));
        } else {
            sheet.addItem(text(R.string.disk_new), R.drawable.ic_plus,
                          () -> newDisk(name, id));
        }

        if (!loaded) return;

        Uri origin = originOf(disk);

        if (origin != null) {
            sheet.addSubmenu(text(R.string.disk_save_over, disk), R.drawable.ic_save,
                             writeBackDisk(id, disk, origin));
        }

        sheet.addSubmenu(text(R.string.disk_save_short), R.drawable.ic_save,
                         saveDisk(name, id));
        sheet.addSubmenu(text(R.string.disk_eject), R.drawable.ic_eject,
                         ejectDisk(name, id));
    }

    /**
     * The document a drive's disk was opened from, if it was.
     *
     * The staged copy has to still be there as well as the grant: a disk Fuse
     * made itself reports "Blank disk" and has no file behind it, and the cache
     * is Android's to empty whenever it likes.
     */
    private Uri originOf(String disk) {
        Uri origin = origins.get(disk);
        if (origin == null) return null;

        return new File(new File(activity.getCacheDir(), MEDIA_DIR), disk).isFile()
                ? origin : null;
    }

    // --- the tape ------------------------------------------------------------

    private void playTape(boolean playing) {
        FuseNative.tapePlay(playing);
        host.note(playing ? R.string.tape_playing : R.string.tape_stopped);
    }

    private void rewindTape() {
        FuseNative.tapeRewind();
        host.note(R.string.tape_rewound);
    }

    /**
     * The tape's blocks, and which one the deck is at.
     *
     * A single-choice list because that is what it is - the tape is at exactly
     * one block, and tapping another winds to it. Fuse's own browser is a
     * two-column table of type and details; libspectrum writes both into one
     * line here, which is what a phone-width row has room for.
     *
     * Built from the snapshot the emulation thread publishes, so a tape that is
     * playing does not have its list read out from under it.
     */
    private void showTapeBrowser() {
        String[] blocks = FuseNative.tapeBlocks();

        if (blocks == null || blocks.length == 0) {
            host.note(R.string.tape_no_blocks);
            return;
        }

        int current = Math.max(0, Math.min(blocks.length - 1, FuseNative.tapeBlock()));

        host.sheet().go(text(R.string.tape_browser_title), page -> {
            for (int i = 0; i < blocks.length; i++) {
                int which = i;

                page.addChoice(blocks[which], which == current, () -> {
                    FuseNative.tapeBlockSelect(which);
                    host.note(R.string.tape_wound, which + 1);
                });
            }
        });
    }

    private MenuDrawer.Page saveTape() {
        return page -> {
            EditText input = page.addField(text(R.string.state_name),
                                           suggestedTapeName(), 0);

            page.addItem(text(R.string.tape_save), R.drawable.ic_save,
                         () -> writeTape(Storage.sanitise(input.getText().toString())));
        };
    }

    private String suggestedTapeName() {
        String base = preferences.getString(States.KEY_MEDIA_NAME, null);
        if (base != null && !base.isEmpty() && !tapeFile(base).exists()) return base;

        for (int n = 1; n < 1000; n++) {
            String numbered = text(R.string.tape_default_name, n);
            if (!tapeFile(numbered).exists()) return numbered;
        }
        return text(R.string.tape_default_name, 1);
    }

    /**
     * Fuse picks the format from the extension, so choosing one is choosing
     * what to call the file. Typing .tap or .tzx yourself still wins; the
     * setting is only what happens when you do not.
     */
    private File tapeFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String wanted = preferences.getString(SettingsActivity.KEY_TAPE_FORMAT, "tap");
        String file = lower.endsWith(".tap") || lower.endsWith(".tzx")
                ? name : name + "." + wanted;

        return new File(Storage.tapesDirectory(activity), file);
    }

    private void writeTape(String name) {
        if (name.isEmpty()) name = text(R.string.tape_default_name, 1);

        File directory = Storage.tapesDirectory(activity);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Toast.makeText(activity, R.string.state_failed, Toast.LENGTH_LONG).show();
            return;
        }

        File target = tapeFile(name);
        FuseNative.writeTape(target.getAbsolutePath());
        Toast.makeText(activity, text(R.string.tape_saved, target.getName()),
                       Toast.LENGTH_LONG).show();
    }

    private MenuDrawer.Page newTape() {
        return page -> {
            page.addNote(text(R.string.tape_new_confirm));
            page.addItem(text(R.string.tape_new), R.drawable.ic_plus, () -> {
                FuseNative.newTape();
                host.note(R.string.tape_new_done);
            });
        };
    }

    // --- the drives ----------------------------------------------------------

    private MenuDrawer.Page replaceDisk(String name, int id) {
        return page -> {
            page.addNote(text(R.string.disk_replace, name));
            page.addItem(text(R.string.disk_new), R.drawable.ic_plus,
                         () -> newDisk(name, id));
        };
    }

    private void newDisk(String name, int id) {
        FuseNative.newDisk(id >> 8, id & 0xff);
        host.note(R.string.disk_new_done, name);
    }

    private MenuDrawer.Page ejectDisk(String name, int id) {
        return page -> {
            page.addNote(text(R.string.disk_eject_confirm, name));
            page.addItem(text(R.string.disk_eject), R.drawable.ic_eject, () -> {
                FuseNative.ejectDisk(id >> 8, id & 0xff);
                host.note(R.string.disk_ejected, name);
            });
        };
    }

    /**
     * Writes what is in a drive back out. Fuse picks the format from the
     * extension, and not every format it reads can be written - an .scl in
     * particular has to come back as a .trd - so the interface decides the
     * default.
     */
    private MenuDrawer.Page saveDisk(String drive, int id) {
        return page -> {
            EditText input = page.addField(text(R.string.state_name),
                                           suggestedDiskName(drive, id), 0);

            page.addItem(text(R.string.disk_save_short), R.drawable.ic_save,
                         () -> writeDisk(id, Storage.sanitise(input.getText().toString())));
        };
    }

    /** What each disk interface writes by default. */
    private static String extensionFor(int id) {
        switch (id >> 8) {
            case 1: return ".trd";      // Beta 128, TR-DOS
            case 2:                     // +D
            case 5: return ".mgt";      // DISCiPLE
            case 4: return ".opd";      // Opus Discovery
            case 6: return ".d80";      // Didaktik 80
            default: return ".dsk";     // +3, and anything unexpected
        }
    }

    private String suggestedDiskName(String drive, int id) {
        String base = preferences.getString(States.KEY_MEDIA_NAME, null);
        if (base == null || base.isEmpty()) base = Storage.sanitise(drive);

        if (!diskFile(base, id).exists()) return base;

        for (int n = 2; n < 1000; n++) {
            if (!diskFile(base + " " + n, id).exists()) return base + " " + n;
        }
        return base;
    }

    private File diskFile(String name, int id) {
        String lower = name.toLowerCase(Locale.ROOT);
        boolean hasExtension = lower.endsWith(".dsk") || lower.endsWith(".trd")
                || lower.endsWith(".mgt") || lower.endsWith(".opd")
                || lower.endsWith(".img") || lower.endsWith(".udi")
                || lower.endsWith(".fdi") || lower.endsWith(".d80")
                || lower.endsWith(".d40") || lower.endsWith(".sad");

        return new File(Storage.disksDirectory(activity),
                        hasExtension ? name : name + extensionFor(id));
    }

    private void writeDisk(int id, String name) {
        if (name.isEmpty()) name = "Disk";

        File directory = Storage.disksDirectory(activity);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Toast.makeText(activity, R.string.state_failed, Toast.LENGTH_LONG).show();
            return;
        }

        File target = diskFile(name, id);
        FuseNative.writeDisk(id >> 8, id & 0xff, target.getAbsolutePath());
        Toast.makeText(activity, text(R.string.tape_saved, target.getName()),
                       Toast.LENGTH_LONG).show();
    }

    private MenuDrawer.Page writeBackDisk(int id, String disk, Uri origin) {
        return page -> {
            page.addNote(text(R.string.disk_save_over_confirm, disk));
            page.addItem(text(R.string.disk_save_over_ok), R.drawable.ic_save,
                         () -> writeBack(id, disk, origin));
        };
    }

    /**
     * Writes a drive back over the file it was opened from.
     *
     * Through a copy in the cache rather than straight into the document, for
     * two reasons that both come down to the original being irreplaceable.
     * Fuse writes by path and a document is a Uri, so something has to carry
     * the bytes across in any case; and {@code disk_write} truncates its file
     * before it knows whether it has anything to write - a disk that turns out
     * to be unformatted leaves nothing behind - so writing in place would
     * destroy the disk in the name of saving it.
     *
     * Keeping the name keeps the format: Fuse picks one from the extension, so
     * a .trd goes back as a .trd. Everything it reads it can also write, apart
     * from .td0, which fails and leaves the original alone.
     */
    private void writeBack(int id, String disk, Uri origin) {
        File temp = new File(new File(activity.getCacheDir(), WRITEBACK_DIR), disk);
        File directory = temp.getParentFile();

        if (!directory.isDirectory() && !directory.mkdirs()) {
            Toast.makeText(activity, R.string.state_failed, Toast.LENGTH_LONG).show();
            return;
        }

        temp.delete();
        FuseNative.writeDisk(id >> 8, id & 0xff, temp.getAbsolutePath());

        new Thread(() -> {
            if (!waitForWrite(temp)) {
                // Fuse has already said why, through an error box of its own.
                host.note(R.string.disk_save_over_failed, disk);
                temp.delete();
                return;
            }

            try (InputStream in = new FileInputStream(temp);
                 OutputStream out = activity.getContentResolver()
                         .openOutputStream(origin, "wt")) {
                if (out == null) throw new IOException("cannot write " + origin);

                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            } catch (IOException | SecurityException | UnsupportedOperationException e) {
                Log.e(TAG, "cannot write back " + origin, e);
                host.note(R.string.disk_save_over_denied, disk);
                return;
            } finally {
                temp.delete();
            }

            host.note(R.string.tape_saved, disk);
        }).start();
    }

    /**
     * Waits for the emulation thread to have written a disk out, which is the
     * only way to know that it has: the write goes through the command queue and
     * there is nothing to answer back with.
     *
     * A size that has stopped growing rather than a file that exists, because
     * the file appears as soon as it is opened and is filled afterwards. A
     * failed write is a file removed again, so this simply times out.
     */
    private static boolean waitForWrite(File file) {
        long previous = -1;

        for (long waited = 0; waited < WRITE_TIMEOUT_MS; waited += WRITE_POLL_MS) {
            try {
                Thread.sleep(WRITE_POLL_MS);
            } catch (InterruptedException e) {
                return false;
            }

            long size = file.length();
            if (size > 0 && size == previous) return true;
            previous = size;
        }

        return false;
    }

    /**
     * The drives holding changes that have not been written back, or null when
     * there are none. Fuse's own flag, asked the same way {@link #fill} asks
     * it - the details arrive as name, disk, modified for each drive.
     */
    public String modifiedDisks() {
        String[] details = FuseNative.driveDetails();
        StringBuilder names = new StringBuilder();

        for (int i = 0; i + 2 < details.length; i += 3) {
            if (!"1".equals(details[i + 2])) continue;

            if (names.length() > 0) names.append(", ");
            names.append(details[i]);
        }

        return names.length() > 0 ? names.toString() : null;
    }

    // --- the card ------------------------------------------------------------

    /**
     * Copies a picked card image into the cards folder, wrapping it in an HDF
     * header if it needs one, and puts it in the slot.
     *
     * Not {@link #stage}, which every other kind of media goes through: that
     * copies into the cache, and a card is written to by the machine - the saves
     * and the high scores are on it. A card swept away with the cache is a card
     * that lost them. {@link CardImage} has the rest of it.
     */
    private void insertCard(Uri picked) {
        String name = CardImage.nameFor(Storage.displayName(activity, picked));
        File directory = Storage.cardsDirectory(activity);

        // Before the copy, not after: the copy replaces the file it writes to,
        // and if that is the card in the slot then anything the machine has
        // written and not committed would go down with the old one.
        if (!FuseNative.cardName().isEmpty()) FuseNative.commitCard();

        host.note(R.string.card_preparing, name);

        if (!directory.isDirectory() && !directory.mkdirs()) {
            host.note(R.string.card_failed);
            return;
        }

        File target;
        try (InputStream in = activity.getContentResolver().openInputStream(picked)) {
            if (in == null) throw new IOException("cannot read " + picked);
            target = CardImage.wrap(in, new File(directory, name));
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "cannot read the card image " + picked, e);
            target = null;
        }

        if (target == null) {
            host.note(R.string.card_failed);
            return;
        }

        preferences.edit().putString(PREF_CARD, target.getAbsolutePath()).apply();

        FuseNative.insertCard(target.getAbsolutePath());
        host.note(R.string.card_inserted, target.getName());
    }

    private void writeCard() {
        FuseNative.commitCard();
        host.note(R.string.card_written);
    }

    private MenuDrawer.Page ejectCard() {
        return page -> {
            page.addNote(text(R.string.card_eject_confirm));
            page.addItem(text(R.string.card_eject), R.drawable.ic_eject, () -> {
                FuseNative.ejectCard();
                preferences.edit().remove(PREF_CARD).apply();
                host.note(R.string.card_ejected);
            });
        };
    }

    private String text(int message, Object... arguments) {
        return activity.getString(message, arguments);
    }
}
