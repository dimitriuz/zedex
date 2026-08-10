package dev.ldlab.zedex.menu;

import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.Setup;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.machine.Suggested;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.view.Palette;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Offering what a scraped record says about how to run a game.
 *
 * {@code Suggested} works out which of Fuse's machines and joysticks a record
 * implies; this asks whether to use them. It offers rather than acts, because
 * both halves of the record are frequently ambiguous - a game listed for
 * "48K/128K" runs on either, and one listing three joysticks listens to all
 * three - and because changing the machine throws away whatever is loaded.
 *
 * <b>Apply reopens the game.</b> Switching machines resets Fuse, so by the
 * time this is asked the file that has just been loaded would be lost. Rather
 * than pretend otherwise, Apply changes the machine, waits for the change to
 * settle on the emulation thread, and opens the same file again. It reads as
 * the app doing what it was told; the alternative was a game that silently
 * vanished.
 *
 * Asked once per game. The answer - including a refusal - is remembered in
 * {@link Setup}, which is a file of the user's own decisions rather than part
 * of the scraped record, so a re-scrape cannot quietly undo it.
 */
public final class SetupUi {

    private static final String TAG = "Zedex";

    /**
     * How long to let a machine change settle before reopening.
     *
     * The change is queued and applied on the emulation thread at the end of a
     * frame, so reopening immediately would load the file into the machine
     * that is on its way out. {@code Machine.select} waits the same way for
     * the same reason, and this matches it deliberately rather than inventing
     * a second number.
     */
    private static final long SETTLE_MS = 600;

    /** What this needs of the screen it belongs to. Three methods, and it
     *  stays at three - see CLAUDE.md on collaborator interfaces. */
    public interface Host {
        /** Open the game that is loaded, again, exactly as it was opened. */
        void reopenCurrentGame();

        /** Say something brief. */
        void note(int message, Object... arguments);
    }

    private final Activity activity;
    private final Host host;
    private final SharedPreferences preferences;

    public SetupUi(Activity activity, Host host, SharedPreferences preferences) {
        this.activity = activity;
        this.host = host;
        this.preferences = preferences;
    }

    /**
     * Offer whatever is known about this game, if anything, and if nobody has
     * already answered.
     *
     * Silent in every other case, which is most of them: a collection is
     * mostly unscraped, most records say nothing about a machine, and a game
     * that has been answered about once should never ask again.
     */
    public void offer(String path) {
        if (path == null) return;

        Setup.Answer remembered = Setup.remembered(activity, path);
        if (remembered != null) {
            if (remembered.anything()) apply(remembered, false);
            return;
        }

        Meta meta = Metadata.forPath(activity, path);
        String[] machineIds = FuseNative.machineIds();
        String[] joystickNames = FuseNative.joystickTypeNames();

        if (!Suggested.anything(meta, machineIds, joystickNames)) return;

        ask(path, meta, machineIds, joystickNames);
    }

    // --- the question -------------------------------------------------------------

    private void ask(String path, Meta meta, String[] machineIds, String[] joystickNames) {
        List<Integer> machines = Suggested.machines(meta.machine, machineIds);
        List<Integer> joysticks = Suggested.joysticks(meta.inputs, joystickNames);

        int keyboard = Suggested.keyboard(meta) ? keyboardIndex(joystickNames) : -1;
        if (keyboard >= 0 && !joysticks.contains(keyboard)) joysticks.add(keyboard);

        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(pixels(20), pixels(8), pixels(20), 0);

        RadioGroup machineChoice = machines.isEmpty() ? null
                : choice(page, R.string.suggest_machine, names(machineIds, machines));
        RadioGroup joystickChoice = joysticks.isEmpty() ? null
                : choice(page, R.string.suggest_control, names(joystickNames, joysticks));

        CheckBox remember = new CheckBox(activity);
        remember.setText(R.string.suggest_remember);
        remember.setTextColor(Palette.TEXT);
        remember.setChecked(true);
        remember.setMinHeight(pixels(48));
        page.addView(remember);

        ScrollView scroller = new ScrollView(activity);
        scroller.addView(page);

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(meta.name == null ? activity.getString(R.string.suggest_title)
                                            : meta.name)
                .setMessage(said(meta))
                .setView(scroller)
                .setPositiveButton(R.string.suggest_apply, (dialog, which) -> {
                    Setup.Answer answer = new Setup.Answer(false,
                            picked(machineChoice, machineIds, machines),
                            picked(joystickChoice, joystickNames, joysticks));

                    if (remember.isChecked()) Setup.remember(activity, path, answer);
                    apply(answer, true);
                })
                // Skip is an answer too, and remembering it is the difference
                // between declining once and declining every time.
                .setNegativeButton(R.string.suggest_skip, (dialog, which) -> {
                    if (remember.isChecked()) {
                        Setup.remember(activity, path, new Setup.Answer(true, null, null));
                    }
                })
                .show();
    }

