package dev.ldlab.zedex.welcome.pages;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.screen.Links;
import dev.ldlab.zedex.storage.Storage;
import dev.ldlab.zedex.view.Cards;
import dev.ldlab.zedex.welcome.Step;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The summary: the intro tape, and two ways to reach the project from here on
 * - feedback and issues, and the two places to put something in the hat.
 *
 * {@code https} needs no {@code <queries>} entry - a web intent carries its
 * own exemption from package-visibility filtering, unlike {@code mailto} -
 * so nothing in the manifest has to change for these rows.
 */
public final class DonePage implements Step {

    @Override
    public int title() {
        return R.string.welcome_done_title;
    }

    @Override
    public int blurb() {
        return R.string.welcome_done_message;
    }

    @Override
    public View body(Context context, SharedPreferences preferences) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        // Said on the way past rather than asked about - the same line
        // StartPanel.describeFolders showed on the old first-run panel: the
        // tape is a tape like any other from here on.
        TextView note = Cards.note(context);
        note.setText(context.getString(R.string.setup_demo,
                Storage.demoTape(context).getAbsolutePath()));
        column.addView(Cards.spaced(context, note));

        column.addView(Cards.choice(context, R.string.welcome_issues,
                R.string.welcome_issues_hint,
                v -> open(context, Links.ISSUES), false));

        // Named after their own destination, the same reasoning AboutActivity
        // gives for its own copies of these two rows - and no caption, since
        // the name already says where it goes.
        column.addView(Cards.choiceOf(context, "Ko-fi", 0,
                v -> open(context, Links.KO_FI), false));
        column.addView(Cards.choiceOf(context, "Buy Me a Coffee", 0,
                v -> open(context, Links.COFFEE), false));

        return column;
    }

    private static void open(Context context, String url) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            // A device with no browser at all; the row simply does nothing.
        }
    }
}
