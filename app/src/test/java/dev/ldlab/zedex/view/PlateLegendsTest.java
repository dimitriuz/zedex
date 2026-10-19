package dev.ldlab.zedex.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * A key drawn with a character on it sends that character.
 *
 * <b>The machine decides what a character costs.</b> A plate is a picture of a
 * keyboard, and the only thing between a tap and a letter is an Android keycode
 * and Fuse's table for it: {@code keyboard.c} turns one {@code INPUT_KEY} into
 * one Spectrum key plus maybe SYMBOL SHIFT, exactly as the hardware's matrix
 * does. So what a legend asks is never "which PC key looks like this" but
 * "which Spectrum keys does the ROM want for this", and the two answers differ
 * for most of the punctuation:
 *
 * <ul>
 *   <li>{@code "} is SYMBOL SHIFT and P — on the hardware this key <i>is</i> the
 *       P column of the symbol row. The 128K's plate gives it a key of its own,
 *       so that one key has to send the pair.</li>
 *   <li>{@code '} is SYMBOL SHIFT and 7, which is what Fuse's apostrophe keycode
 *       means. A plate drawing {@code "} over the apostrophe keycode is one
 *       plausible-looking line away from typing an apostrophe, and every test of
 *       how the keyboard draws would still pass.</li>
 *   <li>{@code ;}, {@code ,} and {@code .} are symbol shift with O, N and M —
 *       and here Android's own keycodes happen to say the same thing, so those
 *       three ride in bare.</li>
 * </ul>
 *
 * <b>Which is why the 48K's plate needs no pair at all:</b> there, {@code "} is
 * a legend printed on the P key and SYMBOL SHIFT is a key the user is already
 * holding. Only a plate that draws {@code "} on a key of its own — the 128K's,
 * with its row of punctuation at the bottom left — has to carry the modifier.
 */
public class PlateLegendsTest {

    /** SYMBOL SHIFT is Android's left CTRL; CAPS SHIFT its left SHIFT. */
    private static final int SYMBOL = KeyEvent.KEYCODE_CTRL_LEFT;

    @Test
    public void the128KsQuoteKeySendsSymbolShiftAndP() {
        for (boolean slim : new boolean[] { false, true }) {
            String skin = slim ? "the slim 128K" : "the 128K";
            Plate.Face quote = only(key(new PlusPlate(slim), "\""), skin);

            assertEquals(skin + "'s key drawn \" sends P",
                         KeyEvent.KEYCODE_P, quote.keycode);
            assertEquals(skin + "'s key drawn \" holds SYMBOL SHIFT with it",
                         SYMBOL, quote.modifier);
        }
    }

    /**
     * The apostrophe keycode means SYMBOL SHIFT and 7 to the machine, so it is
     * right for {@code '} and wrong for anything else — and no plate draws
     * {@code '} on a key of its own, since both machines print it above the 7.
     */
    @Test
    public void noPlateKeySendsTheApostropheKeycode() {
        for (Plate plate : plates()) {
            String machine = plate instanceof RubberPlate ? "48K" : "128K";

            for (Plate.Face face : plate.faces) {
                assertFalse("the " + machine + "'s " + face.name
                                    + " sends the apostrophe keycode, which is"
                                    + " SYMBOL SHIFT and 7 — an apostrophe",
                            face.keycode == KeyEvent.KEYCODE_APOSTROPHE);
            }
        }
    }

    /**
     * The 48K prints {@code "} on the P key, under SYMBOL SHIFT, and so sends P
     * bare: the shift is the user's to hold, and a modifier baked into the key
     * would type a quote for somebody who wanted a P.
     */
    @Test
    public void the48KsQuoteIsTheSymbolShiftLegendOnP() {
        Plate.Face p = only(key(new RubberPlate(false), "\""), "the 48K");

        assertEquals("\"", p.symbol);
        assertEquals(KeyEvent.KEYCODE_P, p.keycode);
        assertEquals("the 48K has a SYMBOL SHIFT key for this", 0, p.modifier);
    }

    /**
     * The rest of the 128K's bottom-left punctuation: characters Android's own
     * keycodes already stand for, each of which Fuse reads as the same
     * symbol-shift pair the hardware uses — {@code ;} with O, {@code ,} with N,
     * {@code .} with M.
     */
    @Test
    public void the128KsOtherPunctuationSendsItsOwnKeycode() {
        String[] drawn = { ";", ",", "." };
        int[] expected = { KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_COMMA,
                           KeyEvent.KEYCODE_PERIOD };

        for (Plate plate : new Plate[] { new PlusPlate(false),
                                         new PlusPlate(true) }) {
            for (int i = 0; i < drawn.length; i++) {
                Plate.Face face = only(key(plate, drawn[i]), "the 128K");

                assertEquals("the key drawn " + drawn[i], expected[i],
                             face.keycode);
                assertEquals("the key drawn " + drawn[i] + " needs no shift",
                             0, face.modifier);
            }
        }
    }

    // --- helpers ------------------------------------------------------------------

    private static List<Plate> plates() {
        List<Plate> all = new ArrayList<>();

        all.add(new RubberPlate(false));
        all.add(new RubberPlate(true));
        all.add(new PlusPlate(false));
        all.add(new PlusPlate(true));

        return all;
    }

    /** Every key of this plate showing this character on its face. */
    private static List<Plate.Face> key(Plate plate, String drawn) {
        List<Plate.Face> found = new ArrayList<>();

        for (Plate.Face face : plate.faces) {
            if (drawn.equals(face.label) || drawn.equals(face.symbol)
                                              || drawn.equals(face.letter)) {
                found.add(face);
            }
        }

        return found;
    }

    /** One match; the plate's own skin in the failure when it is not one. */
    private static Plate.Face only(List<Plate.Face> found, String skin) {
        assertEquals(skin + " should have exactly one key with that legend: "
                             + names(found), 1, found.size());
        return found.get(0);
    }

    private static List<String> names(List<Plate.Face> found) {
        List<String> all = new ArrayList<>();

        for (Plate.Face face : found) all.add(face.name);
        return all;
    }
}
