package dev.ldlab.zedex;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Screenshots and recordings, taken from the frames Fuse draws.
 *
 * Everything starts on the emulation thread, in a callback Fuse makes at the
 * end of a frame, and that thread must not be kept waiting: it is the thread
 * the machine runs on, and anything slow there is heard as a stutter. So the
 * callback does nothing but copy the pixels into a spare buffer and hand them
 * over; a thread of its own does the encoding.
 *
 * When there is no buffer spare the frame is dropped rather than waited for.
 * A recording that skips a frame is better than a machine that hesitates.
 */
final class Recorder {

    private static final String TAG = "Zedex";

    /** Deep enough to ride out a slow write, shallow enough to stay current. */
    private static final int SPARE_FRAMES = 8;

    /** What a recording is written as. */
    enum Format {
        GIF("gif", GifRecording.INTERVAL),
        MP4("mp4", 0);

        final String extension;

        /** Frames closer together than this are not worth keeping. */
        final long minimumIntervalNanos;

        Format(String extension, long minimumIntervalNanos) {
            this.extension = extension;
            this.minimumIntervalNanos = minimumIntervalNanos;
        }
    }

    /** Told when a file is finished, on the main thread. */
    interface Listener {
        void finished(File file, String error);
    }

    private Recorder() {
    }

    private static final Handler main = new Handler(Looper.getMainLooper());

    // --- what the emulation thread reads ---------------------------------

    private static ByteBuffer pixels;
    private static int stride;

    private static volatile boolean recording;
    private static long lastFrameAt;
    private static long minimumInterval;

    private static volatile File screenshotTarget;
    private static volatile Listener screenshotListener;

    // --- the encoding thread ---------------------------------------------

    private static final BlockingQueue<Frame> spare =
            new ArrayBlockingQueue<>(SPARE_FRAMES);
    private static final BlockingQueue<Frame> queued =
            new ArrayBlockingQueue<>(SPARE_FRAMES);

    private static Thread worker;
    private static File target;
    private static Format format;
    private static Listener listener;
    private static int dropped;

    private static final class Frame {
        byte[] pixels;
        int[] palette;
        int width;
        int height;
        int stride;
        long at;
        boolean last;
    }

    // --- starting and stopping -------------------------------------------

    static boolean isRecording() {
        return recording;
    }

    /**
     * Begins a recording. The encoder is not built until the first frame
     * arrives, because that is when the machine's size and palette are known.
     */
    static synchronized boolean start(File file, Format chosen, Listener whenDone) {
        // The previous recording's file is not finished until its thread is.
        if (recording || (worker != null && worker.isAlive())) return false;

        target = file;
        format = chosen;
        listener = whenDone;
        dropped = 0;
        lastFrameAt = 0;
        minimumInterval = chosen.minimumIntervalNanos;

        queued.clear();
        spare.clear();
        for (int i = 0; i < SPARE_FRAMES; i++) spare.offer(new Frame());

        worker = new Thread(Recorder::encode, "fuse-recorder");
        worker.start();

        recording = true;
        FuseNative.setRecording(true);

        return true;
    }

    /** Ends it, and reports the finished file once it is really written. */
    static synchronized void stop() {
        if (!recording) return;

        recording = false;
        FuseNative.setRecording(false);

        Frame end = new Frame();
        end.last = true;

        // The queue is bounded and the encoder may be behind, so make room
        // rather than block the caller, which is the UI thread.
        for (int i = 0; i <= SPARE_FRAMES && !queued.offer(end); i++) queued.poll();
    }

    // --- called on the emulation thread ----------------------------------

    /** One frame, straight from {@code uidisplay_frame_end}. */
    static void frame(int width, int height) {
        if (!recording) return;

        long now = System.nanoTime();
        if (lastFrameAt != 0 && now - lastFrameAt < minimumInterval) return;

        Frame frame = spare.poll();
        if (frame == null) {
            dropped++;
            return;
        }

        read(frame, width, height, now);

        if (!queued.offer(frame)) {
            dropped++;
            spare.offer(frame);
            return;
        }

        lastFrameAt = now;
    }

