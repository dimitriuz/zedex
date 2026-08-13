package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.EmulatorActivity;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.library.ui.GameInfoView;
import dev.ldlab.zedex.storage.Prefs;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Everything known about one game, on a screen of its own - which is now
 * {@link GameInfoView} in an activity and nothing else.
 *
 * The library's pane is a strip beside a grid: it has room for a few facts,
 * one line each, and it earns that room by not trying to show a description
 * as well. A scraped description runs to three paragraphs often enough that
 * the pane's own version of it was squeezed to 26px in landscape - a scroll
 * bar with nothing to scroll in - which is what this screen exists to fix.
 * The magnifier in the pane opens it; see {@code LibraryActivity.showGameInfo}.
 *
 * <b>The page itself is not built here.</b> It was, twice: this screen and the
 * second screen's panel each had their own copy of the same gallery, name,
 * facts, description and extras, and the two drifted apart in both directions
 * - the panel had no extras rows, this screen had no autoplay and no measured
 * cover. There is one implementation now and it is the view, so what is left
 * here is only what is different about being an activity: three intent extras,
 * which action row to ask for, and stopping the video on pause.
 *
 * Addressed by the game's path relative to the content tree, not by a parsed
 * {@link dev.ldlab.zedex.library.meta.Meta}: that path is the key both the
 * metadata store and the artwork are found by, and looking both up on the
 * other side rather than carrying a copy through an Intent means this screen
 * cannot be showing something the store no longer says.
 */
public final class GameInfoActivity extends ZedexActivity {

    /** The game's path relative to the content tree - {@link Metadata#relativePath}. */
    public static final String EXTRA_PATH = "dev.ldlab.zedex.extra.GAME_PATH";

    /** The file's own name, which is all this screen has to show until the store answers. */
    public static final String EXTRA_NAME = "dev.ldlab.zedex.extra.GAME_NAME";

    /**
     * The game's own file, when this screen was opened from the library.
     *
     * <b>Which action row this screen wears turns on it</b> - see {@link
     * #configureRow}. There are two ways in and they are two different states:
     * from the machine's own ⓘ, where a game is running and what is wanted is
     * the way back to it; and from the library's pane, where nothing is
     * running and what is wanted is Play. Absent means the first, which is
     * also what makes the machine's own call need no change.
     *
     * The rule underneath, which the panel follows too: the row reflects
     * whether there is a machine behind this screen.
     */
    public static final String EXTRA_URI = "dev.ldlab.zedex.extra.GAME_URI";

    /** The whole of this screen - see the class comment. Built in {@code
     *  onCreate} and never as a field initialiser, which would run before
     *  the activity has a context worth building against. */
    private GameInfoView view;

    /** True when {@link #EXTRA_URI} was absent - the machine sent us, not the
     *  library. {@link #onBackWanted} reads this so the system Back button
     *  runs the same hand-over as the ‹ icon rather than disagreeing with it;
     *  see that method. */
    private boolean cameFromTheMachine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The manifest label is resolved in the phone's language rather than
        // this screen's, so the title is set here; see Language. Still set
        // although there is no title strip: the task switcher reads it.
        setTitle(R.string.library_info);
        if (getActionBar() != null) getActionBar().hide();

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        String file = getIntent().getStringExtra(EXTRA_URI);
        cameFromTheMachine = file == null;

        view = new GameInfoView(this);

        // The same preference the panel reads, and re-read here for the same
        // reason SecondScreen re-reads it rather than trusting a value handed
        // down once: Settings is as liable to have changed it since the last
        // time this screen was open as anything else. See CLAUDE.md, "a
        // setting has to be applied as well as stored" - GameInfoView's own
        // default (true) is what everyone got here until this line existed.
        view.setAutoplay(preferences.getBoolean(Prefs.KEY_LIBRARY_VIDEO_AUTOPLAY, true));

        configureRow(file, path);

        setContentView(view);
        fitToSafeArea();

