package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.EmulatorActivity;
import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.catalogue.Catalogues;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.view.Palette;
import dev.ldlab.zedex.welcome.Page;
import dev.ldlab.zedex.welcome.Step;
import dev.ldlab.zedex.welcome.Steps;
import dev.ldlab.zedex.welcome.pages.DonePage;
import dev.ldlab.zedex.welcome.pages.FoldersPage;
import dev.ldlab.zedex.welcome.pages.LanguagePage;
import dev.ldlab.zedex.welcome.pages.MachinePage;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The first run: a page at a time, every one of them skippable.
 *
 * <b>Skippable is the point.</b> A screen between somebody and the machine
 * they came for is a toll booth - StartPanel's own note on the demo tape says
 * so - and seven questions is a long one. Page one offers a way straight past
 * all of it, every page offers a way past itself, and skipping writes nothing,
 * so the app's own defaults stay in force.
 *
 * <b>It runs before Fuse starts</b>, and that is a simplification rather than a
 * limitation: Machine.arguments() puts --machine and --joystick-1-output on
 * Fuse's command line out of these very preferences, and FuseSettings reads
 * them after start. So a page writes a preference and stops. Nothing here may
 * call into FuseNative - see MachinePage for the one place that costs
 * something.
 */
public final class WelcomeActivity extends ZedexActivity {

    /** Whether the caller is staying alive behind this - see {@link #start}. */
    public static final String EXTRA_RETURN = "welcomeReturn";

    private static final String STATE_PAGE = "page";

    private Page page;
    private Step step;

    /** Asked once: it needs a Context, and Steps is a pure function. */
    private boolean hasCatalogue;

