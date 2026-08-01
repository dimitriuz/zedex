package dev.ldlab.zedex.menu;

import dev.ldlab.zedex.FuseNative;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.input.ControlProfiles;
import dev.ldlab.zedex.input.Controls;
import dev.ldlab.zedex.input.Gamepad;
import dev.ldlab.zedex.input.Hotkeys;
import dev.ldlab.zedex.input.Mouse;
import dev.ldlab.zedex.screen.GamepadActivity;
import dev.ldlab.zedex.screen.ProfileActivity;
import dev.ldlab.zedex.screen.SettingsActivity;
import dev.ldlab.zedex.view.EmulatorLayout;
import dev.ldlab.zedex.view.MenuDrawer;
import dev.ldlab.zedex.view.SpectrumKeyboardView;
import android.app.Activity;
import android.content.SharedPreferences;
import android.widget.EditText;

import java.util.List;

/**
 * The joystick, the keyboard and the mouse.
 *
 * One class because they overlap: the pad sends joystick directions or keys
 * depending on its type, the mouse takes the pad over while it is on, and the
 * keyboard's skin is sometimes Android's own and then is not drawn here at all.
 *
 * It holds {@link EmulatorLayout} directly rather than asking through
 * {@link Host}. Whether a control is on screen is the layout's own state, and
 * six host methods for it would make the interface as long as the class.
 */
public final class ControlsUi {

    /** What the machine's side of the app has to lend this one. */
    public interface Host {
        /** Says an action happened; Fuse itself is silent about most of them. */
        void note(int message, Object... arguments);

        /** The sheet, which every choice here is a page of. */
        MenuDrawer sheet();

        /**
         * Whether the picture has the window to itself, which the keyboard page
         * has to say: fullscreen has the keys away whatever the row claims.
         */
        boolean fullscreen();

        /**
         * Whether the controls are on a second screen. Only the keyboard page
         * asks, and only to warn that Android decides which display an input
         * method appears on.
         */
        boolean onSecondScreen();
    }

    private final Activity activity;
    private final SharedPreferences preferences;
    private final EmulatorLayout layout;
    private final Gamepad gamepad;
    private final Host host;

    public ControlsUi(Activity activity, SharedPreferences preferences,
               EmulatorLayout layout, Gamepad gamepad, Host host) {
        this.activity = activity;
        this.preferences = preferences;
        this.layout = layout;
        this.gamepad = gamepad;
        this.host = host;
    }

    /**
     * The two things you play with, the mouse, and what a controller asks of the
     * app.
     *
     * The hotkeys are not under <i>Joystick…</i>, which is about what the pad
     * sends the <em>machine</em>. These are what a controller asks of the app,
     * and they work whether the pad is a joystick or a set of keys.
     */
    public void fill(MenuDrawer sheet) {
        sheet.addSubmenu(text(R.string.menu_joystick), R.drawable.ic_joystick,
                         this::fillJoystick);
        sheet.addSubmenu(text(R.string.menu_keyboard), R.drawable.ic_keyboard,
                         this::fillKeyboard);
        sheet.addSubmenu(text(R.string.menu_mouse), R.drawable.ic_mouse,
                         this::fillMouse);
        sheet.addItem(text(R.string.menu_gamepad), R.drawable.ic_controls,
                      () -> GamepadActivity.open(activity));
    }

    // --- pushing the state to the machine ------------------------------------

    /**
     * Everything a control does goes through {@link Controls}, so this is the
     * whole of applying a profile - and the same call is what a physical gamepad
     * needs when there is one.
     */
    public void applyControls() {
        Controls.setProfile(ControlProfiles.current(preferences).keys);
        Controls.setPadSendsKeys(joystickType() == Controls.JOYSTICK_KEYBOARD);

        layout.refreshControls();
    }

    public void applyMouse() {
        Mouse.apply(preferences);
        Mouse.setEnabled(preferences.getBoolean(SettingsActivity.KEY_MOUSE, false));
    }

    /** A controller appearing or going away, and whatever it is bound to. */
    public void applyGamepad() {
        layout.setJoystickSuppressed(autoHide() && Gamepad.connected());
        gamepad.setHotkeys(Hotkeys.load(preferences));
    }

