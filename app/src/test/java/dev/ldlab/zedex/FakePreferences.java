package dev.ldlab.zedex;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@link SharedPreferences}, in a map, for the JVM tier.
 *
 * The interface is the whole of what a class like {@code Filter} or {@code
 * Border} touches, and it is an interface rather than a framework class - so
 * the only thing standing between those and a JVM test was a few lines of
 * implementation. No Robolectric, no {@code returnDefaultValues}, no mocking
 * library: this is smaller than the configuration any of those would need, and
 * it is readable, which a mock's expectation script is not.
 *
 * Deliberately not thread safe and deliberately not persistent. {@code apply}
 * and {@code commit} both write straight through, so a test can read back what
 * it just wrote without wondering whether a background write has happened -
 * which is the one way the real class's asynchrony could make a test lie.
 *
 * The listener half of the interface is not implemented. Nothing this tier
 * tests registers one; the day something does, that is the moment to decide
 * what "notified" should mean here rather than now.
 */
public final class FakePreferences implements SharedPreferences {

    private final Map<String, Object> values = new HashMap<>();

    /** Starts with whatever a test wants already on disk - a device that has
     *  been used, which is the state CLAUDE.md warns is the one never
     *  exercised: "new code that reads existing state must be tested against
     *  used state". */
    public FakePreferences with(String key, Object value) {
        values.put(key, value);
        return this;
    }

    /** What is actually stored, for a test that cares about the writes rather
     *  than the reads - {@code Filter.migrate} removing the two old keys, for
     *  instance, which no getter can show. */
    public Map<String, Object> stored() {
        return Collections.unmodifiableMap(values);
    }

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(values);
    }

    @Override
    public String getString(String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> fallback) {
        Object value = values.get(key);
        return value instanceof Set ? (Set<String>) value : fallback;
    }

    @Override
    public int getInt(String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Integer ? (Integer) value : fallback;
    }

    @Override
    public long getLong(String key, long fallback) {
        Object value = values.get(key);
        return value instanceof Long ? (Long) value : fallback;
    }

    @Override
    public float getFloat(String key, float fallback) {
        Object value = values.get(key);
        return value instanceof Float ? (Float) value : fallback;
    }

    @Override
    public boolean getBoolean(String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    @Override
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new FakeEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
    }

    /**
     * Batches until {@code apply} or {@code commit}, like the real one.
     *
     * Not writing straight through on each put is the part worth copying: a
     * caller that builds an edit and never applies it has written nothing, and
     * a fake that ignored that would pass a test the device would fail.
     */
    private final class FakeEditor implements Editor {

        private final Map<String, Object> pending = new HashMap<>();
        private final Set<String> removals = new HashSet<>();
        private boolean clearFirst;

        @Override public Editor putString(String key, String value) { return put(key, value); }
        @Override public Editor putStringSet(String key, Set<String> value) { return put(key, value); }
        @Override public Editor putInt(String key, int value) { return put(key, value); }
        @Override public Editor putLong(String key, long value) { return put(key, value); }
        @Override public Editor putFloat(String key, float value) { return put(key, value); }
        @Override public Editor putBoolean(String key, boolean value) { return put(key, value); }

        private Editor put(String key, Object value) {
            // The real one treats a null value as a removal, and code has been
            // written against that.
            if (value == null) return remove(key);

            pending.put(key, value);
            removals.remove(key);
            return this;
        }

        @Override
        public Editor remove(String key) {
            removals.add(key);
            pending.remove(key);
            return this;
        }

        @Override
        public Editor clear() {
            clearFirst = true;
            return this;
        }

        @Override
        public boolean commit() {
            if (clearFirst) values.clear();
            values.keySet().removeAll(removals);
            values.putAll(pending);
            return true;
        }

        @Override
        public void apply() {
            commit();
        }
    }
}
