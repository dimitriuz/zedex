package dev.ldlab.zedex.menu;

import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.cheats.PokeDatabase;
import dev.ldlab.zedex.cheats.Pokes;
import dev.ldlab.zedex.storage.States;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.view.MenuDrawer;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.widget.EditText;

import java.util.ArrayList;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * The cheats: the ones that shipped with the app, and the ones you keep.
 *
 * Two collections that read alike and are nothing alike behind the page.
 * {@link PokeDatabase} is three and a half thousand games in a file, found by the
 * fingerprint of what is loaded — so the cheats for this game are simply there.
 * {@link Pokes} is a few addresses somebody typed, in the preferences.
 *
 * A stored poke is a thing to press: nothing is applied by being on the list.
 */
public final class PokesUi {

    private static final String TAG = "Zedex";

    /** How many names a page can offer before it is a list to scroll. */
    private static final int RESULTS = 30;

    /** What the machine's side of the app has to lend this one. */
    public interface Host {
        /** Says an action happened; Fuse itself is silent about most of them. */
        void note(int message, Object... arguments);

        /** The same, for a string that is already formatted - which a plural
         *  has to be, since choosing the form is what resolves it. */
        void noteText(String message);

        /** The sheet, which every page here is part of. */
        MenuDrawer sheet();

        /** The md5 of what is loaded, which is how its cheats are found. */
        byte[] fingerprint();
    }

    private final Activity activity;
    private final SharedPreferences preferences;
    private final Host host;

    /** The cheat database, opened once and kept; null if it will not open. */
    private PokeDatabase database;

    /**
     * The game that is loaded, by the store's own key, or null.
     *
     * Set rather than asked for: {@code Host} is four methods and a fifth
     * would be the point at which the seam is wrong - see CLAUDE.md - and the
     * screen already knows this the moment a game opens. Only the scraped
     * {@code .pok} needs it; the bundled database is found by hash and does
     * not care what the file is called or where it lives.
     */
    private String libraryPath;

    /** Told by the screen when a game opens, and told null when one fails
     *  to - a stale path would offer the last game's cheats for this one. */
    public void forGame(String path) {
        this.libraryPath = path;
    }

    public PokesUi(Activity activity, SharedPreferences preferences, Host host) {
        this.activity = activity;
        this.preferences = preferences;
        this.host = host;
    }

    /**
     * Pokes: one to try now, and the ones worth keeping.
     *
     * Long press removes a stored one, because a row whose tap means "use this"
     * cannot also mean "throw this away".
     */
    public void fill(MenuDrawer sheet) {
        // What came with the app, for whatever is loaded. First, because it is
        // the answer to the question anyone opening this page is asking.
        PokeDatabase.Game found = foundGame();

        if (found != null) {
            sheet.addSection(text(R.string.poke_for_game, found.name));
            fillTrainers(sheet, found.trainers());
            sheet.addRule();
        } else {
            // And what a scrape found, when the database knows nothing.
            //
            // <b>Second, not first.</b> The bundled database matches on the
            // md5 of the file that is actually loaded, which is exact; a
            // scraped .pok belongs to whichever entry the scrape settled on,
            // which for a name match is a good guess and no more. Where both
            // exist they are usually the same cheats from the same source
            // anyway - this is here to top up the two thirds of a collection
            // the database has never heard of.
            List<PokeDatabase.Trainer> scraped = scrapedTrainers();

            if (!scraped.isEmpty()) {
                sheet.addSection(text(R.string.poke_scraped));
                fillTrainers(sheet, scraped);
                sheet.addRule();
            }
        }

        sheet.addSubmenu(text(R.string.poke_search), R.drawable.ic_poke, search());
        sheet.addItem(text(R.string.poke_tipshop), R.drawable.ic_info,
                      this::openTipshop);

        sheet.addRule();
        sheet.addSubmenu(text(R.string.poke_quick), R.drawable.ic_poke, quick());
        sheet.addSubmenu(text(R.string.poke_add), R.drawable.ic_plus, add());

        List<Pokes.Poke> pokes = Pokes.all(preferences);

        sheet.addRule();
        sheet.addSection(text(R.string.poke_stored));

        if (pokes.isEmpty()) {
            sheet.addNote(text(R.string.poke_none));
            return;
        }

        for (int i = 0; i < pokes.size(); i++) {
            Pokes.Poke poke = pokes.get(i);
            int index = i;

            sheet.addItem(poke.name + "\n" + poke.numbers(), R.drawable.ic_poke,
                          () -> apply(poke), R.drawable.ic_trash,
                          text(R.string.poke_forget_action, poke.name),
                          () -> host.sheet().go(
                                  text(R.string.poke_forget_ask, poke.name),
                                  forget(index, poke)));
        }

        sheet.addNote(text(R.string.poke_hint));
    }

