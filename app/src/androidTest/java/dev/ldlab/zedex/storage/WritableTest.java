package dev.ldlab.zedex.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Environment;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * That the write test agrees with what the app can actually write.
 *
 * A media collection accepts only what belongs in it, and that is not a rule
 * about permissions: with no All files access, creating a file with no extension
 * MediaProvider recognises inside {@code Pictures/} fails with "Operation not
 * permitted" on a device where every screenshot the app puts there succeeds. So
 * {@link Storage#isWritable} called the captures folder unusable and the first
 * screenshot after an install went to the data folder instead of the gallery.
 *
 * Which is why the probe is a directory now: a directory has no type to
 * disagree with, and mkdir is refused by exactly the permission being asked
 * about. The first test below is that bug; the second is what makes it a trap,
 * and fails on the old code.
 *
 * All of this is invisible with All files access - MediaProvider steps out of
 * the way and the old probe works - so these skip rather than pass falsely on a
 * device that has granted it.
 */
@RunWith(AndroidJUnit4.class)
public class WritableTest {

    private Context context() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void picturesCountsAsWritable() {
        Assume.assumeFalse("All files access hides the collection rules",
                           Storage.canUseAnyFolder());

        File captures = Storage.capturesDirectory(context());
        assertTrue("captures went outside Pictures: " + captures,
                   captures.getAbsolutePath().startsWith(
                           new File(Environment.getExternalStorageDirectory(),
                                    Environment.DIRECTORY_PICTURES)
                                   .getAbsolutePath()));
        assertTrue("Pictures reported unwritable", Storage.isWritable(captures));
    }

    /**
     * The mechanism, stated plainly: in Pictures a file with no extension is
     * refused and a folder is not. If this ever stops being true the probe can
     * go back to being a file - and if the first line here starts failing,
     * something else does.
     */
    @Test
    public void aFileWithNoExtensionIsRefusedWhereAFolderIsNot() throws Exception {
        Assume.assumeFalse(Storage.canUseAnyFolder());

        File pictures = new File(Environment.getExternalStorageDirectory(),
                                 Environment.DIRECTORY_PICTURES);
        File folder = new File(pictures, "Zedex-writable-test");
        assertTrue("cannot make a folder in Pictures", folder.mkdirs());

        try {
            File plain = new File(folder, ".probe");
            boolean made;
            try {
                made = plain.createNewFile();
            } catch (java.io.IOException refused) {
                made = false;
            }
            assertFalse("Pictures took a file with no extension", made);

            File probe = new File(folder, ".zedex");
            assertTrue("Pictures refused a folder too", probe.mkdir());
            probe.delete();
        } finally {
            for (File left : folder.listFiles() == null
                    ? new File[0] : folder.listFiles()) {
                left.delete();
            }
            folder.delete();
        }
    }

    /** A folder nothing can write to is still refused, permission or not. */
    @Test
    public void refusesWhatCannotBeWritten() {
        assertFalse(Storage.isWritable(new File("/proc/zedex")));
    }
}
