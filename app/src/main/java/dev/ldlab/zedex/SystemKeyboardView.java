package dev.ldlab.zedex;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/**
 * Somewhere for the device's own keyboard to type into.
 *
 * The third keyboard is not a picture of one: it is whichever input method the
 * phone already has, which is worth having because it is the keyboard its owner
 * can touch-type on, it has their own layout and their own key size, and it is
 * the only one of the three that offers a language the Spectrum's forty keys do
 * not.
 *
 * A view one pixel across, because an input method needs something focused to
 * talk to and nothing needs to be seen: what it types goes to the machine, and
 * the machine's screen is what shows it.
 *
 * <b>An IME commits text; it does not press keys.</b> That is the thing to know
 * here. A soft keyboard hands over a string through
 * {@link InputConnection#commitText}, and only sends real key events for a few
 * editing keys, so the characters take {@link FuseNative#character} - which
 * reaches Fuse's own idea of a typed key and comes out as the Spectrum keys it
 * needs, SYMBOL SHIFT and all - while the key events take the ordinary path.
 */
final class SystemKeyboardView extends View {

    SystemKeyboardView(Context context) {
        super(context);

        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    /**
     * Brings the input method up, and takes the focus it needs to appear.
     *
     * Through the insets controller and not {@code showSoftInput()}, which
     * returned false here however the focus was arranged: this app fits none of
     * its own system windows and drives the bars through that controller, and the
     * keyboard is one more inset to show. The old call stays as a fallback for
     * anything that does not honour the new one.
     */
    void open() {
        setVisibility(VISIBLE);
        requestFocus();

        WindowInsetsController insets = getWindowInsetsController();
        if (insets != null) insets.show(WindowInsets.Type.ime());

        InputMethodManager input = getContext().getSystemService(InputMethodManager.class);
        if (input != null) input.showSoftInput(this, 0);
    }

    /** Puts it away, and hands the focus back for the hardware keys. */
    void close() {
        WindowInsetsController insets = getWindowInsetsController();
        if (insets != null) insets.hide(WindowInsets.Type.ime());

        InputMethodManager input = getContext().getSystemService(InputMethodManager.class);
        if (input != null) input.hideSoftInputFromWindow(getWindowToken(), 0);

        setVisibility(GONE);

        View parent = (View) getParent();
        if (parent != null) parent.requestFocus();
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    /**
     * What the input method is typing into.
     *
     * No suggestions, no autocorrect and no extracted view: a Spectrum is not a
     * text field, there is nothing to correct against, and a keyboard that
     * silently rewrote LOAD "" into something it preferred would be worse than
     * no keyboard. Nothing is stored either - there is no text here, only keys
     * on their way past.
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        out.inputType = InputType.TYPE_CLASS_TEXT
                      | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        out.imeOptions = EditorInfo.IME_ACTION_NONE
                       | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                       | EditorInfo.IME_FLAG_NO_FULLSCREEN
                       | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        out.initialSelStart = 0;
        out.initialSelEnd = 0;

        // The false is "do not manage a text buffer for me": there is no text.
        return new BaseInputConnection(this, false) {

            @Override
            public boolean commitText(CharSequence text, int position) {
                type(text);
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int position) {
                // Some keyboards compose as they go and commit at the end; a
                // machine that wants each key as it is struck cannot wait, so
                // this is treated as the text arriving.
                type(text);
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int before, int after) {
                for (int i = 0; i < before; i++) press(KeyEvent.KEYCODE_DEL);
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                // Enter, Backspace and the arrows do come as key events, and
                // those Fuse maps itself.
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    FuseNative.key(event.getKeyCode(), true);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    FuseNative.key(event.getKeyCode(), false);
                }
                return true;
            }
        };
    }

    /** Every character of a committed string, struck and let go in turn. */
    private void type(CharSequence text) {
        if (text == null) return;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '\n') {
                press(KeyEvent.KEYCODE_ENTER);
                continue;
            }

            FuseNative.character(c, true);
            FuseNative.character(c, false);
        }
    }

    private void press(int keycode) {
        FuseNative.key(keycode, true);
        FuseNative.key(keycode, false);
    }
}