    /** The frame a screenshot was asked for. */
    static void screenshot(int width, int height) {
        File file = screenshotTarget;
        Listener whenDone = screenshotListener;

        screenshotTarget = null;
        screenshotListener = null;

        if (file == null) return;

        Frame frame = new Frame();
        read(frame, width, height, System.nanoTime());

        new Thread(() -> writePng(frame, file, whenDone), "fuse-screenshot").start();
    }

    /** Copies the frame out while the emulation thread is held in the callback. */
    private static void read(Frame frame, int width, int height, long now) {
        if (pixels == null) {
            pixels = FuseNative.frameBuffer();
            stride = FuseNative.frameStride();
        }

        int length = height * stride;
        if (frame.pixels == null || frame.pixels.length < length) {
            frame.pixels = new byte[length];
        }

        pixels.position(0);
        pixels.get(frame.pixels, 0, length);

        frame.palette = FuseNative.palette();
        frame.width = width;
        frame.height = height;
        frame.stride = stride;
        frame.at = now;
        frame.last = false;
    }

    /** Asks for the next frame to be written as a PNG. */
    static void screenshotTo(File file, Listener whenDone) {
        screenshotTarget = file;
        screenshotListener = whenDone;
        FuseNative.captureScreenshot();
    }

    // --- encoding ---------------------------------------------------------

    private static void encode() {
        File file = target;
        Listener whenDone = listener;
        Recording out = null;
        String failure = null;

        try {
            while (true) {
                Frame frame = queued.take();
                if (frame.last) break;

                try {
                    if (out == null) out = open(file, frame.palette);
                    out.frame(frame.pixels, frame.width, frame.height,
                              frame.stride, frame.at);
                } finally {
                    spare.offer(frame);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w(TAG, "recording " + file, e);
            failure = e.getMessage();
        }

        try {
            if (out != null) out.close();
            else if (failure == null) failure = "nothing was recorded";
        } catch (Exception e) {
            Log.w(TAG, "finishing " + file, e);
            if (failure == null) failure = e.getMessage();
        }

        if (failure != null) file.delete();
        else if (dropped > 0) Log.i(TAG, dropped + " frames dropped while recording");

        report(whenDone, file, failure);
    }

    private static Recording open(File file, int[] palette) throws IOException {
        return format == Format.GIF ? new GifRecording(file, palette)
                                    : new Mp4Recording(file, palette);
    }

    private static void writePng(Frame frame, File file, Listener whenDone) {
        String failure = null;

        try {
            int[] colours = new int[frame.width * frame.height];

            for (int y = 0; y < frame.height; y++) {
                int source = y * frame.stride;
                int target = y * frame.width;

                for (int x = 0; x < frame.width; x++) {
                    colours[target + x] = argb(
                            frame.palette[frame.pixels[source + x] & 0x0f]);
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(colours, frame.width, frame.height,
                                                Bitmap.Config.ARGB_8888);

            try (OutputStream out =
                         new BufferedOutputStream(new FileOutputStream(file))) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IOException("the image could not be encoded");
                }
            } finally {
                bitmap.recycle();
            }
        } catch (Exception e) {
            Log.w(TAG, "screenshot " + file, e);
            failure = e.getMessage();
            file.delete();
        }

        report(whenDone, file, failure);
    }

    /** The renderer's colours are 0xAABBGGRR; a Bitmap wants 0xAARRGGBB. */
    private static int argb(int rgba) {
        return (rgba & 0xff00ff00)
             | ((rgba & 0xff) << 16)
             | ((rgba >> 16) & 0xff);
    }

    private static void report(Listener whenDone, File file, String error) {
        if (whenDone == null) return;
        main.post(() -> whenDone.finished(file, error));
    }
}
