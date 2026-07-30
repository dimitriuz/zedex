package dev.ldlab.zedex;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Pokes worth keeping: a name, an address and a value.
 *
 * A poke is one byte written into the machine's memory, and the ones people
 * collect are cheats — infinite lives, no collisions, a level to start on. They
 * are worth a list because a poke is useless unless you can find it again a week
 * later, and worth a *name* because "44676, 0" is not something anyone remembers.
 *
 * Nothing is applied by being on the list. A stored poke is a thing to press, so
 * loading a game and pressing it is the whole flow, and the same poke can be
 * applied again after a reset without typing it out.
 *
 * Stored as JSON in the preferences, like the key profiles and the hotkeys —
 * {@code org.json} comes with the framework, so it costs no dependency.
 */
final class Pokes {

    private Pokes() {
    }

    static final String KEY_POKES = "pokes";

    /** One poke. Immutable; the list is rewritten rather than edited. */
    static final class Poke {
        final String name;
        final int address;
        final int value;

        Poke(String name, int address, int value) {
            this.name = name;
            this.address = address;
            this.value = value;
        }

        /** "44676, 0" — the two numbers as every poke list in the world has them. */
        String numbers() {
            return address + ", " + value;
        }
    }

    /** Every stored poke, oldest first; never null. */
    static List<Poke> all(SharedPreferences preferences) {
        List<Poke> pokes = new ArrayList<>();
        String stored = preferences.getString(KEY_POKES, null);

        if (stored == null) return pokes;

        try {
            JSONArray array = new JSONArray(stored);

            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);

                pokes.add(new Poke(object.optString("name", "?"),
                                   object.optInt("address", -1),
                                   object.optInt("value", -1)));
            }
        } catch (JSONException e) {
            // A list that will not parse is a list nobody can fix from here; an
            // empty one at least lets a poke be added again.
            pokes.clear();
        }

        return pokes;
    }

    static void add(SharedPreferences preferences, String name,
                    int address, int value) {
        List<Poke> pokes = all(preferences);

        pokes.add(new Poke(name, address, value));
        store(preferences, pokes);
    }

    static void remove(SharedPreferences preferences, int index) {
        List<Poke> pokes = all(preferences);

        if (index < 0 || index >= pokes.size()) return;

        pokes.remove(index);
        store(preferences, pokes);
    }

    private static void store(SharedPreferences preferences, List<Poke> pokes) {
        JSONArray array = new JSONArray();

        try {
            for (Poke poke : pokes) {
                JSONObject object = new JSONObject();

                object.put("name", poke.name);
                object.put("address", poke.address);
                object.put("value", poke.value);
                array.put(object);
            }
        } catch (JSONException e) {
            // put() only throws on a NaN, and there are no doubles here.
            return;
        }

        preferences.edit().putString(KEY_POKES, array.toString()).apply();
    }

    /**
     * A number as a poke list writes them: decimal, or hexadecimal behind
     * {@code 0x}, {@code $} or {@code #}. Returns -1 for anything else, which is
     * every empty field and every typo.
     */
    static int number(String text, int most) {
        if (text == null) return -1;

        String trimmed = text.trim();
        int radix = 10;

        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            trimmed = trimmed.substring(2);
            radix = 16;
        } else if (trimmed.startsWith("$") || trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
            radix = 16;
        }

        try {
            int value = Integer.parseInt(trimmed, radix);
            return value >= 0 && value <= most ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