    /**
     * The keyboard the settings screen chose while it had the window.
     *
     * ☰ pushes its own choice as it is made and the settings screen only writes
     * the preference, so without this the keyboard stayed as it was until the
     * app was next launched. Cheap to call on every resume: the layout ignores
     * the skin it is already showing.
     */
    public void applyKeyboard() {
        layout.setKeyboardSkin(keyboardSkin());
    }

    /**
     * Brings the device's own keyboard up, or puts it away, to match the skin
     * and whether the keyboard is meant to be showing at all.
     *
     * The drawn skins need none of this; this one is Android's, so showing it is
     * asking for it and hiding it is asking it to go.
     */
    public void applySystemKeyboard() {
        boolean wanted = keyboardSkin() == SpectrumKeyboardView.Skin.SYSTEM
                      && layout.keyboardVisible();

        if (wanted) layout.systemKeyboard().open();
        else layout.systemKeyboard().close();
    }

    public SpectrumKeyboardView.Skin keyboardSkin() {
        return SpectrumKeyboardView.Skin.of(
                preferences.getString(SettingsActivity.KEY_KEYBOARD_SKIN, null));
    }

    public String keyboardSkinName() {
        return keyboardSkin().title;
    }

    // --- the mouse -----------------------------------------------------------

    public boolean mouseOn() {
        return preferences.getBoolean(SettingsActivity.KEY_MOUSE, false);
    }

    /** The mouse page, so the bar's row opens the same one the sheet does. */
    public MenuDrawer.Page mousePage() {
        return this::fillMouse;
    }

    // --- the joystick --------------------------------------------------------

    /**
     * Whether the pad is on screen, and which interface it comes out as.
     *
     * The two are kept apart on purpose: hiding the pad does not unplug the
     * joystick, since the interface is what a game reads and a physical
     * gamepad may want it later. The types are Fuse's own list, in Fuse's own
     * order, so the index is the value it takes.
     */
    private void fillJoystick(MenuDrawer sheet) {
        boolean shown = layout.joystickVisible();

        sheet.addItem(text(shown ? R.string.control_hide : R.string.control_show),
                      shown ? R.drawable.ic_hide : R.drawable.ic_show,
                      () -> showJoystick(!shown));
        sheet.addSubmenu(text(R.string.joystick_type, joystickTypeName()),
                         text(R.string.joystick_type_title),
                         R.drawable.ic_swap, joystickTypePage());
        sheet.addSubmenu(text(R.string.joystick_profile,
                              ControlProfiles.current(preferences).name),
                         text(R.string.profile_title),
                         R.drawable.ic_bookmark, keyProfilePage());

        // With a real interface chosen the pad sends joystick directions and
        // not these keys - but the three buttons beside fire always send them,
        // so the row stays live and says what it is still doing.
        if (joystickType() != Controls.JOYSTICK_KEYBOARD) {
            sheet.addNote(text(R.string.joystick_profile_buttons_only));
        }

        sheet.addItem(text(R.string.joystick_auto_hide,
                           text(autoHide() ? R.string.on : R.string.off)),
                      R.drawable.ic_hide, () -> setAutoHide(!autoHide()));
    }

    public void showJoystick(boolean shown) {
        layout.setJoystickVisible(shown);
        preferences.edit().putBoolean(SettingsActivity.KEY_JOYSTICK, shown).apply();

        host.note(shown ? R.string.joystick_shown : R.string.joystick_hidden);
    }

    /** Whether the on-screen pad steps aside for a real controller. */
    private boolean autoHide() {
        return preferences.getBoolean(SettingsActivity.KEY_JOYSTICK_AUTO_HIDE, true);
    }

    private void setAutoHide(boolean on) {
        preferences.edit()
                .putBoolean(SettingsActivity.KEY_JOYSTICK_AUTO_HIDE, on).apply();

        applyGamepad();
        host.note(on ? R.string.joystick_auto_hide_on
                     : R.string.joystick_auto_hide_off);
    }