    private PokeDatabase database() {
        if (database == null) database = PokeDatabase.open(activity);
        return database;
    }

    /** The game whose file is loaded, if the database knows its hash. */
    private PokeDatabase.Game foundGame() {
        PokeDatabase open = database();
        byte[] fingerprint = host.fingerprint();

        return open == null || fingerprint == null
                ? null : open.forHash(fingerprint);
    }

    // --- the database --------------------------------------------------------

    /**
     * One row per cheat. Tapping it pokes every byte the cheat is made of - most
     * are one, some are dozens - and a cheat that wants a number asks for it
     * first, which is what a value of 256 means in the format.
     */
    /**
     * The cheats a scrape fetched for the game that is loaded.
     *
     * Read when the page is built rather than kept: it is a few hundred bytes
     * off the disk, once, at the moment somebody asks - and reading it then is
     * what lets a scrape that has just finished show up without anything
     * having to be told about it.
     */
    private List<PokeDatabase.Trainer> scrapedTrainers() {
        File file = Artwork.pokes(activity, libraryPath);
        if (file == null) return java.util.Collections.emptyList();

        try {
            return PokeDatabase.parse(new String(Files.readAllBytes(file.toPath()),
                                                 StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + file, e);
            return java.util.Collections.emptyList();
        }
    }

    private void fillTrainers(MenuDrawer sheet, List<PokeDatabase.Trainer> trainers) {
        if (trainers.isEmpty()) {
            sheet.addNote(text(R.string.poke_none_for_game));
            return;
        }

        for (PokeDatabase.Trainer trainer : trainers) {
            String label = trainer.name.isEmpty()
                    ? text(R.string.poke_unnamed) : trainer.name;

            sheet.addItem(trainer.asks() ? label + "…" : label, R.drawable.ic_poke,
                          () -> applyTrainer(trainer));
        }
    }

    private void applyTrainer(PokeDatabase.Trainer trainer) {
        if (trainer.asks()) {
            askTrainerValue(trainer);
            return;
        }

        pokeTrainer(trainer, -1);
    }

    /**
     * The value a cheat left to the player: how many lives, usually.
     *
     * The format writes 256 where the number goes, and every poke of the cheat
     * marked that way gets the same answer - which is what the tools that came
     * before do, and what a cheat like "lives (0-255)" means.
     */
    private void askTrainerValue(PokeDatabase.Trainer trainer) {
        host.sheet().go(trainer.name, page -> {
            page.addNote(text(R.string.poke_asks));

            EditText input = page.addField(text(R.string.poke_value), "", 0);

            page.addItem(text(R.string.poke_apply), R.drawable.ic_poke, () -> {
                int value = Pokes.number(input.getText().toString(), 0xff);

                if (value < 0) {
                    host.note(R.string.poke_bad);
                    return;
                }

                pokeTrainer(trainer, value);
            });
        });
    }

    private void pokeTrainer(PokeDatabase.Trainer trainer, int answer) {
        int done = 0;
        int skipped = 0;

        for (PokeDatabase.Poke poke : trainer.pokes) {
            // A poke into a numbered RAM bank needs paging this app cannot do.
            // Twenty two of them in the whole collection; saying so is better
            // than writing the byte into whatever happens to be paged in.
            if (!poke.plain()) {
                skipped++;
                continue;
            }

            FuseNative.poke(poke.address, poke.asks() ? answer : poke.value);
            done++;
        }

        if (skipped > 0) {
            host.note(R.string.poke_partly, trainer.name, done, skipped);
        } else {
            host.noteText(counted(R.plurals.poke_trainer_done, done,
                                  trainer.name, done));
        }
    }

    /** The database by name, for a state, a new release, or an odd dump. */
    private MenuDrawer.Page search() {
        return page -> {
            EditText input = page.addField(text(R.string.poke_search_hint),
                                           searchableName(loadedName()), 0);

            input.selectAll();

            page.addItem(text(R.string.poke_search_go), R.drawable.ic_poke,
                         () -> showResults(input.getText().toString()));
        };
    }

    private void showResults(String query) {
        PokeDatabase open = database();
        List<PokeDatabase.Game> games = open == null
                ? new ArrayList<>() : open.search(query, RESULTS);

        if (games.isEmpty()) {
            host.note(R.string.poke_search_none, query.trim());
            return;
        }

        host.sheet().go(counted(R.plurals.poke_search_found, games.size(),
                                games.size()), page -> {
            for (PokeDatabase.Game game : games) {
                page.addItem(game.name, R.drawable.ic_poke, () -> showGame(game));
            }
        });
    }

    /** A page of one game's cheats, reached from a search. */
    private void showGame(PokeDatabase.Game game) {
        host.sheet().go(game.name, sheet -> {
            fillTrainers(sheet, game.trainers());
            sheet.addNote(text(R.string.poke_from_search, game.name));
        });
    }

    /**
     * A file's name reduced to something worth searching for.
     *
     * Files are named the way TOSEC names them - "Sim City 48K (1990)(Infogrames)"
     * - and none of that trailing apparatus is in a game's title, so a search for
     * the lot finds nothing. The bracket is where the title stops, and the machine
     * size before it goes too. Selected rather than merely filled in, so a name
     * that is still wrong is one keystroke from gone.
     */
    private static String searchableName(String file) {
        if (file == null) return "";

        String name = file.split("[(\\[]")[0];
        name = name.replaceAll("(?i)\\s+(16|48|128)K([-/](16|48|128)K)?\\s*$", "");

        return name.trim();
    }

    /**
     * The Tipshop, which is where these cheats come from and where the ones this
     * database has not got will be. It invites linking to its pages, so a search
     * for whatever is loaded is the neighbourly way to use it.
     */
    private void openTipshop() {
        // The database's own title where the file was recognised, and the file's
        // name reduced to something searchable where it was not: the site turns
        // what it is given into wildcards, so "Sim City 48K (1990)(Infogrames)"
        // goes looking for %sim%city%48k%1990infogrames% and finds nothing.
        PokeDatabase.Game found = foundGame();
        String name = found != null ? found.name : searchableName(loadedName());

        String url = "https://www.the-tipshop.co.uk/";
        if (name != null && !name.isEmpty()) {
            url += "cgi-bin/search.pl?name=" + Uri.encode(name)
                 + "&searchtype=poke&checkalias=y";
        }

        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            host.note(R.string.poke_no_browser);
        }
    }

