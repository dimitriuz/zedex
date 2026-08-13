package dev.ldlab.zedex.screen;

import dev.ldlab.zedex.R;
import dev.ldlab.zedex.library.ui.PlainText;
import dev.ldlab.zedex.view.Palette;

import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * A game's instructions, as somebody typed them up.
 *
 * ZXDB carries these for a fifth of its Spectrum entries and rather more of
 * them are text than PDF - 6,945 files against 3,723 - so an app that only
 * took the PDF was taking the smaller half. They are transcriptions of the
 * inlay, made over twenty-five years by whoever was doing it that evening.
 *
 * <b>Shown in a monospaced font, at whatever size makes the author's line
 * length fit.</b> These are hard-wrapped at about seventy-eight columns, with
 * rules under the headings, the odd table and the occasional bit of ASCII art;
 * re-flowing one to a phone's width turns a neat document into ragged
 * nonsense, and the line breaks cannot be thrown away because there is no way
 * to tell which of them are paragraph ends. So the text is scaled to the
 * window rather than the window imposed on the text - and where that would
 * make it too small to read, it stops shrinking and scrolls sideways instead.
 *
 * Ours rather than an {@code ACTION_VIEW} to whatever the phone has: a PDF has
 * a viewer on every phone and a {@code text/plain} very often does not, and
 * the ones that exist open it proportionally spaced, which is the one thing
 * these documents cannot survive.
 */
public final class InstructionsActivity extends ZedexActivity {

    private static final String TAG = "Zedex";

    /**
     * The same offer the PDF screen makes: this document, in whatever app the
     * phone has for a text file.
     *
     * Not the default, and for the reason this screen exists - not every
     * phone has anything for {@code text/plain}, and the ones that do re-wrap
     * it, which ruins a transcription hard-wrapped at seventy-eight columns
     * with rules under its headings. But "shown here" should not mean "and
     * nowhere else", and the two manual screens should offer the same thing.
     */
    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(getString(R.string.library_open))
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        String given = getIntent().getStringExtra(EXTRA_FILE);

        dev.ldlab.zedex.library.ui.Manuals.handOver(
                this, given == null ? null : Uri.parse(given),
                "text/plain", getDisplay(), null);

        return true;
    }

    /** The file to show, as a {@code file://} - {@code Artwork.manual}
     *  resolves it and {@code Manuals.open} decides it is one of these. */
    public static final String EXTRA_FILE = "dev.ldlab.zedex.extra.INSTRUCTIONS";

    /**
     * How small the text may get before it stops shrinking, and how large it
     * may get on a screen with room to spare.
     *
     * The floor is what stops a phone rendering seventy-eight columns at a
     * size nobody can read: below this it scrolls sideways instead, which is
     * awkward and legible rather than neat and useless.
     */
    private static final float SMALLEST_SP = 7f;
    private static final float LARGEST_SP = 15f;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // The manifest's label resolves in the phone's language, not this
        // screen's; see Language.
        setTitle(R.string.instructions_title);

        String text = read();

        if (text == null) {
            Toast.makeText(this, R.string.instructions_unreadable,
                           Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setContentView(page(text));
        fitToSafeArea();
    }

    private String read() {
        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_FILE);
        if (path == null) return null;

        File file = new File(Uri.parse(path).getPath() == null
                                     ? path : Uri.parse(path).getPath());

        try {
            return PlainText.decode(Files.readAllBytes(file.toPath()));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "cannot read " + file, e);
            return null;
        }
    }

    /**
     * Both directions, the horizontal one inside the vertical.
     *
     * A {@code HorizontalScrollView} measures its child unbounded, which is
     * exactly what is wanted here - the text is as wide as its longest line
     * and the view scrolls to reach it - and nesting it inside the vertical
     * scroller rather than the other way round keeps the ordinary gesture,
     * which is dragging up and down, on the outer one.
     */
    private ViewGroup page(String text) {
        TextView view = new TextView(this);

        view.setText(text);
        view.setTextColor(Palette.TEXT);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeThatFits());
        view.setGravity(Gravity.START);
        view.setPadding(pixels(12), pixels(12), pixels(12), pixels(24));

        // Never wrapped: see the class comment. A TextView wraps by default
        // and the only way to stop it is to let it measure as wide as it
        // wants inside something that scrolls.
        view.setHorizontallyScrolling(true);

        HorizontalScrollView sideways = new HorizontalScrollView(this);
        sideways.addView(view);

        ScrollView page = new ScrollView(this);
        page.setBackgroundColor(Palette.BACKING);
        page.addView(sideways);

        return page;
    }

    /**
     * The largest size at which the author's line length still fits.
     *
     * Monospaced, so one character's width is every character's width and the
     * sum is arithmetic rather than a measurement: the framework's own
     * monospace advance is close enough to 0.6 of the text size that this
     * lands within a character across the phones and tablets this runs on,
     * and the clamps below cover the rest.
     */
    private float sizeThatFits() {
        float density = getResources().getDisplayMetrics().scaledDensity;
        float widthPx = getResources().getDisplayMetrics().widthPixels
                        - 2f * pixels(12);

        float perCharacterPx = widthPx / PlainText.COLUMNS;
        float sp = perCharacterPx / 0.6f / density;

        return Math.max(SMALLEST_SP, Math.min(LARGEST_SP, sp));
    }

    private int pixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