    /**
     * Fuse's own interfaces, and Keyboard after them.
     *
     * Keyboard is not an interface at all: the machine has nothing plugged in
     * and the pad presses keys, which is how a great many games that predate
     * the joystick interfaces are played. It is offered in the same list because
     * from the pad's side it is the same choice.
     */
    /**
     * The interfaces to choose from.
     *
     * A page rather than a method that shows one, so the two ways in can differ
     * where they should. From the sheet it is a submenu and back is the page
     * above it; from the quick bar it is opened straight onto and back is out.
     * A row that showed it would have to close the sheet first, which threw the
     * trail away and left back climbing to a root nobody had been to.
     *
     * Built when it is shown, not when the row is made: what is ticked is
     * whatever is current at that moment.
     */
    public MenuDrawer.Page joystickTypePage() {
        return page -> {
            String[] fuseTypes = FuseNative.joystickTypeNames();
            if (fuseTypes.length == 0) return;

            String[] names = new String[fuseTypes.length + 1];
            System.arraycopy(fuseTypes, 0, names, 0, fuseTypes.length);
            names[fuseTypes.length] = text(R.string.joystick_keyboard);

            int type = joystickType();
            int ticked = type == Controls.JOYSTICK_KEYBOARD
                    ? fuseTypes.length : type;

            for (int i = 0; i < names.length; i++) {
                int which = i;

                page.addChoice(names[which], which == ticked, () -> {
                    int chosen = which == fuseTypes.length
                            ? Controls.JOYSTICK_KEYBOARD : which;

                    preferences.edit()
                            .putInt(SettingsActivity.KEY_JOYSTICK_TYPE, chosen)
                            .apply();
                    setJoystickType(chosen);
                    host.note(R.string.joystick_type_set, names[which]);
                });
            }
        };
    }

    /** Nothing plugged in for Keyboard, since the pad sends keys instead. */
    private void setJoystickType(int type) {
        FuseNative.setJoystickType(type == Controls.JOYSTICK_KEYBOARD
                ? Controls.JOYSTICK_NONE : type);
        applyControls();
    }

    /** The stored type, or Kempston; never an index Fuse would not recognise. */
    public int joystickType() {
        int stored = preferences.getInt(SettingsActivity.KEY_JOYSTICK_TYPE,
                                        Controls.JOYSTICK_KEMPSTON);
        int count = FuseNative.joystickTypeNames().length;

        if (stored == Controls.JOYSTICK_KEYBOARD) return stored;

        return stored >= 0 && (count == 0 || stored < count)
                ? stored : Controls.JOYSTICK_KEMPSTON;
    }

    public String joystickTypeName() {
        int type = joystickType();
        if (type == Controls.JOYSTICK_KEYBOARD) {
            return text(R.string.joystick_keyboard);
        }

        String[] names = FuseNative.joystickTypeNames();
        return type < names.length ? names[type] : "";
    }

    /** The next type round, for a controller hotkey. */
    public void nextJoystickType() {
        int count = FuseNative.joystickTypeNames().length;
        if (count == 0) return;

        int type = joystickType();
        int at = type == Controls.JOYSTICK_KEYBOARD ? count : type;
        int next = ( at + 1 ) % ( count + 1 );
        int chosen = next == count ? Controls.JOYSTICK_KEYBOARD : next;

        preferences.edit()
                .putInt(SettingsActivity.KEY_JOYSTICK_TYPE, chosen).apply();
        setJoystickType(chosen);

        host.note(R.string.joystick_type_set, joystickTypeName());
    }

    // --- the key profile -----------------------------------------------------

    /** The profiles to choose from, and the two things done to them. */
    public MenuDrawer.Page keyProfilePage() {
        return page -> {
            List<ControlProfiles.Profile> profiles =
                    ControlProfiles.all(preferences);
            String[] names = new String[profiles.size()];

            for (int i = 0; i < names.length; i++) names[i] = profiles.get(i).name;

            int chosen = ControlProfiles.currentIndex(preferences);

            for (int i = 0; i < names.length; i++) {
                int which = i;

                page.addChoice(names[which], which == chosen, () -> {
                    ControlProfiles.store(preferences, profiles, which);
                    applyControls();
                    host.note(R.string.profile_set, names[which]);
                });
            }

            // Under a rule rather than among the profiles: choosing one and
            // changing one are different kinds of thing, and a row that opens a
            // screen reads as another profile at a glance.
            page.addRule();
            page.addItem(text(R.string.profile_edit), R.drawable.ic_edit,
                         () -> ProfileActivity.open(activity));
            page.addSubmenu(text(R.string.profile_new), R.drawable.ic_plus,
                            newProfile());

            String current = profiles.get(chosen).name;

            page.addItem(activity.getString(R.string.profile_copy, current),
                         R.drawable.ic_card, () -> copyProfile());

            // Deleting is the one thing here that doing again does not undo, so
            // it is a page that commits by its own name rather than a row that
            // acts as it is touched.
            if (profiles.size() > 1) {
                page.addSubmenu(activity.getString(R.string.profile_remove, current),
                                R.drawable.ic_quit, deleteProfile());
            }
        };
    }

