package dev.ldlab.zedex.media;

import java.io.File;
import java.io.IOException;

/**
 * Somewhere frames are being written, for as long as the recording lasts.
 *
 * Implementations are only ever touched from {@link Recorder}'s own thread,
 * so none of them needs to be thread safe.
 */
public interface Recording {

    /**
     * One frame, as palette indices.
     *
     * @param pixels    indices into the palette the recording was made with
     * @param width     what the machine is drawing, which can change
     * @param height    likewise
     * @param stride    how far apart the rows are, which is not the width
     * @param timestampNanos when the frame was drawn, on a monotonic clock
     */
    void frame(byte[] pixels, int width, int height, int stride, long timestampNanos)
            throws IOException;

    /** Frames closer together than this are dropped, or 0 to take them all. */
    public long minimumIntervalNanos();

    /** Finishes the file. Throws if there is nothing worth keeping. */
    public void close() throws IOException;

    public File file();
}
