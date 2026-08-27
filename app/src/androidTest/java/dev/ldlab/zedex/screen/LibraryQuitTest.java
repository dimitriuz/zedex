package dev.ldlab.zedex.screen;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.Screen;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The library's own way out of the app.
 *
 * There has to be one, and for a while there was not. The machine has had a
 * Quit since there was a machine, but it is on a screen the library cannot
 * see; the library's only way out was Back at its root, and on a two-screen
 * handheld the panel refuses Back deliberately - which on a device that gives
 * each display its own focus is where a Back press lands. So the app became
 * one you could only leave by going Home.
 *
 * What this asserts is that the row is <em>there</em>, not what it does:
 * tapping it would end the process, and instrumentation runs inside that
 * process, so the test would take itself down with the app. Quitting itself
 * is one line - every task of ours, then {@code exit} - and it is the
 * reachability that went missing.
 */
@RunWith(AndroidJUnit4.class)
public class LibraryQuitTest {

    private static final long FIND = 10_000;

    private Context context;
    private UiDevice device;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    @Test
    public void thelibraryOffersAWayOutOfTheApp() {
        // The same door EmulatorActivity.openLibrary uses: a plain launcher
        // intent hands straight back to the machine whenever startsInLibrary
        // is off, and this test wants the screen either way.
        Intent library = new Intent(context, LibraryActivity.class)
                .putExtra(LibraryActivity.EXTRA_FROM_MENU, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        // Or a mark over the toolbar would swallow the very tap this test
        // makes on Options.
        Screen.suppressFirstRun(context);
        context.startActivity(library, Screen.here());

        UiObject2 options = device.wait(
                Until.findObject(By.desc(context.getString(R.string.library_options))), FIND);
        assertNotNull("the library screen never came up", options);

        Screen.assertHere();

        options.click();

        assertNotNull("the library's options offer no way out of the app - the "
                      + "machine's Quit is on a screen this one cannot reach, and "
                      + "Back is spoken for on a two-screen handheld",
                      device.wait(Until.findObject(
                              By.text(context.getString(R.string.menu_quit))), FIND));

        // Left open on purpose rather than tapped: the row under test ends
        // this process, and this test is in it.
        device.pressBack();
    }
}