        if (path != null) view.showEntry(path, name);
    }

    /**
     * The ‹ icon and the system Back button must agree, and only one of them
     * used to: the icon hands over to {@code EmulatorActivity} because that
     * activity is {@code singleInstance} and a plain {@code finish()} lands
     * in the library's task instead - see {@link #handOverToMachine} - but
     * nothing overrode this, so Back kept doing the platform's own default of
     * {@code finish()} on the very same screen. The library variant is
     * unaffected: {@code finish()} is already correct there, so the platform
     * default stands.
     */
    @Override
    protected void onBackWanted() {
        if (cameFromTheMachine) handOverToMachine();
        else super.onBackWanted();
    }

    /**
     * The hand-over both the ‹ icon and {@link #onBackWanted} run when this
     * screen was opened from the machine - one method, so the two can never
     * drift apart the way icon and Back just had. See the leading action in
     * {@link #configureRow} for why a plain {@code finish()} is wrong here.
     */
    private void handOverToMachine() {
        startActivity(new Intent(this, EmulatorActivity.class));
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();

        // One of the times a video must not be left running - see CLAUDE.md.
        view.release();
    }

    /**
     * Which row this screen wears, and the rule underneath it: the row
     * reflects whether there is a machine behind this screen.
     *
     * {@link #EXTRA_URI} present means the library sent us and nothing is
     * running, so the game's own actions belong here and Play leads. Absent
     * means the machine's own ⓘ sent us and the machine is behind this
     * window, so what is wanted is the way back to it - and, because that
     * machine is a different activity in a different task (see the leading
     * action below), getting there is a hand-over, not a plain {@code
     * finish()}.
     *
     * Back leads in one and trails in the other, which is deliberate: from
     * the machine it is the reason you are leaving, and from the library it
     * is the last resort after Play.
     *
     * The manual and the music are on neither list. They are the view's own -
     * it asks {@code Artwork} for them off the UI thread and reveals each
     * only when the answer arrives - and it puts them between the leading
     * actions and the trailing ones whatever a host adds.
     */
    private void configureRow(String file, String path) {
        if (file != null) {
            Uri uri = Uri.parse(file);

            view.setPrimaryAction(R.string.library_play, () -> {
                // The same hand-over a row in the library makes - see
                // LibraryActivity.openGame, whose own comment explains why
                // the grant travels with it.
                startActivity(new Intent(Intent.ACTION_VIEW, uri, this, EmulatorActivity.class)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(EmulatorActivity.EXTRA_LIBRARY_PATH, path));
                finish();
            });

            // Finishing this screen is what returns to the library, so this
            // needs no intent of its own.
            view.addTrailingAction(R.drawable.ic_chevron_left, R.string.menu_back, this::finish);
            return;
        }

        // The machine is behind this window, but not in the way finish()
        // would reach: EmulatorActivity is singleInstance, so it is always
        // alone in a task of its own, and this screen sits in whichever task
        // opened it (the library's) - finishing it lands there, not on the
        // machine. handOverToMachine is also what onBackWanted runs, so the
        // system Back button lands in the same place as this icon rather
        // than falling through to the platform's plain finish() - measured
        // on the device to actually resume EmulatorActivity with the game
        // still running, where a plain finish() measured landing on the
        // library.
        view.addLeadingAction(R.drawable.ic_chevron_left, R.string.menu_back, this::handOverToMachine);

        // The machine's own menu, which only the machine can open: a sheet
        // built over another activity's window is not something a second
        // activity can raise, so this asks rather than opens.
        view.addTrailingAction(R.drawable.ic_menu, R.string.menu_button, () -> {
            startActivity(new Intent(this, EmulatorActivity.class)
                    .putExtra(EmulatorActivity.EXTRA_OPEN_MENU, true));
            finish();
        });

        // Out of the game altogether: what it does is close the content, and
        // where it leaves you is the library.
        view.addTrailingAction(R.drawable.ic_close, R.string.library_title, () -> {
            startActivity(new Intent(this, LibraryActivity.class)
                    .putExtra(LibraryActivity.EXTRA_FROM_MENU, true)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                              | Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });
    }
}
