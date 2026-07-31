package dev.ldlab.zedex.menu;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.screen.Panels;
import dev.ldlab.zedex.screen.StatesActivity;
import dev.ldlab.zedex.storage.States;
import dev.ldlab.zedex.view.Rows;
import android.app.Activity;
import android.content.SharedPreferences;

/**
 * Saving and loading, both ways round.
 *
 * The list is a screen of its own — {@link StatesActivity} — because a state is
 * a picture and a picture wants more room than a three-hundred-dp sheet. What is
 * here is the way in to it, and the one state that is written without asking
 * anything.
 *
 * <b>One quick save per game, named after it: <i>Tujad Quick</i>.</b> A single
 * slot was one game's save until the next game overwrote it, which is the wrong
 * way round for the thing meant to be pressed without thinking. With nothing
 * loaded there is no name to borrow and it is plain <i>Quick</i>.
 */
public final class StatesUi {

    /** The suffix a quick state's name carries. */
    private static final String QUICK = "Quick";

    /** What the machine's side of the app has to lend this one. */
    public interface Host {
        void note(int message, Object... arguments);

        /** Opens the list where the controls are; see {@link Panels}. */
        void openList(boolean saving);
    }

    private final Activity activity;
    private final SharedPreferences preferences;
    private final Host host;

    public StatesUi(Activity activity, SharedPreferences preferences, Host host) {
        this.activity = activity;
        this.preferences = preferences;
        this.host = host;
    }

    /**
     * A group of its own on the bar. The two rows used to sit under the folder
     * icon with the picker and the recent files, and the quick pair — the two
     * most reached-for things in the app — were on a controller hotkey and
     * nowhere else, so anyone without a controller could not reach them.
     */
    public void fill(Rows rows) {
        rows.item(R.drawable.ic_save, text(R.string.menu_save_state),
                  () -> host.openList(true));
        rows.item(R.drawable.ic_load, text(R.string.menu_load_state),
                  () -> host.openList(false));

        // Named after what is running rather than after the state: the state is
        // called "Tujad Quick", and "Quick save - Tujad Quick" says quick twice.
        String subject = subject();

        rows.rule();
        rows.item(R.drawable.ic_save,
                  subject == null ? text(R.string.hotkey_quick_save)
                                  : text(R.string.quick_save, subject),
                  this::quickSave);
        rows.item(R.drawable.ic_load,
                  subject == null ? text(R.string.hotkey_quick_load)
                                  : text(R.string.quick_load, subject),
                  this::quickLoad);
    }

    /** The list, for the two rows and the two controller hotkeys alike. */
    public void openList(boolean saving) {
        host.openList(saving);
    }

    public void quickSave() {
        String name = quickName();

        if (!States.save(activity, preferences, name)) {
            host.note(R.string.state_failed);
            return;
        }

        host.note(R.string.state_saved, name);
    }

    public void quickLoad() {
        String name = quickName();

        for (States.Saved state : States.all(activity)) {
            if (state.name.equals(name)) {
                // The media name is left as it is: what is loaded is still the
                // game, and calling it "Tujad Quick" from here would name the
                // next save after the save rather than after the game.
                States.load(state);
                host.note(R.string.state_loaded, state.name);
                return;
            }
        }

        host.note(R.string.hotkey_no_quick_save);
    }

    private String quickName() {
        String media = loaded();

        if (media == null || media.isEmpty()) return QUICK;

        // Loading a state makes it the media name, so a quick load followed by
        // a quick save must not end up at "Tujad Quick Quick".
        if (media.equals(QUICK) || media.endsWith(" " + QUICK)) return media;

        return media + " " + QUICK;
    }

    /** What the rows are named after, or null when nothing is loaded. */
    private String subject() {
        String media = loaded();

        if (media == null || media.isEmpty() || media.equals(QUICK)) return null;

        return media.endsWith(" " + QUICK)
                ? media.substring(0, media.length() - QUICK.length() - 1)
                : media;
    }

    private String loaded() {
        return preferences.getString(States.KEY_MEDIA_NAME, null);
    }

    private String text(int message, Object... arguments) {
        return activity.getString(message, arguments);
    }
}
