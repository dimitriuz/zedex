package dev.ldlab.zedex.menu;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.meta.Artwork;
import dev.ldlab.zedex.media.AyFile;
import dev.ldlab.zedex.media.Music;
import dev.ldlab.zedex.view.MenuDrawer;

import android.app.Activity;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * The game's own music, played by the machine.
 *
 * A scraped {@code .ay} is the driver the game used, so listening to it means
 * the Spectrum runs that driver - see {@code media.AySnapshot}. Which is also
 * why this page exists at all rather than a play button somewhere: there is
 * one machine, and it is currently running a game.
 *
 * <b>The game is put aside and given back.</b> Starting a tune saves the
 * machine and stopping restores it, so somebody can listen to the theme in the
 * middle of a level and carry on from where they were. Leaving stops it too -
 * see {@code EmulatorActivity}, which calls {@link Music#stop} when the screen
 * goes away, because a player who wandered off has said they are done with the
 * music rather than with the game.
 */
public final class MusicUi {

    private static final String TAG = "Zedex";

    private final Activity activity;

    /** The game that is loaded, by the store's own key - the same thing
     *  {@code PokesUi} is told, and for the same reason: a fetched file is
     *  found by path rather than by hash. */
    private String libraryPath;

    public MusicUi(Activity activity) {
        this.activity = activity;
    }

    public void forGame(String path) {
        this.libraryPath = path;
    }

    /** Whether there is anything to offer, which is what decides if the row
     *  appears at all - most games have no music in the archive. */
    public boolean anything() {
        return Artwork.music(activity, libraryPath) != null;
    }

    /**
     * The tunes, and a way back.
     *
     * A file usually holds several - the same driver with a different tune
     * number - and they are named, so the page is a list of names. The stop
     * row is only there while something is playing, because a machine that
     * has not been put aside has nothing to be put back.
     */
    public MenuDrawer.Page page() {
        return page -> {
            AyFile file = read();

            if (file == null || file.songs.isEmpty()) {
                page.addNote(text(R.string.music_none));
                return;
            }

            if (Music.interrupted(activity)) {
                page.addItem(text(R.string.music_stop), R.drawable.ic_pause,
                             this::stop);
                page.addRule();
            }

            if (!file.misc.isEmpty()) page.addSection(file.misc);

            for (int at = 0; at < file.songs.size(); at++) {
                AyFile.Song song = file.songs.get(at);

                String label = song.name == null || song.name.isEmpty()
                        ? text(R.string.music_unnamed, at + 1) : song.name;

                page.addItem(label, R.drawable.ic_play, () -> play(song, label));
            }

            page.addNote(text(R.string.music_hint));
        };
    }

    private void play(AyFile.Song song, String label) {
        if (Music.play(activity, song)) {
            note(R.string.music_playing, label);
            return;
        }

        note(R.string.music_unplayable, label);
    }

    private void stop() {
        Music.stop(activity);
        note(R.string.music_stopped);
    }

    private AyFile read() {
        File file = Artwork.music(activity, libraryPath);
        if (file == null) return null;

        try {
            return AyFile.read(Files.readAllBytes(file.toPath()));
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + file, e);
            return null;
        }
    }

    private void note(int message, Object... arguments) {
        android.widget.Toast.makeText(activity, activity.getString(message, arguments),
                                      android.widget.Toast.LENGTH_SHORT).show();
    }

    private String text(int message, Object... arguments) {
        return activity.getString(message, arguments);
    }
}
