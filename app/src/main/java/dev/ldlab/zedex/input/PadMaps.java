package dev.ldlab.zedex.input;

import android.content.SharedPreferences;
import android.view.InputDevice;

import dev.ldlab.zedex.storage.Prefs;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Every pad's mapping, kept apart.
 *
 * Per pad rather than one for all of them because the case this feature exists
 * for is a handheld with a built-in controller and a Bluetooth one beside it:
 * a single mapping means correcting the second breaks the first, silently, and
 * the person finds out in the middle of a game.
 *
 * The name is stored beside the mapping so that a pad which is not connected
 * can still be listed and corrected - and so that a bug report says which pad
 * it was. The device key is not in the report; see Diagnostics.
 */
public final class PadMaps {

    private PadMaps() {
    }

    private static final String NAME = "name";

    /**
     * How a pad is told from another pad.
     *
     * The name is in the fallback and not optional: measured on a
     * GameSir-Cyclone Pro, one physical pad is three input devices - the pad,
     * a consumer-control endpoint and a keyboard - sharing one vendor, one
     * product and one Bluetooth address, and differing only in the name. A
     * fallback of vendor:product would hand all three one mapping.
     *
     * Measured: the descriptor survived a power cycle, a full forget-and-
     * re-pair and a reboot, on a Bluetooth pad and a USB pad both, while every
     * kernel id moved in three of the four readings. See the spec's
     * "The device key".
     */
    public static String keyFor(InputDevice device) {
        String descriptor = device.getDescriptor();

        return descriptor == null || descriptor.isEmpty()
                ? device.getVendorId() + ":" + device.getProductId() + ":" + device.getName()
                : descriptor;
    }

    private static JSONObject all(SharedPreferences preferences) {
        // Prefs.KEY_PAD_MAPPINGS, called by name at every site rather than
        // through a local alias - scripts/check-prefs.py finds a preference
        // by matching KEY_* at the call site, and a local alias named plain
        // KEY was invisible to it: padMappings never appeared in the report,
        // silently unguarded against the wrong-type-read bug that shipped a
        // crash in the bug reporter once already. See Prefs.KEY_PAD_MAPPINGS.
        String stored = preferences.getString(Prefs.KEY_PAD_MAPPINGS, null);
        if (stored == null || stored.isEmpty()) return new JSONObject();

        try {
            return new JSONObject(stored);
        } catch (JSONException e) {
            // Every pad back on its defaults, which is a working app. This
            // read does not itself clear the stored text - but nothing here
            // holds it either. save() and forget() both rewrite the whole
            // key from what all() returns, so the first one called after
            // this discards whatever could not be parsed along with
            // everything else. What survives is "until the next write", not
            // "for a later version to still have a chance at".
            return new JSONObject();
        }
    }

    /** This pad's mapping, or the defaults. */
    public static PadMap load(SharedPreferences preferences, String deviceKey) {
        JSONObject entry = all(preferences).optJSONObject(deviceKey);
        return entry == null ? PadMap.defaults() : PadMap.fromJson(entry.toString());
    }

    public static void save(SharedPreferences preferences, String deviceKey,
                            String name, PadMap map) {
        JSONObject everything = all(preferences);

        try {
            JSONObject entry = new JSONObject(map.toJson());
            entry.put(NAME, name);
            everything.put(deviceKey, entry);
        } catch (JSONException e) {
            return;
        }

        preferences.edit().putString(Prefs.KEY_PAD_MAPPINGS, everything.toString()).apply();
    }

    /** Back to the defaults for one pad, leaving the others alone. */
    public static void forget(SharedPreferences preferences, String deviceKey) {
        JSONObject everything = all(preferences);
        everything.remove(deviceKey);
        preferences.edit().putString(Prefs.KEY_PAD_MAPPINGS, everything.toString()).apply();
    }

    /**
     * Every pad with a stored mapping, device key to name, for the picker.
     *
     * An entry with no {@code "name"} is omitted rather than named after its
     * own key. That used to fall back to the key - a key is better than
     * nothing to show - but that stopped being true the moment this value
     * started reaching a report the user sends to somebody else: the key can
     * be Android's own device descriptor, which is not proven free of a
     * Bluetooth MAC (see Diagnostics and CLAUDE.md), so printing it anywhere
     * a person or the picker can show it undoes the whole point of keeping it
     * out of the report. {@code save()} always writes a name, so this is a
     * corrupt or hand-edited entry either way - it costs that one entry its
     * row, and nothing else. Naming it after its key instead, or with a
     * placeholder, would both still be wrong: a placeholder shared by two
     * nameless pads reads as the same pad and cannot be told apart, which is
     * a worse picker than one that leaves the row out.
     */
    public static Map<String, String> known(SharedPreferences preferences) {
        Map<String, String> names = new HashMap<>();
        JSONObject everything = all(preferences);

        for (Iterator<String> keys = everything.keys(); keys.hasNext(); ) {
            String deviceKey = keys.next();
            JSONObject entry = everything.optJSONObject(deviceKey);
            if (entry == null) continue;

            String name = entry.optString(NAME, null);
            if (name == null || name.isEmpty()) continue;

            names.put(deviceKey, name);
        }

        return names;
    }
}
