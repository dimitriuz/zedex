package dev.ldlab.zedex.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * How the app learns what the newest release is called.
 *
 * {@code /releases/latest} answers {@code 302 Location:
 * .../releases/tag/v1.1.1}, and that tag is the whole answer. The shape belongs
 * to GitHub rather than to this repository, so it is worth a test that says what
 * the app expects: if it ever changes, this fails here rather than the app going
 * quietly blind to updates.
 *
 * The first string below is real — what curl returned from this project's own
 * releases page.
 */
@RunWith(AndroidJUnit4.class)
public class UpdaterVersionTest {

    @Test
    public void readsTheTagOutOfTheRedirect() {
        assertEquals("1.0.4", Updater.versionFrom(
                "https://github.com/dimitriuz/zedex/releases/tag/v1.0.4"));
    }

    /** A tag may be written with or without its v. */
    @Test
    public void takesEitherFormOfTag() {
        assertEquals("1.1.1", Updater.versionFrom("/releases/tag/v1.1.1"));
        assertEquals("1.1.1", Updater.versionFrom("/releases/tag/1.1.1"));
        assertEquals("2", Updater.versionFrom("/releases/tag/v2"));
        assertEquals("1.2.3", Updater.versionFrom("/releases/tag/v1.2.3"));
    }

    /**
     * Anything else has to be null, so the app does nothing rather than
     * something wrong. A missing Location — which is what a 200 or a 404 gives —
     * is the commonest of these.
     */
    @Test
    public void refusesWhatItCannotRead() {
        assertNull("no location at all", Updater.versionFrom(null));
        assertNull("not a tag url",
                   Updater.versionFrom("https://github.com/dimitriuz/zedex"));
        assertNull("no version in it", Updater.versionFrom("/releases/tag/nightly"));
        assertNull("empty", Updater.versionFrom(""));
    }
}
