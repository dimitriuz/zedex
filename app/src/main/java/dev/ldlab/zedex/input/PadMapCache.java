package dev.ldlab.zedex.input;

import android.content.SharedPreferences;
import android.view.InputDevice;

import java.util.HashMap;
import java.util.Map;

/**
 * Which mapping belongs to the device an event came from.
 *
 * Per device and not per app, because two pads can be connected at once -
 * measured, a Bluetooth pad and a USB pad both reporting GAMEPAD and JOYSTICK -
 * and each carries its own. Every event already says which device it came from,
 * so there is nothing to guess.
 *
 * Cached because this answers a question asked for every button press and every
 * motion event, and the honest answer costs a device lookup and a JSON parse.
 *
 * {@link Devices} exists so this is testable: {@code InputDevice.getDevice} is
 * static and answers null under the stub android.jar, so anything calling it
 * directly is out of reach on the JVM tier - the same trap as SparseArray, one
 * layer up.
 */
public final class PadMapCache implements Gamepad.Maps {

    /** A device id to the key its mapping is stored under. */
    public interface Devices {
        /** Null when there is no such device, or it is not a pad. */
        String keyFor(int deviceId);
    }

    /** The real one: Android's device table. */
    public static final Devices ANDROID = deviceId -> {
        InputDevice device = InputDevice.getDevice(deviceId);
        return device == null ? null : PadMaps.keyFor(device);
    };

    private final SharedPreferences preferences;
    private final Devices devices;
    private final Map<Integer, PadMap> resolved = new HashMap<>();

    public PadMapCache(SharedPreferences preferences, Devices devices) {
        this.preferences = preferences;
        this.devices = devices;
    }

    @Override
    public PadMap forDevice(int deviceId) {
        PadMap known = resolved.get(deviceId);
        if (known != null) return known;

        String key = devices.keyFor(deviceId);

        // A device that has gone away, or was never a pad, drives the defaults
        // rather than nothing: a pad unplugged mid-press still has to be able
        // to let go of what it was holding.
        PadMap map = key == null ? PadMap.defaults() : PadMaps.load(preferences, key);

        resolved.put(deviceId, map);
        return map;
    }

    /** Drop everything cached: a pad was added or removed, or one was edited. */
    public void forget() {
        resolved.clear();
    }
}
