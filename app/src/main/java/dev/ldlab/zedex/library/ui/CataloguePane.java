package dev.ldlab.zedex.library.ui;

import dev.ldlab.zedex.EmulatorActivity;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.Types;
import dev.ldlab.zedex.library.catalogue.Catalogue;
import dev.ldlab.zedex.library.catalogue.Imports;
import dev.ldlab.zedex.library.catalogue.Pick;
import dev.ldlab.zedex.library.catalogue.Thumbnails;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.scrape.Provider;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.library.scrape.Scrapers;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.storage.Tree;
import dev.ldlab.zedex.view.Palette;
import dev.ldlab.zedex.work.Work;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * One catalogue title, and the button that brings it in.
 *
 * A class of its own rather than a second life for {@link DetailPane}, and for
 * the same reason {@link CatalogueAdapter} is not {@link EntryAdapter}: that
 * pane is written throughout in terms of an {@code Entry} - a file with a
 * {@code Uri}, a size, a modified time and a path inside an archive - and a
 * catalogue item has none of the four. What is shared is the <em>appearance</em>
 * and the position: the same third of the window, beside the list in landscape
 * and beneath it in portrait, the same cover box over the same facts over the
 * same row of buttons. See the plan's "Two places this plan differs from the
 * spec".
 *
 * <b>It is not always there, and that is the one deliberate difference.</b>
 * {@link DetailPane} is present whether or not anything is selected, because
 * the library always has a selection to be about; this one costs a third of the
 * window to say "nothing chosen" on a screen whose whole job is the list. So it
 * is hidden until a title is tapped, and {@link CatalogueView#onBack} closes it
 * before it starts popping shelves.
 *
 * <b>{@link Catalogue#item} is asked when the pane opens, never when a row is
 * drawn.</b> Versions and files are what make that call expensive - one request
 * per title - and a list needs neither. That is why what arrives from the tap is
 * a {@link Catalogue.Item} with an empty {@link Catalogue.Item#versions()} and
 * why the three buttons cannot be laid out until the answer lands.
 *
 * <b>The cover is the row's own 140dp thumbnail, at the row's own size.</b>
 * {@link Thumbnails} keys its cache on the url alone, so asking for a second
 * size would hand this pane a bitmap decoded for a row and call it a hit - see
 * {@link CatalogueAdapter}'s class comment, which records what a second size
 * would actually cost. The box here is the same {@link CatalogueAdapter#ROW_DP},
 * so a cover the list already fetched is drawn without being enlarged into
 * blur and without a second request.
 *
 * <b>The write grant is asked for here and nowhere else.</b> A content folder
 * chosen before this feature existed is granted read-only - measured, {@code
 * dumpsys} shows {@code mode=0x1} - and {@code createDocument} against it throws
 * a {@code SecurityException} that {@code Tree.create} catches, logging "cannot
 * create" and answering null, so an import would fail with nothing on screen to
 * explain it. (A different swallow lives in {@code Storage.keepAccessTo}, which
 * catches the one {@code takePersistableUriPermission} throws when a grant
 * cannot be persisted - the two are not the same one.) The permission is for
 * importing, so importing is the moment it is worth interrupting somebody:
 * never at startup, never on opening the tab, and never for anybody who browses
 * and imports nothing. See {@link #beginImport} and {@link #onActivityResult}.
 */
public final class CataloguePane extends FrameLayout {

    private static final String TAG = "Zedex";

    /**
     * What the pane cannot do for itself.
     *
     * Deliberately one method, and the test for whether that is the right seam
     * is CLAUDE.md's own - a {@code Host} wider than about four means the seam
     * has moved. The pane starts the emulator itself, exactly as {@code
     * DetailPane.openMusic} does, and launches the folder picker through the
     * activity it is already inside; the one thing it cannot know is what else
     * in the app is showing a list that a new file belongs in.
     */
    public interface Host {

        /** A file landed in the library. Whoever is holding a listing of it -
         *  {@code LibraryActivity}'s Browse tab, and the metadata store's own
         *  caches - has to be told, because a listing is keyed by path and
         *  knows nothing about a file it did not list. */
        void imported();
    }

    /**
     * The folder picker's request code, and the whole reason {@link
     * #onActivityResult} is public.
     *
     * A picker <em>has</em> an {@code onActivityResult} and this is it - do not
     * reach for {@code Updater.resumeIfAllowed}'s {@code onResume} shape, which
     * exists because a <em>settings page</em> permission has no callback at all.
     * The answer arrives at the hosting activity, which forwards it here
     * through {@link CatalogueView#onActivityResult}.
     *
     * High and arbitrary so it cannot collide with a code the hosting activity
     * already uses for something of its own.
     */
    public static final int REQUEST_WRITABLE_TREE = 0x7a11;

    private final Catalogue catalogue;
    private final Http http;
    private final Host host;

    private final ImageView cover;
    private final TextView title;

    /** Year, publisher and the catalogue's own word for the kind, one line,
     *  joined the way {@link CatalogueAdapter#facts} joins the first two. */
    private final TextView facts;

    /** The service's own word for why a title is not available - gone rather
     *  than empty when it says nothing, exactly as the greyed row is. */
    private final TextView availability;

    /**
     * The one line that talks about what is happening: the import's progress
     * and its answer, the refusal when nothing here can be opened, and the
     * request for a writable folder.
     *
     * One view rather than four, because all four are the same sentence in the
     * same place at four different moments and only ever one of them is true.
     */
    private final TextView status;

    private final Button importButton;
    private final Button versionsButton;
    private final Button recordingButton;

    /** Beside {@link #status} when the folder needs re-granting, and only
     *  then - see {@link #askForWriteAccess}. */
    private final Button chooseButton;

    /**
     * The item this pane is about, with its versions and files - null until
     * {@link Catalogue#item} answers, which is what the three buttons wait for.
     */
    private Catalogue.Item showing;

    /**
     * Bumped by every {@link #show}, before anything asynchronous is asked
     * for, and compared by everything that answers - the same shape {@link
     * DetailPane#token} and {@link CatalogueAdapter}'s {@code bindToken} both
     * use. Two things answer late here: the item lookup, and an import that
     * takes as long as a download plus a scrape plus its media.
     */
    private int token;

    /**
     * The import that was interrupted to ask for a writable folder, waiting
     * for the answer.
     *
     * <b>The ask carries the import with it.</b> An ask that loses the thing
     * you asked for is an ask you have to answer twice - somebody grants the
     * folder and is then looking at the same pane with the same button
     * unpressed, which reads as the grant having failed.
     */
    private Runnable pending;

    /** Set while an import is running, so a second tap on the button does not
     *  start a second download of the same file. */
    private boolean importing;

    /**
     * Builds the whole pane, in whichever of its two shapes.
     *
     * {@code landscape} is passed in rather than read from the configuration
     * here for the same reason {@link DetailPane}'s is: whoever is stacking
     * this against the list has already asked, and two reads of one question
     * are two chances to disagree.
     */
    public CataloguePane(Context context, boolean landscape, Catalogue catalogue,
                         Http http, Host host) {
        super(context);

        this.catalogue = catalogue;
        this.http = http;
        this.host = host;

        setBackgroundColor(Palette.BACKING);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        column.setPadding(pixels(16), pixels(16), pixels(16), pixels(16));

        FrameLayout coverBox = new FrameLayout(context);
        coverBox.setBackgroundColor(0x14ffffff);

        // FIT_CENTER, not CENTER_CROP: a ZXDB record's picture is as often a
        // 4:3 loading screen as a 3:4 cover, and the pane is the one place a
        // person looks at the picture rather than past it. The same choice the
        // rows make, for the same reason.
        cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        coverBox.addView(cover, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);

        /*
         * The words scroll, and that is not cosmetic.
         *
         * A LinearLayout hands its children the height they measured, in order,
         * and lays whatever does not fit past its own bottom edge - so on a
         * short window (a portrait pane is a third of the height, and a third
         * of 1920px at xxhdpi is 213dp) the last button is not merely cramped,
         * it is off the pane and untouchable, which looks exactly like a button
         * that failed to draw. fillViewport keeps the weighted spacer working
         * where there is room, so the buttons still sit at the foot of a tall
         * pane rather than bunched under the facts.
         */
        android.widget.ScrollView scroller = new android.widget.ScrollView(context);
        scroller.setFillViewport(true);
        scroller.addView(words, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        if (landscape) {
            // The tall narrow column: the box on top at the size its bitmap
            // was decoded for, everything else beneath it.
            column.addView(coverBox, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, pixels(CatalogueAdapter.ROW_DP)));
            column.addView(scroller, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            // Beside the words instead: a portrait pane is wide and not tall,
            // so it is the width the two split - the same 2-against-3 DetailPane
            // settled on, and for the same reason.
            LinearLayout.LayoutParams boxParams =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f);
            boxParams.rightMargin = pixels(16);
            column.addView(coverBox, boxParams);
            column.addView(scroller, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 3f));
        }

        title = new TextView(context);
        title.setTextColor(Palette.TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title, wrap());

        facts = new TextView(context);
        facts.setTextColor(Palette.MUTED);
        facts.setTextSize(13);
        facts.setPadding(0, pixels(6), 0, 0);
        words.addView(facts, wrap());

        availability = new TextView(context);
        availability.setTextColor(Palette.MUTED);
        availability.setTextSize(13);
        availability.setPadding(0, pixels(4), 0, 0);
        availability.setVisibility(View.GONE);
        words.addView(availability, wrap());

        // Whatever is left over, and nothing in it - so the buttons sit at the
        // foot of the pane rather than floating under the facts, and so it is
        // this that gives way on a short pane and never the button beneath it.
        words.addView(new View(context), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(context);
        status.setTextColor(Palette.MUTED);
        status.setTextSize(13);
        status.setPadding(0, pixels(6), 0, pixels(2));
        status.setVisibility(View.GONE);
        words.addView(status, wrap());

        // Named as well as labelled, all four of them. A Button's own text is
        // drawn upper-cased by the platform theme and UI Automator matches
        // exactly, so a test - or a screen reader - looking for "Import" would
        // be looking for something that is only sometimes there. Set once and
        // never changed, which is what CLAUDE.md's rule against a description
        // that moves asks for; the label above it is what changes.
        chooseButton = button(R.string.catalogue_choose_folder);
        chooseButton.setOnClickListener(v -> chooseWritableFolder());
        words.addView(chooseButton, wrap());

        importButton = button(R.string.catalogue_import);
        importButton.setOnClickListener(v -> importTheGame());
        words.addView(importButton, wrap());

        versionsButton = button(R.string.catalogue_versions);
        versionsButton.setOnClickListener(v -> chooseVersion());
        words.addView(versionsButton, wrap());

        recordingButton = button(R.string.catalogue_recording);
        recordingButton.setOnClickListener(v -> importTheRecording());
        words.addView(recordingButton, wrap());

        addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    // --- what the screen tells it -------------------------------------------------

    /**
     * Fills the pane from the row that was tapped and asks the catalogue for
     * the rest of it.
     *
     * Everything a list already knows is drawn immediately - the title, the
     * year, the publisher, the kind, the availability and the cover the row
     * itself fetched - and the buttons wait for {@link Catalogue#item}, since
     * which of them make sense is a question about versions and files that only
     * that answer holds.
     */
    public void show(Catalogue.Item item) {
        int forThis = ++token;

        showing = null;
        pending = null;
        importing = false;

        title.setText(item.title());
        facts.setText(factsLine(item));

        boolean stated = item.availability() != null && !item.availability().isEmpty();
        availability.setText(stated ? item.availability() : "");
        availability.setVisibility(stated ? View.VISIBLE : View.GONE);

        // Hidden until the answer says which of them this title can offer.
        say(null);
        chooseButton.setVisibility(View.GONE);
        importButton.setVisibility(View.GONE);

        // Reset, not merely hidden: a finished import turns this button into
        // Play for the file it wrote - see importFinished - and a pane reused
        // for the next title would otherwise offer to play the previous one.
        label(importButton, R.string.catalogue_import);
        importButton.setOnClickListener(v -> importTheGame());

        versionsButton.setVisibility(View.GONE);
        recordingButton.setVisibility(View.GONE);

        showCover(forThis, item.pictureUrl());

        Work.run("catalogue-item", () -> {
            Catalogue.Item full = null;
            Throwable failure = null;

            try {
                full = catalogue.item(item.id());
            } catch (ScrapeException e) {
                failure = e;
            } catch (RuntimeException e) {
                // A catalogue is somebody else's JSON; an unreadable one is
                // worth the same line a refusal gets and not worth taking the
                // app down for.
                failure = e;
            }

            Catalogue.Item answered = full;
            Throwable why = failure;

            Work.onMain(() -> itemArrived(forThis, answered, why));
        });
    }

    /**
     * The answer to the folder picker, forwarded by whichever activity is
     * holding this - see {@link #REQUEST_WRITABLE_TREE}.
     *
     * Persists read <b>and</b> write, since read-only is exactly the grant that
     * brought us here, and then carries on with the import that prompted the
     * ask. A cancelled picker is still ours to have handled: the pending import
     * stays where it is, with the button still on screen to try again.
     *
     * @return whether this was the pane's own request, so a host can go on to
     *         its own handling of anything else.
     */
    public boolean onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_WRITABLE_TREE) return false;

        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;

        Uri tree = data.getData();

        Storage.keepAccessTo(getContext(), tree, Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        preferences().edit().putString(Storage.KEY_CONTENT_TREE, tree.toString()).apply();

        chooseButton.setVisibility(View.GONE);

        Runnable then = pending;
        pending = null;
        if (then != null) then.run();

        return true;
    }

    // --- the item ------------------------------------------------------------------

    /**
     * {@link Catalogue#item}'s answer, or the reason there is not one.
     *
     * A refusal is one line and no buttons rather than an Import button that
     * could only fail: what the buttons say depends on files this pane does not
     * have.
     */
    private void itemArrived(int forThis, Catalogue.Item full, Throwable failure) {
        if (forThis != token) return;   // another title was tapped

        if (full == null) {
            Log.w(TAG, "cannot read the catalogue's entry", failure);
            say(getContext().getString(R.string.catalogue_failed));
            return;
        }

        showing = full;

        // The full record's own facts, which can be better than the row's: a
        // search result and a fetched entry are two replies and the second is
        // the one with everything in it.
        facts.setText(factsLine(full));

        Catalogue.Download game = Pick.forGame(full);

        // Replaced by the refusal rather than shown disabled. A greyed button
        // invites a tap and then explains nothing, and this is a real thing to
        // find: an entry whose only files are a cassette scan and a magazine
        // advert has nothing the Spectrum can open.
        importButton.setVisibility(game != null ? View.VISIBLE : View.GONE);
        if (game == null) say(getContext().getString(R.string.catalogue_nothing_to_get));

        versionsButton.setVisibility(full.versions().size() > 1 ? View.VISIBLE : View.GONE);
        recordingButton.setVisibility(Pick.recording(full) != null ? View.VISIBLE : View.GONE);
    }

    /**
     * The cover, from the same cache and at the same size the row used - so a
     * title whose row was on screen a moment ago draws without a request.
     *
     * The listener is always reached on this thread: {@link Thumbnails#load}
     * answers a cache hit on the calling thread and everything else through
     * {@link Work#onMain}, and this method only ever runs on the main one.
     */
    private void showCover(int forThis, String url) {
        cover.setImageDrawable(null);
        cover.clearColorFilter();

        if (url == null || url.isEmpty()) return;

        Bitmap have = Thumbnails.get(url);
        if (have != null) {
            cover.setImageBitmap(have);
            return;
        }

        Thumbnails.load(getContext(), http, url, (fetched, picture) -> {
            if (forThis != token || picture == null) return;

            cover.clearColorFilter();
            cover.setImageBitmap(picture);
        });
    }

    // --- importing -----------------------------------------------------------------

    /** The Import button: the best file of the original release. */
    private void importTheGame() {
        Catalogue.Item item = showing;
        if (item == null) return;

        beginImport(item, Pick.forGame(item), false);
    }

    /**
     * The recording, offered in its own right and never as the game.
     *
     * {@link Pick#forGame} will not return one - a recording is somebody
     * playing the game, which as "the game" is a game you cannot play - so this
     * is the only way to one. It lands in {@code Kinds.RECORDINGS} whatever the
     * entry's genre says, and opening it starts playback, because {@code
     * utils_open_file} hands an RZX to {@code rzx_start_playback_from_buffer}.
     */
    private void importTheRecording() {
        Catalogue.Item item = showing;
        if (item == null) return;

        beginImport(item, Pick.recording(item), true);
    }

    /**
     * The other releases, when there is more than one.
     *
     * Two taps for a re-release or a 128K remake, for the people who have a
     * view on that, and no extra screen for the people who do not - which is
     * why this is a list on top of the pane rather than a page of its own.
     */
    private void chooseVersion() {
        Catalogue.Item item = showing;
        if (item == null) return;

        List<Catalogue.Version> versions = item.versions();
        String[] labels = new String[versions.size()];
        for (int at = 0; at < versions.size(); at++) labels[at] = labelOf(versions.get(at));

        new AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.catalogue_versions)
                .setItems(labels, (dialog, which) ->
                        beginImport(item, Pick.forGame(versions.get(which)), false))
                .show();
    }

    /**
     * The whole of one import: the write grant, the download, the details, and
     * what the pane says about each.
     *
     * <b>The grant is checked first and asked for on the spot.</b> {@code
     * Tree.canWrite} reads the persisted permission list, which is
     * authoritative - the alternative, finding out by writing, is a
     * {@code SecurityException} several layers down that surfaces as an import
     * which did nothing and said nothing.
     *
     * <b>{@link Work#alone}, never {@link Work#run}.</b> A download plus a
     * scrape plus its media is measured in seconds at best; one of those in the
     * shared pool holds a lane for the whole of it and starves the short work
     * the rest of the screen is doing.
     *
     * <b>The pane resolves the collaborators.</b> {@code Imports.describe}
     * takes a {@code Provider} and an {@code Http} rather than fetching them,
     * because {@code Scrapers.all} builds real services on a real {@code
     * Http.Real} with no seam - so a {@code describe} that resolved its own
     * would make every test of it a live network call. See {@link #providerFor}
     * for which provider is chosen and why it is never {@code
     * Scrapers.preferred}. A null provider is not a failure here: the file is
     * imported either way and the details are the extra.
     *
     * @param file may be null - a version with nothing openable in it, which
     *             is an ordinary thing to find and says so rather than starting
     *             an import of nothing.
     */
    private void beginImport(Catalogue.Item item, Catalogue.Download file, boolean recording) {
        if (importing) return;

        if (file == null) {
            say(getContext().getString(R.string.catalogue_nothing_to_get));
            return;
        }

        Uri tree = Storage.contentFolder(getContext());
        if (!Tree.canWrite(getContext(), tree)) {
            askForWriteAccess(() -> beginImport(item, file, recording));
            return;
        }

        importing = true;
        chooseButton.setVisibility(View.GONE);
        say(getContext().getString(R.string.catalogue_importing, item.title()));

        int forThis = token;
        Context context = getContext().getApplicationContext();

        Work.alone("import", () -> {
            Imports.Result result;

            try {
                result = recording ? Imports.recording(context, http, item, file)
                                   : Imports.game(context, http, item, file);

                // Only for a file that was actually written. A second import of
                // something already there has already been described, and a
                // scrape is a request against somebody's allowance.
                if (result.failure == null && !result.alreadyThere) {
                    Imports.describe(context, providerFor(context), http, result, item);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "the import of " + item.title() + " went wrong", e);
                result = null;
            }

            Imports.Result answered = result;
            Work.onMain(() -> importFinished(forThis, answered, recording));
        });
    }

    /**
     * The provider whose id {@link Imports#describe} can trust - the one whose
     * {@link Provider#name()} matches this pane's own {@link
     * Catalogue#name()} - or null when this build has no such provider.
     *
     * <b>Not {@code Scrapers.preferred}.</b> {@code catalogue.item(id)} is
     * fetched by <em>this</em> catalogue's id, from <em>this</em> catalogue's
     * service - ZXInfo, here - and {@code Imports.describe} hands that same id
     * straight to {@code Provider#fetch} as an already-matched candidate,
     * which is the whole point of going through the catalogue rather than a
     * name-and-year guess. An id is only certain against the service that
     * issued it: {@code Scrapers.preferred} answers whichever scraper the
     * user chose for ordinary scraping - ScreenScraper by default, since
     * credentials are baked into the build - and handing a ZXInfo id to
     * ScreenScraper is not a lookup, it is a coincidence waiting to happen. At
     * best {@code fetch} refuses it outright (it did, every time, until this
     * was found); at worst two services that both number their entries from 1
     * agree on a number that means two different games, and the import
     * silently gets somebody else's cover - one of this codebase's worst
     * outcomes. So: match by name, and if nothing matches, describe with
     * null rather than guess - {@code Imports.describe} already treats a null
     * provider as "no details", which is a clean, honest outcome, unlike a
     * wrong one.
     *
     * Zxart, the next catalogue this plan adds, is planned to implement both
     * {@link Catalogue} and {@link Provider} under one shared name, which is
     * exactly the shape this match relies on - nothing here needs to change
     * for it.
     */
    private Provider providerFor(Context context) {
        for (Provider provider : Scrapers.all(context)) {
            if (provider.name().equals(catalogue.name())) return provider;
        }
        return null;
    }

    /**
     * What the import came to.
     *
     * <b>The host is told before the token is checked.</b> A file that landed
     * has landed whether or not anybody is still looking at the pane that asked
     * for it, and the listing that has to be refreshed is not this pane's.
     *
     * <b>Already there is not an error.</b> {@code Imports} finds before it
     * creates, so a second import says so and offers the file rather than
     * writing {@code HeadOverHeels (1).tzx} beside the first - which is how a
     * collection acquires four of everything and how somebody comes to think
     * the first import failed.
     */
    private void importFinished(int forThis, Imports.Result result, boolean recording) {
        boolean landed = result != null && result.failure == null && result.documentUri != null;

        if (landed) host.imported();

        if (forThis != token) return;   // another title was tapped meanwhile

        importing = false;

        if (!landed) {
            if (result != null && result.failure != null) {
                Log.w(TAG, "the import failed: " + result.failure.kind, result.failure);
            }
            say(getContext().getString(R.string.catalogue_failed));
            return;
        }

        String name = result.displayName != null ? result.displayName
                                                 : String.valueOf(title.getText());

        say(getContext().getString(result.alreadyThere ? R.string.catalogue_already
                                                      : R.string.catalogue_imported, name));

        // A recording is imported in order to be watched, so it opens itself -
        // that is what the button said it would do. Anything else offers the
        // file, and only when it is a file: a multi-load game arrives as a
        // folder named after the title, and a folder is not something the
        // machine can be handed.
        if (recording) {
            open(result.documentUri);
        } else if (result.displayName != null && Types.openable(result.displayName)) {
            label(importButton, R.string.library_play);
            importButton.setVisibility(View.VISIBLE);
            importButton.setOnClickListener(v -> open(result.documentUri));
        }
    }

    /**
     * Hands a file to the machine: {@code ACTION_VIEW} with the document as the
     * data, exactly as a file manager's hand-over does and as {@code
     * LibraryActivity.openGame} already does - every way of opening a game
     * meets at {@code Media.Host.opened}, and this is one of them.
     */
    private void open(Uri document) {
        Intent intent = new Intent(Intent.ACTION_VIEW, document,
                                   getContext(), EmulatorActivity.class);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // The fast path for the emulator's own panel: known exactly here, so
        // there is nothing for it to resolve for itself.
        String relativePath = Metadata.relativePath(getContext(), document);
        if (relativePath != null) {
            intent.putExtra(EmulatorActivity.EXTRA_LIBRARY_PATH, relativePath);
        }

        try {
            getContext().startActivity(intent);
        } catch (RuntimeException e) {
            // Handing a content:// uri to another activity passes the grant
            // with it, and that throws rather than returning anything once we
            // no longer hold one - see LibraryActivity.openGame, where the same
            // call once crashed the app outright from Recent.
            Log.w(TAG, "no permission left for " + document, e);
            Toast.makeText(getContext(), R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    // --- the folder that has to be writable ----------------------------------------

    /**
     * Says which folder needs the permission, and offers the one tap that
     * grants it.
     *
     * Named, because "Zedex needs permission to add games to a folder" is a
     * question nobody can answer - and it is the folder they already chose, so
     * naming it is also what makes the picker's own starting point make sense.
     * With no folder chosen at all there is nothing to name and nothing to
     * re-grant, so the library's own words for that are used instead.
     */
    private void askForWriteAccess(Runnable then) {
        pending = then;

        String folder = Storage.describe(
                preferences().getString(Storage.KEY_CONTENT_TREE, null));

        say(folder == null ? getContext().getString(R.string.library_no_folder)
                           : getContext().getString(R.string.catalogue_needs_write, folder));

        chooseButton.setVisibility(View.VISIBLE);
    }

    /**
     * The picker, opened <b>at the folder they already use</b>.
     *
     * {@code EXTRA_INITIAL_URI} is what makes this one tap rather than a walk
     * back down somebody's storage to a folder they chose months ago - the same
     * trick {@code StartPanel.importRomsFolder} and the ES-DE picker both use,
     * and verified on an emulator there.
     */
    private void chooseWritableFolder() {
        Activity activity = activityOf();
        if (activity == null) {
            Log.w(TAG, "no activity to ask for a folder with");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

        Uri start = Storage.contentFolder(getContext());
        if (start != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, start);

        try {
            activity.startActivityForResult(intent, REQUEST_WRITABLE_TREE);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(getContext(), R.string.open_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** The activity this pane is inside, or null - a view's context is not
     *  always one, and {@code startActivityForResult} is the one thing here
     *  that needs it to be. */
    private Activity activityOf() {
        Context at = getContext();

        while (at instanceof android.content.ContextWrapper) {
            if (at instanceof Activity) return (Activity) at;
            at = ((android.content.ContextWrapper) at).getBaseContext();
        }
        return null;
    }

    // --- small things --------------------------------------------------------------

    /** Year, publisher and the catalogue's own word for the kind, skipping
     *  whichever is unknown - the same joining and the same separator the rows
     *  and {@code DetailPane} both use. */
    private static String factsLine(Catalogue.Item item) {
        StringBuilder line = new StringBuilder();

        appendFact(line, item.year());
        appendFact(line, item.publisher());
        appendFact(line, item.kind());

        return line.toString();
    }

    private static void appendFact(StringBuilder line, String fact) {
        if (fact == null || fact.isEmpty()) return;
        if (line.length() > 0) line.append(" · ");
        line.append(fact);
    }

    /** What tells two releases apart on a list: the catalogue's own label for
     *  it, its year, or - for the original, which is often neither - the year
     *  alone. Never empty, since an unlabelled row cannot be chosen. */
    private String labelOf(Catalogue.Version version) {
        String label = version.label();
        String year = version.year();

        boolean hasLabel = label != null && !label.isEmpty();
        boolean hasYear = year != null && !year.isEmpty();

        if (hasLabel && hasYear) return label + " (" + year + ")";
        if (hasLabel) return label;
        if (hasYear) return year;

        return String.valueOf(title.getText());
    }

    /** The one line about what is happening, or nothing at all - gone rather
     *  than empty, so it costs no blank line when there is nothing to say. */
    private void say(String words) {
        status.setText(words == null ? "" : words);
        status.setVisibility(words == null ? View.GONE : View.VISIBLE);
    }

    private Button button(int label) {
        Button made = new Button(getContext());
        made.setVisibility(View.GONE);
        label(made, label);
        return made;
    }

    /**
     * The label and the name together, always - a button whose words say Play
     * and whose description still says Import is one thing to a reader and
     * another to a screen reader.
     *
     * Only {@link #importButton} ever changes, and only between two fixed
     * strings at two moments a person caused; nothing here changes
     * continuously, which is the accessibility rule that matters - a
     * description that moves keeps the accessibility tree from ever settling.
     */
    private void label(Button button, int label) {
        button.setText(label);
        button.setContentDescription(getContext().getString(label));
    }

    private SharedPreferences preferences() {
        return getContext().getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                             LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int pixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
