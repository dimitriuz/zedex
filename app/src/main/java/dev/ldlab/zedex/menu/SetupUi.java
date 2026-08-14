package dev.ldlab.zedex.menu;

import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.input.ControlProfiles;
import dev.ldlab.zedex.input.Controls;
import dev.ldlab.zedex.input.Keymap;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.Setup;
import dev.ldlab.zedex.library.meta.Meta;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.machine.Suggested;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.view.Palette;

import android.app.Activity;
import android.app.AlertDialog;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * How long to keep waiting for Fuse to start, and how often to look.
     *
     * The same numbers {@code Machine.watchForFailure} waits with, asking the
     * same question of the same thread: they are one machine starting, and two
     * different answers to how long that takes would only ever be wrong in
     * different directions.
     */
    private static final long START_TIMEOUT_MS = 6000;
    private static final long START_POLL_MS = 500;

    /** What this needs of the screen it belongs to. Three methods, and it
     *  stays at three - see CLAUDE.md on collaborator interfaces. */
    public interface Host {
        /** Open the game that is loaded, again, exactly as it was opened. */
        void reopenCurrentGame();

        /** Say something brief. */
        void note(int message, Object... arguments);

        /**
         * Put the pad on this interface - a Fuse joystick index, or {@code
         * Controls.JOYSTICK_KEYBOARD} - sending {@code layout}'s keys if it
         * is the keyboard.
         *
         * Asked of the screen rather than done here, because choosing an
         * interface is three things at once, installing a key profile is a
         * fourth, and {@code ControlsUi} already owns all four. Doing some of
         * them here is how the dialog would come to set a joystick the
         * on-screen pad had not been told about.
         *
         * @param layout null for every choice but the keyboard's
         */
        void chooseControl(int type, ControlProfiles.Profile layout);
    }

    private final Activity activity;
    private final Host host;

    /**
     * The games already asked about since the app started.
     *
     * Applying a machine reopens the game, and an open is what asks the
     * question - so without this, an answer that was not remembered asks it
     * again the moment it is acted on, over and over. Not a substitute for
     * {@link Setup}, which is what makes an answer outlive the session; this
     * only stops one session asking twice.
     */
    private final Set<String> asked = new HashSet<>();

    /**
     * The game a reopen of <em>ours</em> is on its way for, and only until it
     * arrives.
     *
     * {@link #asked} covers the same failure for the branch that asks, and the
     * remembered branch above it had nothing - so an answer that Fuse will not
     * keep was applied, reopened, read again, applied again, for ever. That is
     * what a TR-DOS disk remembered as a 128K did: {@code utils.c} selects a
     * machine of its own for one, this put the remembered one back 600ms
     * later, and the two took turns about twice a second until the app was
     * killed. Every open counted as a play, too, so the count ran away with it.
     *
     * {@link Suggested} is where that particular disagreement is settled, and
     * this is here so the next one cannot spin: an answer applied once is
     * applied once, whatever the emulator then does with the machine.
     *
     * Cleared by the very next {@code consider} whichever game it is for,
     * rather than only by the one expected - a reopen that never lands
     * (the file has gone, the activity is going) would otherwise leave this
     * set and swallow a later open that deserved an answer.
     */
    private String reopening;

    public SetupUi(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    /**
     * The next open of this game is somebody putting it back, not a new
     * question - so leave the answer alone.
     *
     * For the machine chooser, which reopens the game on the machine it has
     * just changed to. Without this the remembered answer would be replayed
     * on the way back and put the old machine straight back, which is a hand
     * choice being undone by a stored one within the second - the same
     * argument as {@link #reopening}, from the other side.
     */
    public void notAskingAbout(String path) {
        reopening = path;
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

        whenFuseIsRunning(0, () -> consider(path));
    }

    /**
     * Waits for Fuse to be up, because none of this can be answered before it
     * is.
     *
     * <b>{@code machineIds()} is empty until the emulation thread has run a
     * frame</b> - the list is published from the pump, not built at load time
     * - so asking before there is a machine produced a dialog with no Machine
     * section for a record that plainly named one, which read as the mapping
     * table being wrong rather than as the question being asked too early.
     * {@code joystickTypeNames()} is a plain table and answers straight away,
     * which is exactly why only half the dialog looked broken.
     *
     * The question is asked after the file has been opened now, and staging a
     * document reliably takes longer than starting Fuse - so in practice there
     * is already a machine by the time this runs, and removing this wait does
     * not reproduce the fault. It stays because the race is real and cheap to
     * close: Fuse is not started until the surface exists, and a small file
     * staged out of the cache need not wait for a layout pass.
     *
     * Gives up after the same wait {@code Machine} allows a start, silently:
     * Fuse not starting at all has its own report on screen, and a second one
     * about a scraped record helps nobody.
     */
    private void whenFuseIsRunning(long waited, Runnable then) {
        if (FuseNative.machineIds().length > 0) {
            then.run();
            return;
        }

        if (waited >= START_TIMEOUT_MS) {
            Log.w(TAG, "no machine after " + waited + "ms; nothing to suggest against");
            return;
        }

        activity.getWindow().getDecorView().postDelayed(
                () -> whenFuseIsRunning(waited + START_POLL_MS, then), START_POLL_MS);
    }

    private void consider(String path) {
        // Our own reopen coming back. It carries no new question - the answer
        // that caused it has just been applied - and answering it again is
        // the whole of the loop this guards against. One shot: read and
        // cleared, so the next open is considered whatever happened to this
        // one. See the field.
        String ours = reopening;
        reopening = null;
        if (path != null && path.equals(ours)) return;

        // Before the remembered branch as well as after it: replaying an
        // answer that names the keyboard means building the game's key
        // profile again, which is read from the record rather than stored
        // in the answer - see Setup, and why the answer is not the place for
        // a copy of the scraped layout.
        Meta meta = Metadata.forPath(activity, path);

        Setup.Answer remembered = Setup.remembered(activity, path);
        if (remembered != null) {
            if (remembered.anything()) apply(remembered, meta, path, false);
            return;
        }

        if (!asked.add(path)) return;

        String[] machineIds = FuseNative.machineIds();
        String[] joystickNames = FuseNative.joystickTypeNames();

        if (!Suggested.anything(meta, path, machineIds, joystickNames)) return;

        ask(path, meta, machineIds, joystickNames);
    }

    // --- the question -------------------------------------------------------------

    private void ask(String path, Meta meta, String[] machineIds, String[] joystickNames) {
        List<Integer> machines = Suggested.machines(meta.machine, path, machineIds);
        List<Integer> controls = Suggested.controls(meta, joystickNames);

        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(pixels(20), pixels(8), pixels(20), 0);

        // Labelled with Fuse's names and answered with its ids: "Spectrum
        // 128K" is what the machine is called on every other screen, and "128"
        // is what survives being written down - the two arrays are parallel,
        // so one index reads both.
        RadioGroup machineChoice = machines.isEmpty() ? null
                : choice(page, R.string.suggest_machine,
                         names(FuseNative.machineNames(), machines));
        RadioGroup controlChoice = controls.isEmpty() ? null
                : choice(page, R.string.suggest_control, labels(joystickNames, controls));

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
                            pickedControl(controlChoice, joystickNames, controls));

                    if (remember.isChecked()) Setup.remember(activity, path, answer);
                    apply(answer, meta, path, true);
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

    /** The interfaces as the rest of the app writes them: Fuse's own names,
     *  and the app's word for the keyboard where the number is past the end
     *  of Fuse's list. */
    private List<String> labels(String[] joystickNames, List<Integer> controls) {
        List<String> labels = new ArrayList<>();

        for (int type : controls) {
            labels.add(type == Controls.JOYSTICK_KEYBOARD
                       ? activity.getString(R.string.joystick_keyboard)
                       : joystickNames[type]);
        }

        return labels;
    }

    /** What the group is set to, as the stable name rather than an index. */
    private static String picked(RadioGroup group, String[] all, List<Integer> offered) {
        if (group == null) return null;

        int at = chosenIndex(group, offered);
        return at < 0 ? null : all[offered.get(at)];
    }

    /** The same, for the controls, where one of the choices is not one of
     *  Fuse's and is stored under {@link Setup#KEYBOARD} instead. */
    private static String pickedControl(RadioGroup group, String[] joystickNames,
                                        List<Integer> offered) {
        if (group == null) return null;

        int at = chosenIndex(group, offered);
        if (at < 0) return null;

        int type = offered.get(at);
        return type == Controls.JOYSTICK_KEYBOARD ? Setup.KEYBOARD
                                                  : joystickNames[type];
    }

    private static int chosenIndex(RadioGroup group, List<Integer> offered) {
        int at = group.indexOfChild(group.findViewById(group.getCheckedRadioButtonId()));
        return at >= 0 && at < offered.size() ? at : -1;
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
    private void apply(Setup.Answer answer, Meta meta, String path, boolean announce) {
        if (answer.joystick != null) applyJoystick(answer.joystick, meta, path);

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

        // Claimed before the reopen rather than inside it, so the open this
        // asks for is recognised as ours however quickly it comes back.
        reopening = path;

        activity.getWindow().getDecorView().postDelayed(() -> {
            host.reopenCurrentGame();
            if (announce) host.note(R.string.suggest_applied);
        }, SETTLE_MS);
    }

    /**
     * The interface, by the name it was remembered under.
     *
     * {@link Setup#KEYBOARD} is not one of Fuse's and never will be - it is
     * the pad sending the game's own keys - so it is recognised here rather
     * than looked for in a list it cannot be in, and it is the one choice that
     * carries something with it: the game's own layout, read out of the record
     * by {@link Keymap} and named after the game.
     *
     * A layout that will not read is not a reason to refuse the choice. The
     * dialog only offers the keyboard when one will (see {@code
     * Suggested.keyboard}), but a remembered answer can outlive the record it
     * was made against - a re-scrape from a provider with no {@code sp2kcfg}
     * leaves the game with the keys it had, which is better than the pad
     * changing under somebody who never asked it to.
     */
    private void applyJoystick(String name, Meta meta, String path) {
        if (Setup.KEYBOARD.equals(name)) {
            host.chooseControl(Controls.JOYSTICK_KEYBOARD, layoutFor(meta, path));
            return;
        }

        int index = indexOf(FuseNative.joystickTypeNames(), name);
        if (index < 0) {
            Log.w(TAG, "no joystick interface called " + name + " any more");
            return;
        }

        host.chooseControl(index, null);
    }

    /** The game's keys as a profile, under the game's own name - or the
     *  file's, for a record that never got one. */
    private static ControlProfiles.Profile layoutFor(Meta meta, String path) {
        if (meta == null) return null;

        String name = meta.name == null || meta.name.isEmpty()
                ? Storage.withoutExtension(Storage.filename(path)) : meta.name;

        return Keymap.profile(name, meta.keymap);
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
