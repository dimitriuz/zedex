package dev.ldlab.zedex.frontend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

/**
 * Which ES-DE folder the app reads, on a device with more than one volume.
 *
 * <b>Measured on an AYN Thor Lite, where this was wrong.</b> ES-DE's real
 * folder was on the memory card - {@code /storage/FA6E-75C6/ES-DE}, a 699 KB
 * gamelist and a full {@code downloaded_media} - and the app looked only at
 * {@code Environment.getExternalStorageDirectory()}, which is the primary
 * volume by definition and always will be. It found {@code
 * /storage/emulated/0/ES-DE} instead: a folder holding nothing but the {@code
 * custom_systems} this app had itself written there, on the same wrong
 * assumption. So the link reported that ES-DE had no games listed, for a
 * collection of 803 with artwork for all of them, and the two files that make
 * a game launchable from ES-DE had been written where ES-DE would never look.
 *
 * That is why the decision is a function over a list rather than a walk of the
 * real device: the case that matters is two candidates where the wrong one
 * comes first, and no device test can mount a volume to make it.
 */
public class EsdeFolderTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** A volume with an ES-DE folder that has only the name. */
    private File bare() throws IOException {
        File volume = temp.newFolder();
        assertEquals(true, new File(volume, "ES-DE").mkdirs());
        return volume;
    }

    /** A volume with an ES-DE folder ES-DE itself has furnished. */
    private File furnished(String what) throws IOException {
        File volume = temp.newFolder();
        assertEquals(true, new File(volume, "ES-DE/" + what).mkdirs());
        return volume;
    }

    @Test
    public void nothingAnywhereIsNoFolder() throws IOException {
        assertNull(EsDe.esdeIn(Collections.singletonList(temp.newFolder())));
        assertNull(EsDe.esdeIn(Collections.<File>emptyList()));
    }

    /** One candidate is the answer whether or not it looks furnished: a fresh
     *  ES-DE that has scraped nothing yet is still ES-DE's. */
    @Test
    public void theonlyCandidateWins() throws IOException {
        File volume = bare();

        assertEquals(new File(volume, "ES-DE"),
                     EsDe.esdeIn(Collections.singletonList(volume)));
    }

    /**
     * The furnished one beats the one that only has the name, wherever it is
     * in the list.
     *
     * The device case exactly: the primary volume is looked at first and holds
     * a folder of our own making, and the real one is on the card behind it.
     */
    @Test
    public void afurnishedFolderBeatsAbareOneEvenWhenItIsSecond() throws IOException {
        File first = bare();
        File second = furnished("gamelists");

        assertEquals(new File(second, "ES-DE"), EsDe.esdeIn(Arrays.asList(first, second)));
    }

    /** Any of the three ES-DE makes for itself counts. */
    @Test
    public void mediaOrSettingsCountAsFurnishedToo() throws IOException {
        File media = furnished("downloaded_media");
        File settings = furnished("settings");

        assertEquals(new File(media, "ES-DE"),
                     EsDe.esdeIn(Arrays.asList(bare(), media)));
        assertEquals(new File(settings, "ES-DE"),
                     EsDe.esdeIn(Arrays.asList(bare(), settings)));
    }

    /**
     * A folder holding only {@code custom_systems} does <b>not</b> count.
     *
     * That is the one this app writes, so counting it would make the app's own
     * footprint look like ES-DE's home - which is precisely how the wrong
     * folder won on the device this was found on.
     */
    @Test
    public void ourOwnCustomSystemsDoesNotMakeItEsdes() throws IOException {
        File ours = furnished("custom_systems");
        File theirs = furnished("gamelists");

        assertEquals(new File(theirs, "ES-DE"), EsDe.esdeIn(Arrays.asList(ours, theirs)));
    }

    /** With two furnished folders the first wins, which keeps a device that
     *  was working before this existed behaving the way it did. */
    @Test
    public void thefirstFurnishedOneWins() throws IOException {
        File first = furnished("gamelists");
        File second = furnished("gamelists");

        assertEquals(new File(first, "ES-DE"), EsDe.esdeIn(Arrays.asList(first, second)));
    }
}