    // --- pokes of your own ---------------------------------------------------

    private void apply(Pokes.Poke poke) {
        FuseNative.poke(poke.address, poke.value);
        host.note(R.string.poke_done, poke.numbers());
    }

    /** Two numbers, applied and forgotten: for a poke being tried out. */
    private MenuDrawer.Page quick() {
        return page -> {
            page.addNote(text(R.string.poke_explain));

            EditText address = page.addField(text(R.string.poke_address), "", 0);
            EditText value = page.addField(text(R.string.poke_value), "", 0);

            page.addItem(text(R.string.poke_apply), R.drawable.ic_poke, () -> {
                int where = Pokes.number(address.getText().toString(), 0xffff);
                int what = Pokes.number(value.getText().toString(), 0xff);

                if (where < 0 || what < 0) {
                    host.note(R.string.poke_bad);
                    return;
                }

                apply(new Pokes.Poke("", where, what));
            });
        };
    }

    /** The same two numbers and a name, kept for next time. */
    private MenuDrawer.Page add() {
        return page -> {
            EditText name = page.addField(text(R.string.poke_name), loadedName(), 0);
            EditText address = page.addField(text(R.string.poke_address), "", 0);
            EditText value = page.addField(text(R.string.poke_value), "", 0);

            page.addItem(text(R.string.poke_add), R.drawable.ic_plus, () -> {
                int where = Pokes.number(address.getText().toString(), 0xffff);
                int what = Pokes.number(value.getText().toString(), 0xff);

                if (where < 0 || what < 0) {
                    host.note(R.string.poke_bad);
                    return;
                }

                String called = Storage.sanitise(name.getText().toString());
                if (called.isEmpty()) called = text(R.string.poke_unnamed);

                Pokes.add(preferences, called, where, what);
                host.note(R.string.poke_stored_one, called);
            });
        };
    }

    /**
     * Asking before something cannot be undone, as a page rather than a dialog.
     *
     * A dialog is the activity's window and opens on the machine's screen; the
     * question was asked on the panel and the answer would have been over
     * there. A page is part of the sheet, so it appears where the sheet is -
     * and Back is the way out of it, which is what Cancel was.
     */
    private MenuDrawer.Page forget(int index, Pokes.Poke poke) {
        return page -> {
            page.addNote(poke.numbers());
            page.addItem(text(R.string.poke_forget), R.drawable.ic_trash, () -> {
                Pokes.remove(preferences, index);
                host.note(R.string.poke_forgotten, poke.name);
            });
        };
    }

    /** What is loaded, which names a new poke and seeds a search. */
    private String loadedName() {
        return preferences.getString(States.KEY_MEDIA_NAME, "");
    }

    private String text(int message, Object... arguments) {
        return activity.getString(message, arguments);
    }

    /**
     * A counted string, in whichever form the language wants for that number.
     *
     * {@code count} is passed twice on purpose: once to choose the form, and
     * again as the argument that fills the %d in it. They are separate ideas -
     * Russian picks its form from the last digit, and the number printed is
     * still the whole count.
     */
    private String counted(int plural, int count, Object... arguments) {
        return activity.getResources().getQuantityString(plural, count, arguments);
    }
}
