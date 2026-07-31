package dev.ldlab.zedex.media;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.Image;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Writes frames as H.264 in an MP4, through the device's own encoder.
 *
 * The encoder wants YUV and the machine produces palette indices, but there
 * are only sixteen colours in play: converting the palette once a recording
 * turns every pixel into three table lookups, which is what makes this cheap
 * enough to do on every frame of a 50Hz machine.
 *
 * The size is fixed when the first frame arrives, because that is when the
 * machine's resolution is known.
 */
public final class Mp4Recording implements Recording {

    private static final String CODEC = MediaFormat.MIMETYPE_VIDEO_AVC;

    /** Sixteen flat colours at 320x240 need very little. */
    private static final int BIT_RATE = 3_000_000;
    private static final int FRAME_RATE = 50;
    private static final int KEYFRAME_SECONDS = 1;

    private static final long DEQUEUE_TIMEOUT = 10_000;       // microseconds

    private final File file;
    private final byte[] luma = new byte[16];
    private final byte[] blue = new byte[16];
    private final byte[] red = new byte[16];

    private MediaCodec codec;
    private MediaMuxer muxer;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    private int track = -1;
    private boolean muxing;
    private int width;
    private int height;
    private long firstFrameAt = -1;
    private long frames;

    Mp4Recording(File file, int[] palette) {
        this.file = file;

        for (int i = 0; i < 16; i++) {
            int colour = palette[i];                          // 0xAABBGGRR
            int r = colour & 0xff;
            int g = (colour >> 8) & 0xff;
            int b = (colour >> 16) & 0xff;

            // BT.601, which is what an encoder assumes at this size.
            luma[i] = clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
            blue[i] = clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
            red[i] = clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
        }
    }

    private static byte clamp(int value) {
        return (byte) Math.max(0, Math.min(255, value));
    }

    @Override
    public File file() {
        return file;
    }

    @Override
    public long minimumIntervalNanos() {
        return 0;                                             // every frame
    }

    @Override
    public void frame(byte[] pixels, int frameWidth, int frameHeight, int stride,
                      long timestampNanos) throws IOException {

        if (codec == null) {
            width = frameWidth;
            height = frameHeight;
            start();
        }

        // H.264 is fixed size for the life of a stream, so a resolution
        // change part way through - a Timex hi-res mode - is skipped.
        if (frameWidth != width || frameHeight != height) return;

        if (firstFrameAt < 0) firstFrameAt = timestampNanos;

        int index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT);
        if (index >= 0) {
            fill(codec.getInputImage(index), pixels, stride);
            codec.queueInputBuffer(index, 0, width * height * 3 / 2,
                                   (timestampNanos - firstFrameAt) / 1000, 0);
            frames++;
        }

        drain(false);
    }

    private void start() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(CODEC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_SECONDS);

        codec = MediaCodec.createEncoderByType(CODEC);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        muxer = new MediaMuxer(file.getAbsolutePath(),
                               MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    /**
     * Writes one frame into the encoder's own buffers.
     *
     * The planes are asked where they want their bytes rather than assumed:
     * chroma may be planar or interleaved depending on the device, and the
     * rows are often wider than the picture.
     */
    private void fill(Image image, byte[] pixels, int stride) {
        Image.Plane[] planes = image.getPlanes();

        ByteBuffer y = planes[0].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPixel = planes[0].getPixelStride();

        for (int row = 0; row < height; row++) {
            int source = row * stride;
            int target = row * yRow;

            for (int column = 0; column < width; column++) {
                y.put(target + column * yPixel, luma[pixels[source + column] & 0x0f]);
            }
        }

        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        int uvRow = planes[1].getRowStride();
        int uvPixel = planes[1].getPixelStride();

        // Chroma is a quarter the size. The picture is sixteen flat colours
        // with hard edges, so the top left of each block is taken as it is;
        // averaging would only bleed one colour into its neighbour.
        for (int row = 0; row < height / 2; row++) {
            int source = row * 2 * stride;
            int target = row * uvRow;

            for (int column = 0; column < width / 2; column++) {
                int index = pixels[source + column * 2] & 0x0f;
                int at = target + column * uvPixel;

                u.put(at, blue[index]);
                v.put(at, red[index]);
            }
        }
    }

    private void drain(boolean finishing) throws IOException {
        while (true) {
            int index = codec.dequeueOutputBuffer(info, finishing ? DEQUEUE_TIMEOUT : 0);

            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!finishing) return;
                continue;
            }

            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxing) throw new IOException("the encoder changed format twice");

                track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxing = true;
                continue;
            }

            if (index < 0) continue;                          // deprecated codes

            ByteBuffer encoded = codec.getOutputBuffer(index);

            // The codec-specific data is handed over as a buffer of its own;
            // the muxer takes it from the format instead, so it is dropped.
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;

            if (info.size > 0 && muxing && encoded != null) {
                encoded.position(info.offset);
                encoded.limit(info.offset + info.size);
                muxer.writeSampleData(track, encoded, info);
            }

            codec.releaseOutputBuffer(index, false);

            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (codec != null && frames > 0) {
                int index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT);
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, 0,
                                           MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                drain(true);
            }
        } finally {
            if (codec != null) {
                codec.stop();
                codec.release();
            }
            if (muxer != null) {
                if (muxing) muxer.stop();
                muxer.release();
            }
        }

        if (frames == 0) throw new IOException("nothing was recorded");
    }
}
