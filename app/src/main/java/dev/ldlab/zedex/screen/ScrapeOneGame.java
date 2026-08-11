package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.Entry;
import dev.ldlab.zedex.library.meta.Metadata;
import dev.ldlab.zedex.library.scrape.Candidate;
import dev.ldlab.zedex.library.scrape.Http;
import dev.ldlab.zedex.library.scrape.Provider;
import dev.ldlab.zedex.library.scrape.Blend;
import dev.ldlab.zedex.library.scrape.ScrapeException;
import dev.ldlab.zedex.library.scrape.Scrapers;
import dev.ldlab.zedex.work.Work;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.util.Log;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Scraping one game, with the parts that need a person.
 *
 * {@code Blend} runs the whole multi-source loop and stops short of the two
 * decisions left: which of several candidates a source found, and which of
 * several pictures to keep when sources disagree. Both need a screen, so they
 * are here, and keeping them apart is what lets the loop be tested without
 * one.
 *
 * <b>A scrape fills gaps and cannot overwrite - see {@link Blend} - which is
 * why there is no confirmation dialog before it runs any more.</b> There used
 * to be one, asking whether to replace a hand-edited row; it existed only
 * because this class called {@code Scrape.apply}, whose {@code owned()}
 * rebuilds the row from the provider's own {@code Meta} and so replaced every
 * field a person had typed, including turning {@code isMine()} off. {@code
 * Blend.run} merges with {@code Merge.of}, which fills what is missing and
 * never touches what is already there, so the question the dialog used to ask
 * no longer has an unsafe answer to guard against.
 *
 * The whole thing is one pass off the UI thread. A search is a round trip to
 * France and each picture is another - a cover is a {@code mediaJeu.php} call
 * exactly like a search is - and now there may be several sources, so the
 * total is larger than it used to be and still none of it belongs on the main
 * thread. {@code Work.alone} rather than the pool: this is seconds to a
 * minute and would hold a lane the short work wants.
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
     * Everything that can stop it does so before any work: no source
     * enabled, no path of its own.
     */
    void scrape(Entry entry) {
        List<Provider> sources = Scrapers.enabled(activity);
        if (sources.isEmpty()) return;

        String path = Metadata.relativePath(activity, entry.uri);
        if (path == null) return;

        // No hand-edit confirmation any more: a scrape fills gaps and cannot
        // overwrite a typed value, so there is nothing to warn about.
        look(sources, entry, path);
    }

    /**
     * Every source in turn, off the UI thread, then the result is shown.
     *
     * Package-private rather than private so {@code
     * ScrapeOneGameHandEditTest} can drive the merge directly, with a fake
     * source list and no dialog: {@code scrape(Entry)} itself calls {@code
     * Scrapers.enabled}, which is a real, network-backed source for this
     * build, and a hand-edit-survival test must not depend on a live service
     * answering in a particular way.
     */
    void look(List<Provider> sources, Entry entry, String path) {
        ProgressDialog waiting = waiting();

        Work.alone("scrape", () -> {
            Blend.Result result = Blend.run(activity, sources, new Http.Real(activity),
                                            entry, path, Scrapers.wanted(activity),
                                            Blend.Media.OFFER_ALTERNATIVES,
                                            this::askOnTheUiThread,
                                            // Nothing to cancel: one game is
                                            // seconds. Cancel in the
                                            // per-source chooser already lets
                                            // one source be skipped - see
                                            // askOnTheUiThread - but that
                                            // moves the loop on to the next
                                            // source rather than stopping the
                                            // scrape, and there is
                                            // deliberately no separate
                                            // "stop everything" escape for a
                                            // run this short.
                                            () -> false);

            activity.runOnUiThread(() -> {
                dismiss(waiting);
                finish(result, entry, path);
            });
        });
    }

    /**
     * Nothing to ask about goes straight in; anything contested gets the
     * sheet.
     *
     * The facts are already stored either way - Blend writes them before this
     * is reached, and for the same reason a sweep's fill does: a scrape that
     * got the metadata has still improved the row, whatever happens to the
     * pictures next.
     */
    private void finish(Blend.Result result, Entry entry, String path) {
        if (result.staged.isEmpty()) {
            say(reasonFor(result));
            activity.metadataChanged();
            return;
        }

        if (!result.anythingContested()) {
            commit(result.staged, path, result);
            return;
        }

        ArtworkChoice.show(activity, entry.name, result.staged,
                           chosen -> commit(chosen, path, result));
    }

    private void commit(List<Blend.Staged> chosen, String path, Blend.Result result) {
        Work.alone("scrape-commit", () -> {
            int installed = Blend.commit(activity, path, chosen);

            activity.runOnUiThread(() -> {
                say(installed > 0
                        ? activity.getString(R.string.scrape_done_media, installed)
                        : reasonFor(result));
                activity.metadataChanged();
            });
        });
    }

    /** How often the wait for an answer looks up to see whether the activity
     *  it was posted to is still there to answer it. The same 200ms {@code
     *  ScrapeManyActivity.CHOICE_POLL_MS} and {@code Blend.CANCEL_POLL_MS}
     *  both use. */
    private static final long ANSWER_POLL_MS = 200;

    /**
     * Which of several, on the UI thread, with the worker parked behind a
     * latch.
     *
     * The same shape {@code Sweep.Watcher.chooseFrom} already uses, but this
     * follows {@code ScrapeManyActivity.ask} rather than that one, and for its
     * reason rather than {@code chooseFrom}'s: an {@code AlertDialog} fires
     * {@code setOnCancelListener} on a cancel, but not when the activity that
     * owns it is torn down underneath it - destroying the library while this
     * dialog is up used to block this {@code Work.alone} thread for the rest
     * of the process, holding a destroyed {@code Activity}. The check inside
     * the posted lambda covers the same activity already being gone by the
     * time the lambda runs at all, in which case nothing is shown and the
     * latch is freed at once - the old code, before there was a dialog to
     * park behind, simply returned from the worker in that case, and this
     * restores that.
     */
    private Candidate askOnTheUiThread(String sourceName, List<Candidate> found,
                                       String game) {
        final CountDownLatch answered = new CountDownLatch(1);
        final Candidate[] chosen = new Candidate[1];

        String[] labels = new String[found.size()];
        for (int at = 0; at < found.size(); at++) labels[at] = found.get(at).describe();

        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                answered.countDown();
                return;
            }

            new AlertDialog.Builder(
                            activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(activity.getString(R.string.scrape_choose_from, sourceName))
                    .setItems(labels, (dialog, which) -> {
                        chosen[0] = found.get(which);
                        answered.countDown();
                    })
                    .setOnCancelListener(dialog -> answered.countDown())
                    .setNegativeButton(android.R.string.cancel,
                                       (dialog, which) -> answered.countDown())
                    .show();
        });

        try {
            // Polled rather than a single indefinite await - see the class
            // doc above. isFinishing()/isDestroyed() is this class's
            // equivalent of ScrapeManyActivity's own "cancelled" field: there
            // is nothing else here that could free this thread early.
            while (!answered.await(ANSWER_POLL_MS, TimeUnit.MILLISECONDS)) {
                if (activity.isFinishing() || activity.isDestroyed()) return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return chosen[0];
    }

    /**
     * What to tell somebody when there is no picture to speak for the run:
     * whether anybody actually improved the row, and only when nobody did,
     * why not.
     *
     * Consulted wins over failures, not the other way around. A game two
     * sources were asked about - one filling in a fact, the other's quota
     * spent - has still been improved, and reporting the failed source's
     * reason as though it were the outcome would be a true fact about the
     * wrong source. Under one source these two were the same question;
     * they stopped being the same question the moment there could be more
     * than one answer.
     */
    private String reasonFor(Blend.Result result) {
        if (!result.consulted.isEmpty()) return activity.getString(R.string.scrape_done);
        if (!result.failures.isEmpty()) return reasonFor(result.failures.get(0).why);

        return activity.getString(R.string.scrape_nothing);
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
