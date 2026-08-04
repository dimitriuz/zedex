package dev.ldlab.zedex.feedback;

import dev.ldlab.zedex.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.Toast;

/**
 * Sends a bug report, if the user decides to.
 *
 * The whole design is in one rule: <b>the user sees exactly what would be sent,
 * and can change it, before anything leaves the device.</b> That is what makes it
 * honest to say the app collects nothing — the report is built on request, shown
 * in a box that can be edited, and handed to the user's own mail app. There is no
 * server here, nothing is uploaded, and nothing is kept afterwards.
 *
 * It matters because the report is not entirely about the machine: the name of
 * the program that was loaded and the path of the data folder are the user's, and
 * the only decent way to include them is to let the user look at them first.
 *
 * The report goes in the mail body rather than as an attachment. A {@code mailto:}
 * intent has no attachments, and a body is better anyway: what is sent is
 * literally the text that was just read, with no second copy to wonder about.
 */
public final class Feedback {

    /**
     * Where a report goes. Also in {@code docs/PRIVACY.md} and in the Play
     * listing, and all three should say the same thing.
     *
     * An address on the project's own domain rather than a personal one, and a
     * forwarded one rather than a mailbox: it can be retired without touching a
     * published listing or a released app, which matters for the address a store
     * shows the world.
     */
    private static final String ADDRESS = "zedex.support@ldlab.dev";

    private Feedback() {
    }

    /** From About: build a report about right now, and offer to send it. */
    public static void compose(Activity activity) {
        show(activity, Diagnostics.report(activity), false);
    }

    /**
     * At startup, when the last run ended badly.
     *
     * Asked once: the file goes whether the answer is yes or no, because a report
     * somebody has declined is not a better question tomorrow.
     */
    public static void offerLastCrash(Activity activity) {
        String crash = Crashes.pending(activity);
        if (crash == null) return;

        new AlertDialog.Builder(activity,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.feedback_crashed_title)
                .setMessage(R.string.feedback_crashed_message)
                .setPositiveButton(R.string.feedback_look, (dialog, which) -> {
                    Crashes.forget(activity);
                    show(activity, crash, true);
                })
                .setNegativeButton(R.string.feedback_discard,
                        (dialog, which) -> Crashes.forget(activity))
                .setCancelable(false)
                .show();
    }

    /**
     * The report, in a box, with somewhere to type above it.
     *
     * Editable on purpose and not only for privacy: the most useful line in any
     * report is the one saying what the person was doing, and it is the one thing
     * the app cannot work out for itself.
     */
    private static void show(Activity activity, String report, boolean crash) {
        EditText box = new EditText(activity);

        box.setText(activity.getString(R.string.feedback_template) + "\n\n" + report);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.setSingleLine(false);
        box.setVerticalScrollBarEnabled(true);
        box.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);

        AlertDialog dialog = new AlertDialog.Builder(activity,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(crash ? R.string.feedback_title_crash : R.string.feedback_title)
                .setMessage(R.string.feedback_explain)
                .setView(box)
                .setPositiveButton(R.string.feedback_send, (ignored, which) ->
                        send(activity, box.getText().toString(), crash))
                .setNeutralButton(R.string.feedback_copy, (ignored, which) ->
                        copy(activity, box.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.show();
    }

    private static void copy(Activity activity, String report) {
        ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);

        if (clipboard == null) return;

        clipboard.setPrimaryClip(ClipData.newPlainText("Zedex", report));
        Toast.makeText(activity, R.string.feedback_copied, Toast.LENGTH_SHORT).show();
    }

    /**
     * Hands it to whatever sends mail here.
     *
     * ACTION_SENDTO with a {@code mailto:} URI rather than ACTION_SEND, so the
     * chooser offers mail apps and not every app that can take a piece of text.
     *
     * If nothing on the device answers to mail — which happens, and on Android 11
     * and later would also happen to an app that had not declared the query in
     * its manifest — the report is put on the clipboard instead. Better than a
     * button that appears to do nothing.
     */
    private static void send(Activity activity, String report, boolean crash) {
        String subject = activity.getString(crash ? R.string.feedback_subject_crash
                                                  : R.string.feedback_subject);

        Intent mail = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
        mail.putExtra(Intent.EXTRA_EMAIL, new String[] { ADDRESS });
        mail.putExtra(Intent.EXTRA_SUBJECT, subject);
        mail.putExtra(Intent.EXTRA_TEXT, report);

        try {
            activity.startActivity(mail);
        } catch (ActivityNotFoundException e) {
            copy(activity, report);
            Toast.makeText(activity,
                           activity.getString(R.string.feedback_no_mail, ADDRESS),
                           Toast.LENGTH_LONG).show();
        }
    }
}
