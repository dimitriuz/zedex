package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.view.SafeArea;
import dev.ldlab.zedex.cheats.PokeDatabase;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * What this is, which version of it, and where the source lives.
 *
 * A GPL program should say so and say where to get it, and an emulator built on
 * someone else's core should say whose. That is most of what this screen is for;
 * the rest is being able to answer "which build is this?" without a cable.
 *
 * The version is asked of the package manager and the commit and date come from
 * resources that the build stamps out of git, so nothing here can claim to be a
 * version or a commit it is not.
 */
public final class AboutActivity extends Activity {

    private static final String SOURCE = "https://github.com/dimitriuz/zedex";

    private static final int TEXT = 0xffededf2;
    private static final int DIM = 0xff9a9aa5;
    private static final int LINK = 0xff00b0c8;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView page = new ScrollView(this);
        // The banner's own background, so the picture does not end in a seam.
        page.setBackgroundColor(0xff0f0e13);
        page.addView(content());

        setContentView(page);

        // Nothing of ours under the status bar or the camera; see SafeArea.
        SafeArea.fit(findViewById(android.R.id.content));
    }

    private View content() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, 0, pixels(24));

        // The store graphic, which already says the name and what it is; the
        // words below repeat them for anything that reads rather than looks.
        ImageView banner = new ImageView(this);
        banner.setImageResource(R.drawable.feature);
        banner.setAdjustViewBounds(true);
        banner.setScaleType(ImageView.ScaleType.FIT_CENTER);
        banner.setContentDescription(getString(R.string.about_banner));
        column.addView(banner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        column.addView(text(getString(R.string.app_name), 28, TEXT, pixels(20)));
        column.addView(text(getString(R.string.about_tagline), 16, DIM, pixels(4)));
        column.addView(text(getString(R.string.about_what), 14, TEXT, pixels(20)));
        column.addView(text(build(), 14, DIM, pixels(20)));
        column.addView(text(getString(R.string.about_licence), 13, DIM, pixels(16)));
        column.addView(text(cheats(), 13, DIM, pixels(16)));
        column.addView(link());

        return column;
    }

    /** Version, build number, commit and date - as much as the build knew. */
    private String build() {
        String version = "";
        long code = 0;

        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName;
            code = info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            // Cannot happen for our own package, and an empty line is honest.
        }

        String commit = getString(R.string.build_commit);
        String date = getString(R.string.build_date);

        String built = getString(R.string.about_version, version, code);
        if (!commit.isEmpty() && !date.isEmpty()) {
            built += "\n" + getString(R.string.about_built, commit, date);
        }

        return built;
    }

    /**
     * Whose cheats these are.
     *
     * The pokes shipped with the app are other people's work - The Tipshop's
     * collection, gathered by somebody else again - so the screen that says
     * what this program is made of has to say so, with the numbers read out of
     * the database itself rather than typed in here where they would go stale.
     */
    private String cheats() {
        PokeDatabase database = PokeDatabase.open(this);
        if (database == null) return getString(R.string.about_cheats_source);

        String source = database.meta("source");
        String games = database.meta("games");
        String trainers = database.meta("trainers");
        database.close();

        if (source == null || games == null || trainers == null) {
            return getString(R.string.about_cheats_source);
        }

        return getString(R.string.about_cheats, games, trainers) + "\n" + source;
    }

    /** The source, which the licence obliges and curiosity wants. */
    private View link() {
        TextView view = text(SOURCE, 15, LINK, pixels(20));

        view.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE)));
            } catch (android.content.ActivityNotFoundException e) {
                // A device with no browser at all; saying so beats doing nothing.
                Toast.makeText(this, SOURCE, Toast.LENGTH_LONG).show();
            }
        });

        return view;
    }

    private TextView text(String words, float size, int colour, int above) {
        TextView view = new TextView(this);

        view.setText(words);
        view.setTextSize(size);
        view.setTextColor(colour);
        view.setLineSpacing(0, 1.2f);
        view.setGravity(Gravity.START);
        view.setPadding(pixels(20), above, pixels(20), 0);

        return view;
    }

    private int pixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
