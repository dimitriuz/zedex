package dev.ldlab.zedex.welcome.pages;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.storage.Prefs;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.welcome.Step;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Which Spectrum to start.
 *
 * <b>Written down rather than asked of Fuse.</b> FuseNative.machineIds() is
 * empty until the emulation thread is up - SettingsActivity disables its own
 * machine row for exactly that reason - and this wizard runs before the
 * machine starts by design, since the ROMs are unpacked into a folder the
 * wizard has not settled yet. So the six that matter are here, and
 * MachineIdsContractTest asserts every one of them against Fuse's real list
 * after launching the emulator.
 *
 * Six and not thirty-two: this is the page that gets somebody started, and
 * the whole list is one tap away in Settings › Machine. The model names are
 * literals because they are product names, like Kempston; the line under each
 * is a resource.
 */
public final class MachinePage implements Step {

    /** One offered machine: Fuse's own id, this app's spelling of the model,
     *  and the line under it. */
    public static final class Model {
        public final String id;
        public final String name;
        public final int description;

        Model(String id, String name, int description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }
    }

    public static final Model[] MACHINES = {
        new Model("48",       "ZX Spectrum 48K",  R.string.welcome_machine_48),
        new Model("128",      "ZX Spectrum 128K", R.string.welcome_machine_128),
        new Model("plus2",    "ZX Spectrum +2",   R.string.welcome_machine_plus2),
        new Model("plus2a",   "ZX Spectrum +2A",  R.string.welcome_machine_plus2a),
        new Model("plus3",    "ZX Spectrum +3",   R.string.welcome_machine_plus3),
        new Model("pentagon", "Pentagon 128",     R.string.welcome_machine_pentagon),
    };

    @Override
    public int title() {
        return R.string.welcome_machine;
    }

    @Override
    public int blurb() {
        return R.string.welcome_machine_hint;
    }

    @Override
    public View body(Context context, SharedPreferences preferences) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        // The same default Machine.DEFAULT_MACHINE and settings_machine.xml
        // both state; checked, they agree, so a skipped page is safe.
        String current = preferences.getString(Prefs.KEY_MACHINE, "128");

        // A Cards.Group, so tapping a different machine moves the cyan
        // highlight there live - rather than leaving it on the card that
        // used to be current until the page is rebuilt from scratch.
        Cards.Group group = new Cards.Group();

        for (Model machine : MACHINES) {
            column.addView(group.add(context, machine.name,
                    machine.description,
                    v -> preferences.edit()
                            .putString(Prefs.KEY_MACHINE, machine.id).apply(),
                    machine.id.equals(current)));
        }

        return column;
    }
}