    /**
     * The one in use, again, under a name of its own.
     *
     * No name to type: this exists to be the starting point for a change, and
     * asking what to call something before there is anything to call it is a
     * question with no answer yet. It can be renamed in the editor, where the
     * name sits at the top of the screen with the keys it belongs to.
     */
    private void copyProfile() {
        List<ControlProfiles.Profile> profiles = ControlProfiles.all(preferences);
        ControlProfiles.Profile source = ControlProfiles.current(preferences);
        String name = copyName(profiles, source.name);

        profiles.add(new ControlProfiles.Profile(name, source.keys));
        ControlProfiles.store(preferences, profiles, profiles.size() - 1);

        applyControls();
        host.note(R.string.profile_copied, name);
    }

    /** "QAOPM copy", then "QAOPM copy 2": a copy of a copy still has a name. */
    private String copyName(List<ControlProfiles.Profile> profiles, String of) {
        String name = activity.getString(R.string.profile_copy_suffix, of);

        for (int n = 2; taken(profiles, name); n++) {
            name = activity.getString(R.string.profile_copy_numbered, of, n);
        }

        return name;
    }

    private static boolean taken(List<ControlProfiles.Profile> profiles, String name) {
        for (ControlProfiles.Profile profile : profiles) {
            if (profile.name.equals(name)) return true;
        }
        return false;
    }

    private MenuDrawer.Page deleteProfile() {
        return page -> {
            List<ControlProfiles.Profile> profiles = ControlProfiles.all(preferences);
            int index = ControlProfiles.currentIndex(preferences);

            if (profiles.size() < 2) {
                page.addNote(text(R.string.profile_last));
                return;
            }

            String name = profiles.get(index).name;
            page.addNote(activity.getString(R.string.profile_delete_ask, name));
            page.addItem(text(R.string.profile_delete), R.drawable.ic_quit, () -> {
                profiles.remove(index);
                ControlProfiles.store(preferences, profiles, Math.max(0, index - 1));

                applyControls();
                host.note(R.string.profile_removed, name);
                host.sheet().close();
            });
        };
    }

    /** A new profile starts as a copy of the one in use, and becomes the one in
     *  use: it is being made because the current keys are nearly right. */
    private MenuDrawer.Page newProfile() {
        return page -> {
            EditText field = page.addField(text(R.string.profile_new_name), "", 0);

            page.addItem(text(R.string.profile_new), R.drawable.ic_plus, () -> {
                String name = field.getText().toString().trim();
                if (name.isEmpty()) return;

                List<ControlProfiles.Profile> profiles =
                        ControlProfiles.all(preferences);

                profiles.add(new ControlProfiles.Profile(
                        name, ControlProfiles.current(preferences).keys));
                ControlProfiles.store(preferences, profiles, profiles.size() - 1);

                applyControls();
                ProfileActivity.open(activity);
            });
        };
    }

    /** The one in use, for a row that says what a tap would change. */
    public String keyProfileName() {
        return ControlProfiles.current(preferences).name;
    }

    /** The next profile round, for a controller hotkey. */
    public void nextKeyProfile() {
        List<ControlProfiles.Profile> profiles = ControlProfiles.all(preferences);
        if (profiles.size() < 2) return;

        int next = ( ControlProfiles.currentIndex(preferences) + 1 )
                 % profiles.size();

        ControlProfiles.store(preferences, profiles, next);
        applyControls();

        host.note(R.string.profile_set, profiles.get(next).name);
    }

    // --- the keyboard --------------------------------------------------------

    private void fillKeyboard(MenuDrawer sheet) {
        boolean shown = layout.keyboardVisible();

        if (host.fullscreen()) sheet.addNote(text(R.string.keyboard_fullscreen));

        sheet.addItem(text(shown ? R.string.control_hide : R.string.control_show),
                      shown ? R.drawable.ic_hide : R.drawable.ic_show,
                      () -> showKeyboard(!shown));
        sheet.addSubmenu(text(R.string.keyboard_skin, keyboardSkin().title),
                         text(R.string.keyboard_skin_title),
                         R.drawable.ic_picture, keyboardSkinPage());

        // Said here because here is where the skin is chosen. What it types
        // still reaches the machine - the panel's window is what the input
        // method is talking to - but which screen it is drawn on is Android's
        // to decide, and a second screen has to be allowed to host one.
        if (host.onSecondScreen()
                && keyboardSkin() == SpectrumKeyboardView.Skin.SYSTEM) {
            sheet.addNote(text(R.string.keyboard_system_elsewhere));
        }
    }