    @Override
    protected int title() {
        return R.string.welcome_title;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        hasCatalogue = Catalogues.any(this);

        page = state != null && state.containsKey(STATE_PAGE)
                ? Page.valueOf(state.getString(STATE_PAGE))
                : Page.WELCOME;

        show(page);
        fitToSafeArea();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_PAGE, page.name());
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);

        if (step instanceof FoldersPage) {
            ((FoldersPage) step).onActivityResult(request, result, data);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // A settings-page permission has no onActivityResult - only a resume
        // can notice the answer, or a folder picked before the permission
        // existed is never applied and the row stalls until a restart.
        if (step instanceof FoldersPage) ((FoldersPage) step).onResumed();
    }

    /** Which page this is, and the whole of what the wizard knows. */
    private void show(Page which) {
        page = which;
        step = stepFor(which);

        LinearLayout column = Cards.column(this, 150);

        TextView title = new TextView(this);
        title.setTextSize(26);
        title.setTextColor(Palette.TEXT);
        title.setText(step.title());
        column.addView(title);

        TextView blurb = new TextView(this);
        blurb.setTextSize(15);
        blurb.setTextColor(Palette.MUTED);
        blurb.setText(step.blurb());
        column.addView(blurb);

        column.addView(step.body(this, preferences));

        // The way on, and the ways past. DONE has neither: its only button
        // starts the machine, because there is nothing left to skip.
        if (page == Page.DONE) {
            column.addView(Cards.choice(this, R.string.welcome_start,
                    R.string.welcome_start_hint, v -> finishSetup(), true));
        } else {
            column.addView(Cards.choice(this, R.string.welcome_next, 0,
                    v -> next(), true));
            column.addView(Cards.choice(this, R.string.welcome_skip, 0,
                    v -> skip(), false));
        }

        // Only on page one: one offer to leave the whole thing, where it can
        // be taken before any of it has been read.
        if (page == Page.WELCOME) {
            column.addView(Cards.choice(this, R.string.welcome_later,
                    R.string.welcome_later_hint, v -> finishSetup(), false));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Cards.BACK);
        scroll.addView(column);
        setContentView(scroll);
    }

    /**
     * @return null for a {@link Page} Tasks 7-9 have not built a {@link Step}
     *         for yet - <b>scaffold, not a real "no page"</b>. {@link #next},
     *         {@link #skip} and {@link #back} all walk past a null the way
     *         {@link #forwardFrom}/{@link #backwardFrom} do, so nothing here
     *         is ever shown; WELCOME, FOLDERS, MACHINE and DONE build one, and
     *         CONTROLS, SCREEN, LIBRARY and SCRAPING do not yet. Once every
     *         case does, this branch is unreachable and the walking in
     *         {@link #forwardFrom}/{@link #backwardFrom} never iterates more
     *         than once - that is the moment to delete the null handling on
     *         both ends together.
     */
    private Step stepFor(Page which) {
        switch (which) {
            case WELCOME: return new LanguagePage(this::recreate);
            case FOLDERS: return new FoldersPage(this);
            case MACHINE: return new MachinePage();
            case DONE:    return new DonePage();
            default:
                return null;
        }
    }

    /**
     * The next page after {@code from} whose {@link Step} is actually built,
     * or null past the end. See {@link #stepFor}'s own comment: this is the
     * half of the scaffold that keeps {@link #next}/{@link #skip} from
     * landing on a page Tasks 6-9 have not written yet.
     */
    private Page forwardFrom(Page from) {
        Page candidate = Steps.after(from, preferences, hasCatalogue);

        while (candidate != null && stepFor(candidate) == null) {
            candidate = Steps.after(candidate, preferences, hasCatalogue);
        }

        return candidate;
    }

    /** {@link #forwardFrom}, walking the other way for {@link #back}. */
    private Page backwardFrom(Page from) {
        Page candidate = Steps.before(from, preferences, hasCatalogue);

        while (candidate != null && stepFor(candidate) == null) {
            candidate = Steps.before(candidate, preferences, hasCatalogue);
        }

        return candidate;
    }

    /** Forwards: the page settles whatever it was holding, then on. */
    private void next() {
        step.apply(preferences);
        go(forwardFrom(page));
    }

    /** Past: apply is not called, so a skipped page writes nothing. */
    private void skip() {
        go(forwardFrom(page));
    }

    /**
     * Back walks the pages that were actually shown, and leaves from the
     * first one - so there is no exit that skips the storage work below, and
     * no way to leave by accident from the middle.
     *
     * Reached through {@link #onBackWanted}, which {@link ZedexActivity}
     * already calls from a callback it registers itself, held in its own
     * field, at {@code PRIORITY_DEFAULT} - overriding that hook is all this
     * screen needs to do, rather than registering a second callback of its
     * own beside it.
     */
    private void back() {
        Page previous = backwardFrom(page);

        if (previous == null) finishSetup();
        else show(previous);
    }

    @Override
    protected void onBackWanted() {
        back();
    }

    private void go(Page nextPage) {
        if (nextPage == null) finishSetup();
        else show(nextPage);
    }

    /**
     * Done asking, however few pages were answered.
     *
     * The ROMs go into whatever folder was settled on, which is why this is
     * the moment for it and not onCreate. pinRoot writes statesRoot even when
     * the folders page was skipped, deliberately: leaving the default
     * unrecorded would let a permission granted later silently move where the
     * app looks.
     */
    private void finishSetup() {
        Storage.pinRoot(this);
        Storage.createFolders(this);
        Storage.installRoms(this);

        // A tape like any other from here on; the summary said where it is.
        Storage.installDemo(this);

        preferences.edit().putBoolean(Storage.KEY_SETUP_DONE, true).apply();

        // Where to go is this screen's to decide, because it is the thing that
        // has just settled the two preferences startsInLibrary reads.
        if (!getIntent().getBooleanExtra(EXTRA_RETURN, false)) {
            startActivity(new Intent(this,
                    SettingsActivity.startsInLibrary(preferences)
                            ? LibraryActivity.class
                            : EmulatorActivity.class));
        }

        finish();
    }

    /**
     * @param returnHere whether the caller is staying alive behind this. True
     *                   for EmulatorActivity reached by a file manager's
     *                   ACTION_VIEW - it is singleInstance, so it is still
     *                   there with its original intent and its onResume runs
     *                   startEmulator again - and for the Settings row. False
     *                   for LibraryActivity's launcher path, which finishes
     *                   itself: it cannot make its own hand-over decision,
     *                   since the answer it depends on is what this screen is
     *                   about to write.
     */
    public static void start(Activity from, boolean returnHere) {
        Intent intent = new Intent(from, WelcomeActivity.class);
        intent.putExtra(EXTRA_RETURN, returnHere);
        from.startActivity(intent);
    }
}
