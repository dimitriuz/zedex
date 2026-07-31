package dev.ldlab.zedex.menu;

import dev.ldlab.zedex.FuseNative;
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
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;

/**
 * The cheats: the ones that came with the app, and the ones you keep.
 *
 * Two collections that look the same on the page and are nothing alike behind
 * it. {@link PokeDatabase} is three and a half thousand games shipped as a file
 * and found by the fingerprint of what is loaded — the app knows what game this
 * is, so the cheats for it are simply there. {@link Pokes} is a handful of
 * addresses somebody typed, kept in the preferences, and belonging to whoever
 * typed them.
 *
 * A stored poke is a thing to press: nothing is applied by being on the list, so
 * loading a game and pressing it is the whole flow, and the same poke survives a
 * reset without being typed again.
 *
 * Every page here belongs to the ☰ sheet rather than the quick bar. That is the
 * line the two menus are drawn along: the bar is for what is reached for, and a
 * cheat is read, chosen, sometimes typed and often searched for.
 */
public final class PokesUi {

    /** How many names a page can offer before it is a list to scroll. */
    private static final int RESULTS = 30;

    /** What the machine's side of the app has to lend this one. */
    public interface Host {
        /** Says an action happened; Fuse itself is silent about most of them. */
        void note(int message, Object... arguments);

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
            fillTrainers(sheet, found);
            sheet.addRule();
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
    private void fillTrainers(MenuDrawer sheet, PokeDatabase.Game game) {
        List<PokeDatabase.Trainer> trainers = game.trainers();

        if (trainers.isEmpty()) {
            sheet.addNote(text(R.string.poke_none_for_game));
            return;
        }

        for (PokeDatabase.Trainer trainer : trainers) {
            String label = trainer.name.isEmpty()
                    ? text(R.string.poke_unnamed) : trainer.name;

            sheet.addItem(trainer.asks() ? label + "…" : label, R.drawable.ic_poke,
                          () -> applyTrainer(game, trainer));
        }
    }

    private void applyTrainer(PokeDatabase.Game game, PokeDatabase.Trainer trainer) {
        if (trainer.asks()) {
            askTrainerValue(game, trainer);
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
    private void askTrainerValue(PokeDatabase.Game game,
                                 PokeDatabase.Trainer trainer) {
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
            host.note(R.string.poke_trainer_done, trainer.name, done);
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

        host.sheet().go(text(R.string.poke_search_found, games.size()), page -> {
            for (PokeDatabase.Game game : games) {
                page.addItem(game.name, R.drawable.ic_poke, () -> showGame(game));
            }
        });
    }

    /** A page of one game's cheats, reached from a search. */
    private void showGame(PokeDatabase.Game game) {
        host.sheet().go(game.name, sheet -> {
            fillTrainers(sheet, game);
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
}
