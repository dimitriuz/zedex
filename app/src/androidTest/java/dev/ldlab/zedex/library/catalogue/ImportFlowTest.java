package dev.ldlab.zedex.library.catalogue;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.Screen;
import dev.ldlab.zedex.library.Types;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.ui.CatalogueView;
import dev.ldlab.zedex.screen.LibraryActivity;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.storage.Tree;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import java.io.File;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The whole way from a search to a file in the library: type, tap a title, tap
 * Import, and find the game under {@code Downloaded/Games/}.
 *
 * The one test in this suite that goes all the way through - a live search, a
 * live download from the archive, a real zip, and a real SAF write into the
 * folder somebody granted. Everything below it is covered offline: {@code
 * ImportsTest} writes canned zips through the same {@code Imports}, {@code
 * ZxInfoCatalogueTest} parses canned replies, {@code CatalogueRowsTest} pins the
 * row's own pure functions. What none of them can say is whether the parts are
 * wired to each other, and that is this.
 *
 * <b>There is no skip, on purpose</b> - the same ruling {@code
 * CatalogueScreenTest} carries. Two things stop this working: the bench is
 * offline, and <em>this app's address has been blocked by that host again</em>,
 * which has happened once and took an email to lift. A green skip says nothing
 * about either, and the second is the one worth knowing about, so every
 * assertion that can fail for those reasons names both.
 *
 * <b>The waits are for conditions, never for durations.</b> A download's length
 * depends on the archive, the connection and the pacing; {@link #awaitImport}
 * polls the destination folder the way {@code FilterTest.awaitRowCount} polls
 * the adapter. A sample after a fixed sleep passes alone and fails behind three
 * other classes.
 *
 * <b>Only what this run made is removed.</b> The imported file's name comes out
 * of the archive, so - unlike {@code ImportsTest}, whose fixtures it writes
 * itself - a {@code nanoTime} cannot be put in it. What replaces it is the same
 * guarantee by another route: the folder's contents are listed before the
 * import and only the documents that were not there before are deleted
 * afterwards. Never the {@code Downloaded/Games/} folder, which on any real
 * device is somebody's own imported games.
 *
 * <b>That guarantee covers the SAF document and nothing this run wrote
 * elsewhere, which is not the whole of what a passing run leaves behind.</b>
 * {@code describe} writes a row into {@code library/metadata.json} and pulls
 * covers, screenshots and the rest into this app's own media folder - neither
 * is under {@code Downloaded/Games/}, so the guarantee above never touched
 * either, and a run that reached {@code describe} left the bench with one more
 * row and a handful more files than it found, for ever. {@link #tidyUp} now
 * also forgets the metadata row for each document this run made ({@link
 * Metadata#forget}) and deletes whatever landed under this app's media
 * folder for that same path - both keyed off the same {@link
 * Metadata#relativePath} the pane itself used to write them, so what is
 * undone is exactly what this run's own import could have written and
 * nothing a real collection put there first.
 *
 * <b>A repeat run still proves something.</b> The second time this runs the file
 * is already there, {@code Imports} says so rather than writing a second copy -
 * and it says so having still fetched and unpacked the archive, so the live half
 * of this test happens either way. That branch is asserted through the pane's
 * own "already in your library" line, which is why {@link #awaitImport} watches
 * for two outcomes and not one.
 */
@RunWith(AndroidJUnit4.class)
public class ImportFlowTest {

    /** Long enough for the screen, and for one paced request to a server in
     *  another country. */
    private static final long FIND = 30_000;

    /** A download, a zip, and a write through a documents provider. Generous
     *  because the number that matters is the condition, not the clock. */
    private static final long IMPORT = 120_000;

    private static final long POLL = 250;

    /**
     * A 1987 Ocean release that has been in this database since it was made,
     * with tape images to download - the same entry the import path was written
     * against ({@code HeadOverHeels.tzx.zip}, holding {@code
     * HeadOverHeels.tzx}).
     *
     * Matched case-insensitively: how a service capitalises a title is the
     * service's business and not something worth failing a test over.
     */
    private static final String SEARCH = "Head over Heels";
    private static final Pattern TITLE = Pattern.compile("(?i).*head over heels.*");

    /**
     * The row that is the 1987 original, by the facts line under its name.
     *
     * <b>Not simply the first row whose title matches.</b> Measured on the live
     * service: this search answers 153 entries and the first of them is a 2024
     * remake of the same name with nothing in it the Spectrum can open - the
     * pane says exactly that where the Import button would be, which is correct
     * behaviour and a useless target for this test. The year and the publisher
     * are what tell the release apart, and they are the row's own second line.
     */
    private static final Pattern ORIGINAL = Pattern.compile("(?i)1987.*ocean.*");

    /** {@code Imports}' own top-level folder. A literal here because it is
     *  private there, and this test is checking exactly the place a person
     *  would look. */
    private static final String DOWNLOADED = "Downloaded";

    private final UiDevice device =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    /** Bumped by the view's own {@code Host} - the wire from the pane out to
     *  whatever is holding a listing of the folder. */
    private final AtomicInteger imported = new AtomicInteger();

    private Context context;
    private CatalogueView view;
    private Uri tree;

    /** The documents this run created, and only those. */
    private final List<Uri> made = new ArrayList<>();

    /** {@link Metadata#relativePath} for each of {@link #made} - what
     *  {@link #tidyUp} forgets from the metadata store and deletes from the
     *  media folder, alongside the document itself. */
    private final List<String> described = new ArrayList<>();

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        tree = Storage.contentFolder(context);

        // Asserted rather than assumed. A bench with no folder granted, or one
        // granted read-only, cannot run this - and both are things to fix on
        // the bench rather than to pass quietly: the read-only case is exactly
        // what the pane's own permission ask exists for, and a test that
        // skipped on it would hide the ask never being reached.
        assertNotNull("no content folder is granted on this device, so there is"
                      + " nowhere for an import to land. Grant one in Settings,"
                      + " or see docs/DEVELOPING.md", tree);
        assertTrue("the content folder is granted read-only (dumpsys shows"
                   + " mode=0x1), so createDocument throws and this test cannot"
                   + " write. Re-grant it - the app takes read+write now - or"
                   + " drive the pane's own Choose button by hand once",
                   Tree.canWrite(context, tree));

        portrait();
        launchLibrary();
        installTheView();
    }

    /**
     * Removes the documents this run created, and what {@code describe} wrote
     * about them - and nothing else.
     *
     * A run stopped by hand never reaches this, which is the other half of why
     * only new documents (and their own metadata and media) are ever named: a
     * leftover is left alone by the next run rather than mistaken for its own.
     */
    @After
    public void tidyUp() {
        for (Uri document : made) {
            try {
                DocumentsContract.deleteDocument(context.getContentResolver(), document);
            } catch (Exception ignored) {
                // Untidy is not a failure. What it must never be is somebody
                // else's file.
            }
        }

        // Same key the pane itself wrote them under, so this can only ever
        // touch what this run's own import could have produced.
        for (String path : described) {
            Metadata.forget(context, path);
            deleteMedia(path);

            // Metadata.forget only touches metadata.json; Artwork keeps its
            // own in-memory cache of what it has already found for a path,
            // which would otherwise go on answering with a file this just
            // deleted for the rest of the process.
            Artwork.forget(path);
        }

        try {
            device.unfreezeRotation();
        } catch (android.os.RemoteException ignored) {
            // Nothing worth failing a finished test over.
        }
    }

    /**
     * Deletes every file this app's own media folder holds for {@code
     * relativePath}, under whichever of {@code Artwork}'s folders it landed
     * in.
     *
     * {@code Artwork}'s own folder list ({@code covers}, {@code manuals}, the
     * rest) is private, and duplicating it here would drift the moment it
     * changes - so this walks the whole media folder instead and matches each
     * file against the same stem {@code Artwork} itself computes: {@code
     * relativePath} with a leading {@code ./} and its own extension both
     * dropped. A one-off walk of a few hundred games' worth of media costs
     * nothing against a test that has just made two network round trips.
     */
    private void deleteMedia(String relativePath) {
        String stem = relativePath.startsWith("./") ? relativePath.substring(2) : relativePath;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);

        File root = Storage.mediaDirectory(context);
        deleteMatching(root, root, stem);
    }

    /**
     * Recurses under {@code dir}, deleting any file whose path relative to
     * {@code root} - with its top-level medium folder ({@code covers}, {@code
     * manuals}, ...) and its own extension both stripped - equals {@code
     * stem}.
     */
    private void deleteMatching(File root, File dir, String stem) {
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (child.isDirectory()) {
                deleteMatching(root, child, stem);
                continue;
            }

            String relative = root.toURI().relativize(child.toURI()).getPath();
            int slash = relative.indexOf('/');
            String underMediumFolder = slash >= 0 ? relative.substring(slash + 1) : relative;

            int dot = underMediumFolder.lastIndexOf('.');
            String base = dot > 0 ? underMediumFolder.substring(0, dot) : underMediumFolder;

            if (base.equals(stem) && !child.delete()) {
                android.util.Log.w("Zedex", "cannot remove leftover media " + child);
            }
        }
    }

    /**
     * Search, tap the title, tap Import, and the game is under
     * {@code Downloaded/Games/}.
     *
     * Driven through the accessibility tree at every step rather than by
     * calling the methods behind the buttons: what this test is for is the
     * wiring, and a test that called {@code beginImport} directly would pass on
     * a pane where nothing was attached to anything.
     */
    @Test
    public void asearchAtapAndAnimportPutsThegameInTheLibrary() {
        Map<String, Uri> before = namesIn(gamesFolder());

        search(SEARCH);

        // Not By.text alone: the search field now holds exactly this text too,
        // and clicking that does nothing at all. A row's title is a plain
        // TextView and the field is an EditText, which is what tells them apart.
        assertNotNull("no row on screen is \"" + SEARCH + "\" within "
                      + (FIND / 1000) + "s. Two things to check before the"
                      + " screen: whether this device has a network at all, and"
                      + " whether api.zxinfo.dk is refusing this address again -"
                      + " it has blocked it once, at the network layer, and DNS"
                      + " still resolves while port 443 is refused, so that"
                      + " looks exactly like the host being down",
                      device.wait(Until.findObject(
                              By.clazz(TextView.class).text(TITLE)), FIND));

        // The facts line, which is what tells one release from another - see
        // ORIGINAL. Tapping the second line of a row is tapping the row: the
        // listener is on the row itself and a TextView is not clickable.
        UiObject2 row = device.wait(Until.findObject(
                By.clazz(TextView.class).text(ORIGINAL)), FIND);
        assertNotNull("the search brought rows back but none of them is the 1987"
                      + " Ocean release - the row this test imports is picked by"
                      + " its year and publisher, since the newest entry of this"
                      + " name is a remake with nothing downloadable in it", row);
        row.click();

        // The pane fetches the entry's versions and files when it opens - that
        // is the expensive call, and the button cannot be laid out until it
        // answers. So waiting for Import is waiting for Catalogue.item.
        UiObject2 importButton = device.wait(Until.findObject(
                By.desc(context.getString(R.string.catalogue_import))), FIND);
        assertNotNull("the pane never offered Import within " + (FIND / 1000)
                      + "s. Either the entry's own record did not arrive - see"
                      + " the two causes above - or it arrived with nothing this"
                      + " app can open in it, in which case the pane says so"
                      + " where the button would be", importButton);

        Screen.assertHere();

        importButton.click();

        Outcome outcome = awaitImport(before);

        assertTrue("nothing arrived under " + DOWNLOADED + "/" + Kinds.GAMES
                   + " within " + (IMPORT / 1000) + "s and the pane never said"
                   + " the game was already there. The import is a live download"
                   + " from spectrumcomputing.co.uk followed by a SAF write, so"
                   + " check in that order: whether this device has a network,"
                   + " whether that host is refusing this address, and whether"
                   + " the granted folder is still writable",
                   outcome.appeared() || outcome.saidAlready);

        for (Map.Entry<String, Uri> one : outcome.after.entrySet()) {
            if (before.containsKey(one.getKey())) continue;

            made.add(one.getValue());

            // Keyed the same way Imports.describe itself keys it, so tidyUp
            // can only ever forget what this run's own import could have
            // written - never a game somebody else's collection already had
            // metadata for.
            String path = Metadata.relativePath(context, one.getValue());
            if (path != null) described.add(path);
        }

        // A file, not a name: SAF will happily create a document and leave it
        // empty, and an empty .tzx is indistinguishable from a real one until
        // the emulator refuses it.
        for (String name : outcome.newNames(before)) {
            if (!Types.openable(name)) continue;   // a multi-load's own folder

            long size = sizeOf(outcome.after.get(name));
            assertTrue("the import wrote " + name + " with " + size + " bytes in"
                       + " it", size > 0);
        }

        // And the wire out of the view: the host is told, so whatever else is
        // holding a listing of that folder can refresh. Told after the details
        // are fetched, which is why this wait is its own.
        assertTrue("the view's Host was never told about the import, so nothing"
                   + " holding a listing of that folder would refresh",
                   awaitTold());
    }

    // --- driving the screen -------------------------------------------------------

    /** Really portrait, not merely natural - on a tablet natural is landscape,
     *  and the pane is the third of the window whose shape this decides. Set
     *  before the activity is launched, since a rotation recreates it and would
     *  take the view this test installs with it. */
    private void portrait() {
        try {
            device.setOrientationPortrait();
        } catch (android.os.RemoteException e) {
            throw new AssertionError("cannot rotate the device", e);
        }
    }

    /**
     * The same door {@code EmulatorActivity.openLibrary} uses - a plain
     * launcher intent bounces straight back to the machine whenever {@code
     * startsInLibrary} is off, and this test wants the screen regardless.
     */
    private void launchLibrary() {
        Intent intent = new Intent(context, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_FROM_MENU, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startActivity(intent, Screen.here());

        assertNotNull("the library screen never came up", device.wait(
                Until.findObject(By.desc(context.getString(R.string.library_options))), FIND));

        Screen.assertHere();
    }

    /**
     * Puts a real {@link CatalogueView} over the real activity, with the real
     * ZXInfo catalogue behind it - the same shape {@code CatalogueScreenTest}
     * uses, and for the same reason: the tab that opens this is Task 12 and
     * there is nothing yet to tap.
     */
    private void installTheView() {
        Activity activity = resumedLibrary();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            view = new CatalogueView(activity,
                    new ZxInfoCatalogue(new Http.Real(activity)),
                    () -> imported.incrementAndGet());
            activity.setContentView(view);
        });

        assertNotNull("the catalogue view was never built", view);
    }

    private Activity resumedLibrary() {
        Activity[] found = { null };

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (activity instanceof LibraryActivity) found[0] = activity;
            }
        });

        assertNotNull("LibraryActivity is not the resumed activity", found[0]);
        return found[0];
    }

    /** Typed and committed with Enter, rather than called through - the editor
     *  action is the whole of how a search is started. */
    private void search(String what) {
        UiObject2 field = device.wait(Until.findObject(By.desc(
                context.getString(R.string.catalogue_search_hint))), FIND);
        assertNotNull("the catalogue has no search field on screen", field);

        field.click();
        field.setText(what);
        device.pressEnter();

        putTheKeyboardAway();
    }

    /**
     * Takes the soft keyboard down, and this is load-bearing rather than tidy.
     *
     * Typing into the field brings the IME up, the IME covers the bottom of the
     * window, and in portrait the bottom of the window is exactly where the pane
     * is - so the Import button was on screen, behind Gboard, and the first run
     * of this test failed reading "the pane never offered Import" for a pane
     * that had drawn it perfectly. Nothing about it is visible in the
     * accessibility tree either: the node is simply not there.
     *
     * Through the input method manager rather than {@code pressBack()}: Back
     * with the IME already down reaches the view, where it closes the pane or
     * leaves the shelf, so the tidy-looking version of this is a test that
     * dismantles the thing it is about.
     */
    private void putTheKeyboardAway() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            InputMethodManager manager =
                    view.getContext().getSystemService(InputMethodManager.class);
            if (manager != null) manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    // --- watching for the import ---------------------------------------------------

    /** What the destination folder holds afterwards, and whether the pane said
     *  the game was already in the library. */
    private static final class Outcome {
        final Map<String, Uri> after;
        final boolean saidAlready;
        private final Map<String, Uri> before;

        Outcome(Map<String, Uri> before, Map<String, Uri> after, boolean saidAlready) {
            this.before = before;
            this.after = after;
            this.saidAlready = saidAlready;
        }

        boolean appeared() {
            return !newNames(before).isEmpty();
        }

        List<String> newNames(Map<String, Uri> was) {
            List<String> names = new ArrayList<>(after.keySet());
            names.removeAll(was.keySet());
            return names;
        }
    }

    /**
     * Polls until the import has plainly happened, one way or the other: a
     * document under {@code Downloaded/Games} that was not there before, or the
     * pane saying the game is already in the library.
     *
     * Answers with whatever it last saw when the wait runs out, so the caller's
     * own assertion is what reports the failure - the shape {@code
     * FilterTest.awaitRowCount} established.
     */
    private Outcome awaitImport(Map<String, Uri> before) {
        String already = invariantOf(R.string.catalogue_already);

        long deadline = SystemClock.uptimeMillis() + IMPORT;
        Outcome seen = new Outcome(before, namesIn(gamesFolder()), false);

        while (!seen.appeared() && !seen.saidAlready
                && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL);
            seen = new Outcome(before, namesIn(gamesFolder()),
                               device.hasObject(By.textContains(already)));
        }
        return seen;
    }

    /** Whether the view's own {@code Host} was told, within the same generous
     *  window - it is told after the details are fetched, which is a second
     *  paced request and a few media files. */
    private boolean awaitTold() {
        long deadline = SystemClock.uptimeMillis() + IMPORT;

        while (imported.get() == 0 && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL);
        }
        return imported.get() > 0;
    }

    /**
     * The part of a one-argument string that does not depend on the argument -
     * so a message can be looked for on screen without knowing what the file
     * turned out to be called.
     *
     * Whichever of the two halves is longer, because which side of the sentence
     * the name falls on differs by language and an empty half would match
     * every view on the screen.
     */
    private String invariantOf(int template) {
        // A character no translation contains, written as an escape so it is
        // visible in the source: the format is applied and then the two halves
        // are read back off either side of it.
        String marker = "\u0001";
        String whole = context.getString(template, marker);

        int at = whole.indexOf(marker);
        String head = whole.substring(0, at).trim();
        String tail = whole.substring(at + marker.length()).trim();

        return tail.length() >= head.length() ? tail : head;
    }

    // --- reading the folder --------------------------------------------------------

    /** {@code Downloaded/Games} under the granted tree, or null before anything
     *  has ever been imported. Never created here: what this test is checking
     *  is that the import creates it. */
    private Uri gamesFolder() {
        Uri downloaded = Tree.find(context, Tree.folder(context, tree), DOWNLOADED);
        return downloaded == null ? null : Tree.find(context, downloaded, Kinds.GAMES);
    }

    /** Every document in that folder, by display name. Empty for a folder that
     *  does not exist yet. */
    private Map<String, Uri> namesIn(Uri folder) {
        Map<String, Uri> found = new LinkedHashMap<>();
        if (folder == null) return found;

        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                folder, DocumentsContract.getDocumentId(folder));

        try (Cursor cursor = context.getContentResolver().query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {

            while (cursor != null && cursor.moveToNext()) {
                found.put(cursor.getString(1),
                          DocumentsContract.buildDocumentUriUsingTree(folder,
                                                                      cursor.getString(0)));
            }
        }
        return found;
    }

    private long sizeOf(Uri document) {
        try (Cursor cursor = context.getContentResolver().query(document, new String[] {
                DocumentsContract.Document.COLUMN_SIZE }, null, null, null)) {

            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        }
        return -1;
    }
}
