package dev.ldlab.zedex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The handshake behind Fuse's "the disk has been modified" question.
 *
 * <b>Why this is worth a test of its own.</b> Fuse asks it on the emulation
 * thread, in the middle of ejecting a disk, and uses the answer to decide
 * whether the file is written - so {@code onConfirmSave} blocks that thread
 * until somebody replies. A reply that never comes is not a missing dialog, it
 * is a machine that never runs again, and no amount of looking at the screen
 * shows the difference between the two.
 *
 * Nothing here goes near Fuse. The question arrives as a plain call and the
 * answer leaves as a plain int; what is under test is that every way of not
 * answering still answers.
 */
@RunWith(AndroidJUnit4.class)
public class ConfirmSaveTest {

    /** Long enough that a wrong answer is the code's and not the bench's, and
     *  short enough that a hang fails the test rather than the suite. */
    private static final long ANSWER_MS = 5000;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();

        // onConfirmSave needs the handler attach() makes; without it every
        // answer is cancel and every assertion below would pass for the wrong
        // reason.
        FuseNative.attach(context);
        FuseNative.setConfirmer(null);
    }

    @After
    public void putItBack() {
        FuseNative.setConfirmer(null);
    }

    /**
     * Asks from a background thread, because that is where Fuse asks from.
     *
     * Calling it on the test thread would be the one arrangement that cannot
     * work: onConfirmSave waits for the UI thread to put a dialog up, and the
     * instrumentation's own thread is not it - but a test that blocked the UI
     * thread would deadlock rather than fail, and a deadlocked test takes the
     * whole run with it.
     */
    private static int askOffTheMainThread(String message) throws Exception {
        AtomicInteger answer = new AtomicInteger(Integer.MIN_VALUE);
        CountDownLatch done = new CountDownLatch(1);

        Thread asking = new Thread(() -> {
            answer.set(FuseNative.onConfirmSave(message));
            done.countDown();
        }, "confirm-save-test");

        asking.start();

        assertTrue("onConfirmSave never came back - the emulation thread would "
                   + "be blocked here for the life of the process",
                   done.await(ANSWER_MS, TimeUnit.MILLISECONDS));

        return answer.get();
    }

    /** Nothing on screen to ask on: cancel, and promptly. */
    @Test
    public void withNobodyToAskTheAnswerIsCancel() throws Exception {
        assertEquals(FuseNative.CONFIRM_CANCEL, askOffTheMainThread("Disk modified"));
    }

    @Test
    public void whatTheConfirmerSaysIsWhatFuseIsTold() throws Exception {
        FuseNative.setConfirmer((message, answer) -> answer.is(FuseNative.SAVE));
        assertEquals(FuseNative.SAVE, askOffTheMainThread("Disk modified"));

        FuseNative.setConfirmer((message, answer) -> answer.is(FuseNative.DONT_SAVE));
        assertEquals(FuseNative.DONT_SAVE, askOffTheMainThread("Disk modified"));

        FuseNative.setConfirmer((message, answer) -> answer.is(FuseNative.CONFIRM_CANCEL));
        assertEquals(FuseNative.CONFIRM_CANCEL, askOffTheMainThread("Disk modified"));
    }

    /** Fuse's own words reach the screen unchanged - they name the drive, and
     *  a dialog that dropped them would ask about nothing in particular. */
    @Test
    public void themessageIsHandedOverAsItWasGiven() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        FuseNative.setConfirmer((message, answer) -> {
            seen.set(message);
            answer.is(FuseNative.DONT_SAVE);
        });

        askOffTheMainThread("Beta disk A: has been modified.\nDo you want to save it?");

        assertEquals("Beta disk A: has been modified.\nDo you want to save it?", seen.get());
    }

    /**
     * The screen goes while the question is up.
     *
     * The real case: a dialog is showing and the activity is destroyed - a
     * rotation, or somebody leaving - so the buttons nobody pressed are gone
     * for good. The emulation thread has to be let go, and cancel is the reply
     * that leaves the disk in the drive with its changes.
     */
    @Test
    public void aconfirmerThatGoesAwayUnblocksTheEmulationThread() throws Exception {
        CountDownLatch asked = new CountDownLatch(1);

        // Never answers - it only says it was reached.
        FuseNative.setConfirmer((message, answer) -> asked.countDown());

        AtomicInteger answer = new AtomicInteger(Integer.MIN_VALUE);
        CountDownLatch done = new CountDownLatch(1);

        new Thread(() -> {
            answer.set(FuseNative.onConfirmSave("Disk modified"));
            done.countDown();
        }, "confirm-save-abandoned").start();

        assertTrue("the confirmer was never asked", asked.await(ANSWER_MS, TimeUnit.MILLISECONDS));
        assertEquals("it answered when it was not supposed to", 1, done.getCount());

        FuseNative.setConfirmer(null);

        assertTrue("the emulation thread was left waiting for an answer that "
                   + "was never coming - the machine would never run again",
                   done.await(ANSWER_MS, TimeUnit.MILLISECONDS));
        assertEquals(FuseNative.CONFIRM_CANCEL, answer.get());
    }

    /** A confirmer that throws is still an answer, or the machine stops. */
    @Test
    public void aconfirmerThatThrowsDoesNotStopTheMachine() throws Exception {
        FuseNative.setConfirmer((message, answer) -> {
            throw new IllegalStateException("no window token");
        });

        assertEquals(FuseNative.CONFIRM_CANCEL, askOffTheMainThread("Disk modified"));
    }

    /** Answering twice is what a dialog does when a button dismisses it and
     *  the dismiss listener fires as well. The first reply is the one kept. */
    @Test
    public void asecondAnswerIsIgnoredRatherThanOverridingTheFirst() throws Exception {
        FuseNative.setConfirmer((message, answer) -> {
            answer.is(FuseNative.SAVE);
            answer.is(FuseNative.CONFIRM_CANCEL);
        });

        assertEquals(FuseNative.SAVE, askOffTheMainThread("Disk modified"));
    }
}