    /** What the record actually says, in its own words rather than ours -
     *  somebody deciding deserves to see what the decision is based on. */
    private String said(Meta meta) {
        List<String> parts = new ArrayList<>();

        if (meta.machine != null) parts.add(meta.machine);
        if (!meta.inputs.isEmpty()) parts.add(String.join(", ", meta.inputs));

        return activity.getString(R.string.suggest_says, String.join(" · ", parts));
    }

    private RadioGroup choice(LinearLayout into, int heading, List<String> labels) {
        TextView label = new TextView(activity);
        label.setText(heading);
        label.setTextColor(Palette.MUTED);
        label.setTextSize(12);
        into.addView(label);

        RadioGroup group = new RadioGroup(activity);

        for (String text : labels) {
            RadioButton button = new RadioButton(activity);
            button.setId(View.generateViewId());
            button.setText(text);
            button.setTextColor(Palette.TEXT);
            button.setMinHeight(pixels(48));
            group.addView(button);
        }

        // The first is the record's own first answer - for a machine that is
        // what the game was written for, whatever else it also runs on.
        group.check(group.getChildAt(0).getId());
        into.addView(group);

        return group;
    }

    private static List<String> names(String[] all, List<Integer> chosen) {
        List<String> labels = new ArrayList<>();
        for (int index : chosen) labels.add(all[index]);
        return labels;
    }

    /** What the group is set to, as the stable name rather than an index. */
    private static String picked(RadioGroup group, String[] all, List<Integer> offered) {
        if (group == null) return null;

        int at = group.indexOfChild(group.findViewById(group.getCheckedRadioButtonId()));
        if (at < 0 || at >= offered.size()) return null;

        return all[offered.get(at)];
    }

    private static int keyboardIndex(String[] joystickNames) {
        for (int at = 0; at < joystickNames.length; at++) {
            if (joystickNames[at].toLowerCase(java.util.Locale.US).startsWith("keyboard")) {
                return at;
            }
        }
        return -1;
    }

    // --- doing it -------------------------------------------------------------------

    /**
     * Applies an answer: the joystick at once, the machine with a reopen
     * behind it.
     *
     * The joystick first and unconditionally, because setting it changes
     * nothing else - where a machine change resets everything, which is why
     * the file has to be opened again afterwards.
     *
     * @param announce false when this is a remembered answer being replayed;
     *                 a game that was answered about weeks ago should just
     *                 work rather than explaining itself every launch
     */
    private void apply(Setup.Answer answer, boolean announce) {
        if (answer.joystick != null) applyJoystick(answer.joystick);

        if (answer.machine == null) {
            if (announce) host.note(R.string.suggest_applied);
            return;
        }

        int index = indexOf(FuseNative.machineIds(), answer.machine);
        if (index < 0) {
            // A remembered answer naming a machine this build of Fuse does not
            // have. Nothing to do about it, and nothing worth alarming
            // somebody with either.
            Log.w(TAG, "no machine called " + answer.machine + " any more");
            return;
        }

        if (FuseNative.currentMachine() == index) {
            // Already the right machine, so there is nothing to reset and
            // nothing to reopen.
            if (announce) host.note(R.string.suggest_applied);
            return;
        }

        FuseNative.selectMachine(index);

        activity.getWindow().getDecorView().postDelayed(() -> {
            host.reopenCurrentGame();
            if (announce) host.note(R.string.suggest_applied);
        }, SETTLE_MS);
    }

    /**
     * The interface, into Fuse and into the setting.
     *
     * Both, because either alone drifts: Fuse is what the running machine
     * reads, and the preference is what the next launch and the on-screen pad
     * read. {@code joystickType} is written with {@code putInt} everywhere
     * else - see CLAUDE.md on a preference's type being whatever wrote it.
     */
    private void applyJoystick(String name) {
        int index = indexOf(FuseNative.joystickTypeNames(), name);
        if (index < 0) {
            Log.w(TAG, "no joystick interface called " + name + " any more");
            return;
        }

        FuseNative.setJoystickType(index);
        preferences.edit().putInt(Prefs.KEY_JOYSTICK_TYPE, index).apply();
    }

    private static int indexOf(String[] values, String wanted) {
        if (values == null || wanted == null) return -1;

        for (int at = 0; at < values.length; at++) {
            if (wanted.equals(values[at])) return at;
        }
        return -1;
    }

    private int pixels(int dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }
}