    /**
     * Which machine's keyboard is drawn.
     *
     * A picture and where its keys are, nothing more: the 128K's plate has keys
     * the 48K's rubber one does not, and they reach the machine the way the real
     * ones did - TRUE VIDEO is CAPS SHIFT and 3, and most of the others turn out
     * to be single keys Fuse already knows.
     */
    /** The keyboards to choose from, Android's own among them. */
    public MenuDrawer.Page keyboardSkinPage() {
        return page -> {
            SpectrumKeyboardView.Skin[] skins = SpectrumKeyboardView.Skin.values();
            String[] names = new String[skins.length];
            int checked = 0;

            for (int i = 0; i < skins.length; i++) {
                names[i] = skins[i].title;
                if (skins[i] == keyboardSkin()) checked = i;
            }

            int chosen = checked;

            for (int i = 0; i < skins.length; i++) {
                int which = i;

                page.addChoice(names[which], which == chosen, () -> {
                    preferences.edit()
                            .putString(SettingsActivity.KEY_KEYBOARD_SKIN,
                                       skins[which].value)
                            .apply();
                    layout.setKeyboardSkin(skins[which]);

                    // Posted, and after the sheet has gone: an input method is
                    // only shown for the window that has the focus, and while
                    // the sheet still had it the request was quietly dropped.
                    layout.post(this::applySystemKeyboard);
                    host.note(R.string.keyboard_skin_set, skins[which].title);
                });
            }
        };
    }

    public void showKeyboard(boolean shown) {
        layout.setKeyboardVisible(shown);
        applySystemKeyboard();
        preferences.edit().putBoolean(SettingsActivity.KEY_KEYBOARD, shown).apply();

        host.note(shown ? R.string.keyboard_shown : R.string.keyboard_hidden);
    }

    // --- the mouse -----------------------------------------------------------

    /**
     * The Kempston mouse, which is a mode rather than a peripheral you forget
     * about: while it is on, the pad and the stick move the pointer instead of
     * the joystick, so the page says so rather than leaving it to be discovered.
     */
    private void fillMouse(MenuDrawer sheet) {
        boolean on = Mouse.enabled();

        sheet.addItem(text(on ? R.string.mouse_off : R.string.mouse_on),
                      on ? R.drawable.ic_hide : R.drawable.ic_mouse,
                      () -> showMouse(!on));
        sheet.addItem(text(R.string.mouse_sensitivity, sensitivity()),
                      R.drawable.ic_swap, this::showMouseSensitivityDialog);
        sheet.addNote(text(R.string.mouse_explain));
    }

    private int sensitivity() {
        return SettingsActivity.SettingsFragment.number(
                preferences, SettingsActivity.KEY_MOUSE_SENSITIVITY, 100);
    }

    private void showMouse(boolean on) {
        preferences.edit().putBoolean(SettingsActivity.KEY_MOUSE, on).apply();
        Mouse.setEnabled(on);

        host.note(on ? R.string.mouse_on_done : R.string.mouse_off_done);
    }

    private void showMouseSensitivityDialog() {
        String[] names = activity.getResources()
                .getStringArray(R.array.percent_names);
        String[] values = activity.getResources()
                .getStringArray(R.array.percent_values);

        int now = sensitivity();
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (Integer.parseInt(values[i]) == now) checked = i;
        }

        int chosen = checked;

        host.sheet().go(text(R.string.mouse_sensitivity_title), page -> {
            for (int i = 0; i < names.length; i++) {
                int which = i;

                page.addChoice(names[i], which == chosen, () -> {
                    preferences.edit()
                            .putString(SettingsActivity.KEY_MOUSE_SENSITIVITY,
                                       values[which])
                            .apply();
                    Mouse.apply(preferences);
                    host.note(R.string.mouse_sensitivity,
                              Integer.parseInt(values[which]));
                });
            }
        });
    }

    private String text(int message, Object... arguments) {
        return activity.getString(message, arguments);
    }
}
