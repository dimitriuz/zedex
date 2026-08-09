package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.library.scrape.Candidate;
import dev.ldlab.zedex.library.scrape.Downloads;
import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.scrape.Provider;
import dev.ldlab.zedex.library.scrape.Scrape;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.library.scrape.Scrapers;
import dev.ldlab.zedex.work.Work;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import java.util.List;

/**
 * Scraping one game, with the parts that need a person.
 *
 * {@code Scrape} does everything that does not - build the question, ask it,
 * write the answer - and stops short of every decision: which of several
 * candidates, and whether to replace something typed by hand. Those need a
 * screen, so they are here, and keeping them apart is what lets the rest be
 * tested without one.
 *
 * The whole thing is one pass off the UI thread. A search is a round trip to
 * France and each picture is another - a cover is a {@code mediaJeu.php} call
 * exactly like a search is - so with the usual three that is four, and none of
 * them belongs on the main thread. {@code Work.alone} rather than the pool:
 * this is seconds to a minute and would hold a lane the short work wants.
 */
final class ScrapeOneGame {

    private static final String TAG = "Zedex";

    private final LibraryActivity activity;

    ScrapeOneGame(LibraryActivity activity) {
        this.activity = activity;
    }

    /**
     * The whole of it, from a selected row.
     *
     * Everything that can stop it does so before any work: no provider, no
     * path of its own. The hand-edit question is asked before the search
     * rather than after, so nobody waits for a lookup only to be told the
     * result will be thrown away.
     */
    void scrape(Entry entry) {
        Provider provider = Scrapers.preferred(activity);
        if (provider == null) return;

        String path = Metadata.relativePath(activity, entry.uri);
        if (path == null) return;

        if (Scrape.wouldOverwriteAHandEdit(activity, path)) {
            new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage(R.string.scrape_overwrite)
                    .setPositiveButton(R.string.scrape_menu,
                                       (dialog, which) -> look(provider, entry, path))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        look(provider, entry, path);
    }

    /** The search, and whatever the answer turns out to need. */
    private void look(Provider provider, Entry entry, String path) {
        ProgressDialog waiting = waiting();

        Work.alone("scrape", () -> {
            List<Candidate> found;

            try {
                found = Scrape.candidates(activity, provider, entry, path);
            } catch (ScrapeException e) {
                activity.runOnUiThread(() -> {
                    dismiss(waiting);
                    say(reasonFor(e));
                });
                return;
            }

            // One the provider is sure of needs nobody: that is what matching
            // on the file's own hash buys, and asking anyway would be a dialog
            // with one button.
            if (Scrape.certain(found)) {
                write(provider, found.get(0), path, waiting);
                return;
            }

            activity.runOnUiThread(() -> {
                dismiss(waiting);

                if (found.isEmpty()) {
                    say(activity.getString(R.string.scrape_nothing));
                    return;
                }
                choose(provider, found, path);
            });
        });
    }

    /**
     * Which of several, or whether the single uncertain one is right.
     *
     * A guess acted on silently is one game's cover on another for ever, so
     * even one candidate gets asked about when the provider is not sure - it
     * found it by the filename, which on a Spectrum collection is as often
     * wrong as right.
     */
    private void choose(Provider provider, List<Candidate> found, String path) {
        String[] labels = new String[found.size()];
        for (int at = 0; at < found.size(); at++) labels[at] = found.get(at).describe();

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.scrape_choose)
                .setItems(labels, (dialog, which) -> {
                    ProgressDialog waiting = waiting();
                    Work.alone("scrape-chosen",
                               () -> write(provider, found.get(which), path, waiting));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Off the UI thread in both callers. */
    private void write(Provider provider, Candidate candidate, String path,
                       ProgressDialog waiting) {
        Downloads.Result result = null;
        ScrapeException failure = null;

        try {
            result = Scrape.apply(activity, provider, new Http.Real(), candidate, path,
                                  Provider.Wanted.usual());
        } catch (ScrapeException e) {
            failure = e;
        }

        Downloads.Result finished = result;
        ScrapeException why = failure;

        activity.runOnUiThread(() -> {
            dismiss(waiting);

            if (why != null) {
                // The facts may well have been written before the pictures
                // were refused - Scrape.apply stores them first on purpose -
                // so this says what went wrong rather than "it failed".
                say(reasonFor(why));
            } else if (finished.saved > 0) {
                say(activity.getString(R.string.scrape_done_media, finished.saved));
            } else {
                say(activity.getString(R.string.scrape_done));
            }

            // Either way the row may have changed, and the pane is showing it.
            activity.metadataChanged();
        });
    }

    /**
     * What to tell somebody, per kind.
     *
     * The kinds exist so that a spent quota does not read as "something went
     * wrong": one of these is worth trying again in a minute, one is worth
     * trying tomorrow, and one needs a password fixed. A single message would
     * make all three look like the same shrug.
     */
    private String reasonFor(ScrapeException e) {
        Log.w(TAG, "scrape failed: " + e.kind, e);

        switch (e.kind) {
            case QUOTA_EXCEEDED:  return activity.getString(R.string.scrape_failed_quota);
            case BAD_CREDENTIALS: return activity.getString(R.string.scrape_failed_login);
            case CLOSED:          return activity.getString(R.string.scrape_failed_closed);
            case THREAD_LIMIT:
            case NETWORK:         return activity.getString(R.string.scrape_failed_network);
            default:              return activity.getString(R.string.scrape_nothing);
        }
    }

    private ProgressDialog waiting() {
        ProgressDialog dialog = new ProgressDialog(activity);
        dialog.setMessage(activity.getString(R.string.scrape_working));
        dialog.setIndeterminate(true);
        dialog.setCancelable(true);
        dialog.show();
        return dialog;
    }

    /** A dialog belonging to an activity that has gone throws on dismiss -
     *  see 9.13, which is that bug in the updater. */
    private void dismiss(ProgressDialog dialog) {
        if (dialog == null || activity.isFinishing() || activity.isDestroyed()) return;

        try {
            dialog.dismiss();
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "the progress dialog had already gone", e);
        }
    }

    private void say(String message) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}
