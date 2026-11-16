package dev.earclean.preview;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class VideoCapture {
    interface Completion {
        void finished(String error);
    }

    private final File destination;
    private final Completion completion;
    private final boolean fullFrame;
    private final int width;
    private final int height;
    private final ArrayBlockingQueue<TimedImage> pending = new ArrayBlockingQueue<>(1);
    private volatile boolean stopped;
    private long lastFrameTimeUs = -1;
    private final long startUs = SystemClock.elapsedRealtimeNanos() / 1000;

    VideoCapture(File destination, CaptureImage first, boolean fullFrame, Completion completion) {
        this.destination = destination;
        this.completion = completion;
        this.fullFrame = fullFrame;
        width = fullFrame ? (first.frame.getWidth() & ~1) : CaptureImage.SIZE;
        height = fullFrame ? (first.frame.getHeight() & ~1) : CaptureImage.SIZE;
    }

    void start() {
        new Thread(this::encode, "earclean-recorder").start();
    }

    void offer(CaptureImage image) {
        long timeUs = SystemClock.elapsedRealtimeNanos() / 1000 - startUs;
        if (stopped || (lastFrameTimeUs >= 0 && timeUs - lastFrameTimeUs < 50_000)) return;
        lastFrameTimeUs = timeUs;
        pending.poll();
        pending.offer(new TimedImage(image, timeUs));
    }

    void stop() {
        stopped = true;
    }

    private void encode() {
        File partial = new File(destination.getPath() + ".part");
        try {
            encodeFile(partial);
            if (!partial.renameTo(destination)) throw new IOException("无法保存视频");
            completion.finished(null);
        } catch (IOException | RuntimeException error) {
            completion.finished(error.getMessage() == null ? "录制失败" : error.getMessage());
        } finally {
            partial.delete();
        }
    }

    private void encodeFile(File partial) throws IOException {
        String mime = MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1_400_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 20);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec codec = MediaCodec.createEncoderByType(mime);
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            try {
                MediaMuxer muxer = new MediaMuxer(partial.getPath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                try {
                    writeFrames(codec, muxer);
                } finally {
                    muxer.release();
                }
            } finally {
                codec.stop();
            }
        } finally {
            codec.release();
        }
    }

    private void writeFrames(MediaCodec codec, MediaMuxer muxer) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            writeFrames(codec, muxer, bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    private void writeFrames(MediaCodec codec, MediaMuxer muxer, Bitmap bitmap) throws IOException {
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        RectF bounds = new RectF();
        Path mask = new Path();
        int[] pixels = new int[width * height];
        MediaCodec.BufferInfo output = new MediaCodec.BufferInfo();
        int track = -1;
        int samples = 0;
        long lastPts = -1;
        long stopDeadline = 0;
        boolean inputEnded = false;
        while (true) {
            if (stopped && stopDeadline == 0) stopDeadline = SystemClock.elapsedRealtime() + 3500;
            if (stopDeadline != 0 && SystemClock.elapsedRealtime() >= stopDeadline) {
                throw new IOException("视频编码结束超时");
            }
            int out = codec.dequeueOutputBuffer(output, 0);
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
            } else if (out >= 0) {
                try {
                    if (output.size > 0 && (output.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer encoded = codec.getOutputBuffer(out);
                        if (track < 0 || encoded == null) throw new IOException("视频轨道未就绪");
                        encoded.position(output.offset);
                        encoded.limit(output.offset + output.size);
                        muxer.writeSampleData(track, encoded, output);
                        samples++;
                    }
                } finally {
                    codec.releaseOutputBuffer(out, false);
                }
                if ((output.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
            if (inputEnded) {
                SystemClock.sleep(5);
                continue;
            }
            TimedImage frame;
            try {
                frame = pending.poll(12, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("录制已中断", error);
            }
            if (frame == null && !stopped) continue;
            int index = codec.dequeueInputBuffer(12_000);
            if (index < 0) {
                if (frame != null) pending.offer(frame);
                continue;
            }
            if (frame == null) {
                if (lastPts < 0) throw new IOException("没有可保存的视频帧");
                codec.queueInputBuffer(index, 0, 0, lastPts + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                inputEnded = true;
                continue;
            }
            if (frame.timeUs <= lastPts) {
                codec.queueInputBuffer(index, 0, 0, lastPts, 0);
                continue;
            }
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null) throw new IOException("设备编码器没有可写缓冲区");
            int capacity = buffer.capacity();
            Image input = codec.getInputImage(index);
            if (input == null) throw new IOException("设备编码器不支持图像输入");
            canvas.drawColor(0xff0a1115);
            if (fullFrame) {
                frame.image.renderFull(canvas, width / 2f, height / 2f, width, height, paint, bounds);
            } else {
                frame.image.render(canvas, width / 2f, height / 2f, width, paint, mask, bounds);
            }
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            fillImage(input, pixels);
            codec.queueInputBuffer(index, 0, capacity, frame.timeUs, 0);
            lastPts = frame.timeUs;
        }
        if (samples == 0) throw new IOException("没有可保存的视频帧");
        muxer.stop();
    }

    private void fillImage(Image input, int[] pixels) {
        Image.Plane[] planes = input.getPlanes();
        ByteBuffer y = planes[0].getBuffer();
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        for (int row = 0; row < height; row++) {
            int yRow = y.position() + row * planes[0].getRowStride();
            int uRow = u.position() + (row / 2) * planes[1].getRowStride();
            int vRow = v.position() + (row / 2) * planes[2].getRowStride();
            for (int col = 0; col < width; col++) {
                int color = pixels[row * width + col];
                int r = (color >>> 16) & 255;
                int g = (color >>> 8) & 255;
                int b = color & 255;
                y.put(yRow + col * planes[0].getPixelStride(),
                        (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));
                if (((row | col) & 1) == 0) {
                    u.put(uRow + (col / 2) * planes[1].getPixelStride(),
                            (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128));
                    v.put(vRow + (col / 2) * planes[2].getPixelStride(),
                            (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128));
                }
            }
        }
    }

    private static final class TimedImage {
        final CaptureImage image;
        final long timeUs;

        TimedImage(CaptureImage image, long timeUs) {
            this.image = image;
            this.timeUs = timeUs;
        }
    }
}
