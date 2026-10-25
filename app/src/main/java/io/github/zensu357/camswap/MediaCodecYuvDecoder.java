package io.github.zensu357.camswap;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.Image;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

/**
 * Lightweight MediaCodec-based video decoder that outputs YUV_420_888 frames
 * directly, avoiding the expensive GL→Bitmap→RGB→YUV conversion pipeline.
 */
final class MediaCodecYuvDecoder {

    private static final long TIMEOUT_US = 10_000L;

    // Статическое сохранение позиции декодера при перезапуске камеры в баттлах
    private static volatile long savedDecodePositionUs = 0L;
    private static volatile long savedDecodeRealtimeMs = 0L;
    private static volatile String lastDecodedVideoPath = "";

    private volatile boolean running;
    private Thread decodeThread;

    private volatile YuvFrame latestFrame;

    // Video metadata
    private volatile int videoWidth;
    private volatile int videoHeight;
    private volatile int videoRotation;
    private volatile int videoFrameRate;

    /** Immutable snapshot of one decoded YUV frame. */
    static final class YuvFrame {
        final int width;
        final int height;
        final byte[] yPlane;
        final byte[] uPlane;
        final byte[] vPlane;
        final long timestampNs;

        YuvFrame(int width, int height, byte[] y, byte[] u, byte[] v, long tsNs) {
            this.width = width;
            this.height = height;
            this.yPlane = y;
            this.uPlane = u;
            this.vPlane = v;
            this.timestampNs = tsNs;
        }
    }

    void start() {
        if (running) return;
        running = true;
        latestFrame = null;
        decodeThread = new Thread(this::decodeLoop, "CS-YuvDecode");
        decodeThread.start();
    }

    void stop() {
        running = false;
        Thread t = decodeThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        decodeThread = null;
        latestFrame = null;
    }

    boolean isRunning() {
        return running;
    }

    YuvFrame acquireLatestFrame() {
        return latestFrame;
    }

    public byte[] acquireLatestNv21Frame() {
        YuvFrame frame = latestFrame;
        if (frame == null || frame.yPlane == null || frame.uPlane == null || frame.vPlane == null) {
            return null;
        }
        int yLen = frame.yPlane.length;
        int uLen = frame.uPlane.length;
        int vLen = frame.vPlane.length;
        byte[] nv21 = new byte[yLen + uLen + vLen];
        System.arraycopy(frame.yPlane, 0, nv21, 0, yLen);
        int uvIndex = yLen;
        for (int i = 0; i < vLen; i++) {
            nv21[uvIndex++] = frame.vPlane[i];
            if (i < uLen) {
                nv21[uvIndex++] = frame.uPlane[i];
            }
        }
        return nv21;
    }

    int getVideoWidth() { return videoWidth; }
    int getVideoHeight() { return videoHeight; }
    int getVideoRotation() { return videoRotation; }

    private long resolveResumePositionUs(String videoPath, long durationUs) {
        if (videoPath != null && !videoPath.equals(lastDecodedVideoPath)) {
            lastDecodedVideoPath = videoPath;
            savedDecodePositionUs = 0L;
            savedDecodeRealtimeMs = 0L;
        }

        // 1. Приоритет: синхронизация с основным MediaPlayerManager (и звуком Video Sync)
        try {
            if (HookMain.playerManager != null) {
                long playerPosMs = HookMain.playerManager.getCamera2PlaybackPositionMs();
                if (playerPosMs > 0) {
                    long posUs = playerPosMs * 1000L;
                    if (durationUs > 0) {
                        posUs = posUs % durationUs;
                    }
                    savedDecodePositionUs = posUs;
                    savedDecodeRealtimeMs = SystemClock.elapsedRealtime();
                    return posUs;
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. Резерв: собственная сохраненная позиция декодера + время переключения
        if (savedDecodePositionUs > 0L) {
            long targetUs = savedDecodePositionUs;
            if (savedDecodeRealtimeMs > 0L) {
                long deltaMs = SystemClock.elapsedRealtime() - savedDecodeRealtimeMs;
                if (deltaMs > 0 && deltaMs < 30_000L) {
                    targetUs += deltaMs * 1000L;
                }
            }
            if (durationUs > 0L) {
                targetUs = targetUs % durationUs;
            }
            return targetUs;
        }
        return 0L;
    }

    private void decodeLoop() {
        while (running) {
            try {
                decodeOnePass();
            } catch (Exception e) {
                if (running) {
                    LogUtil.log("【CS】YuvDecoder 异常: " + e);
                    try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                }
            }
        }
    }

    private void decodeOnePass() throws IOException {
        String videoPath = VideoManager.getCurrentVideoPath();
        ParcelFileDescriptor pfd = VideoManager.getVideoPFD();
        FileDescriptor fd = pfd != null ? pfd.getFileDescriptor() : null;

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            if (fd != null) {
                extractor.setDataSource(fd);
            } else if (videoPath != null) {
                extractor.setDataSource(videoPath);
            } else {
                LogUtil.log("【CS】YuvDecoder: 无可用视频源");
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                return;
            }

            int trackIndex = selectVideoTrack(extractor);
            if (trackIndex < 0) {
                LogUtil.log("【CS】YuvDecoder: 未发现视频轨道");
                return;
            }
            extractor.selectTrack(trackIndex);
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                videoFrameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
            }
            if (videoFrameRate <= 0) videoFrameRate = 30;

            long durationUs = 0L;
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                durationUs = format.getLong(MediaFormat.KEY_DURATION);
            }

            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                videoRotation = format.getInteger(MediaFormat.KEY_ROTATION);
            } else {
                videoRotation = 0;
            }

            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            long resumePosUs = resolveResumePositionUs(videoPath, durationUs);
            if (resumePosUs > 0L) {
                extractor.seekTo(resumePosUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                LogUtil.log("【CS】【YUV解码器】Продолжение декодирования с позиции: " + (resumePosUs / 1000L) + " ms");
            }

            decodeFrames(decoder, extractor, resumePosUs);

        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                try { decoder.release(); } catch (Exception ignored) {}
            }
            extractor.release();
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void decodeFrames(MediaCodec decoder, MediaExtractor extractor, long initialSeekUs) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean sawInputEOS = false;
        long basePtsUs = -1L;
        long startTimeMs = System.currentTimeMillis();

        byte[] yBuf = null, uBuf = null, vBuf = null;

        while (running) {
            if (!sawInputEOS) {
                int inIdx = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inIdx >= 0) {
                    ByteBuffer inputBuf = decoder.getInputBuffer(inIdx);
                    int sampleSize = extractor.readSampleData(inputBuf, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEOS = true;
                    } else {
                        long pts = extractor.getSampleTime();
                        decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0);
                        extractor.advance();
                    }
                }
            }

            int outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIdx >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    decoder.releaseOutputBuffer(outIdx, false);
                    // Конец видео — перематываем в начало для бесконечного цикла
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    decoder.flush();
                    sawInputEOS = false;
                    savedDecodePositionUs = 0L;
                    savedDecodeRealtimeMs = SystemClock.elapsedRealtime();
                    basePtsUs = -1L;
                    startTimeMs = System.currentTimeMillis();
                    continue;
                }

                if (info.size > 0) {
                    if (basePtsUs < 0L) {
                        basePtsUs = info.presentationTimeUs;
                        startTimeMs = System.currentTimeMillis();
                    }

                    savedDecodePositionUs = info.presentationTimeUs;
                    savedDecodeRealtimeMs = SystemClock.elapsedRealtime();

                    Image image = decoder.getOutputImage(outIdx);
                    if (image != null) {
                        try {
                            int w = image.getWidth();
                            int h = image.getHeight();
                            int yLen = w * h;
                            int cLen = (w / 2) * (h / 2);

                            if (yBuf == null || yBuf.length != yLen) {
                                yBuf = new byte[yLen];
                                uBuf = new byte[cLen];
                                vBuf = new byte[cLen];
                            }

                            extractYuvPlanes(image, w, h, yBuf, uBuf, vBuf);

                            byte[] yOut = new byte[yLen];
                            byte[] uOut = new byte[cLen];
                            byte[] vOut = new byte[cLen];
                            System.arraycopy(yBuf, 0, yOut, 0, yLen);
                            System.arraycopy(uBuf, 0, uOut, 0, cLen);
                            System.arraycopy(vBuf, 0, vOut, 0, cLen);
                            latestFrame = new YuvFrame(w, h, yOut, uOut, vOut, System.nanoTime());
                        } finally {
                            image.close();
                        }
                    }

                    // Выдерживаем тайминг кадров относительно точки старта/перемотки
                    long targetElapsedMs = Math.max(0L, (info.presentationTimeUs - basePtsUs) / 1000L);
                    long actualElapsedMs = System.currentTimeMillis() - startTimeMs;
                    long sleepMs = targetElapsedMs - actualElapsedMs;
                    if (sleepMs > 2 && sleepMs < 5000) {
                        try { Thread.sleep(sleepMs); } catch (InterruptedException e) { break; }
                    }
                }
                decoder.releaseOutputBuffer(outIdx, false);
            }
        }
    }

    private static void extractYuvPlanes(Image image, int w, int h,
                                          byte[] yOut, byte[] uOut, byte[] vOut) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();

        if (yPixelStride == 1 && yRowStride == w) {
            yBuf.get(yOut, 0, w * h);
        } else {
            for (int row = 0; row < h; row++) {
                yBuf.position(row * yRowStride);
                if (yPixelStride == 1) {
                    yBuf.get(yOut, row * w, w);
                } else {
                    int base = row * w;
                    for (int col = 0; col < w; col++) {
                        yOut[base + col] = yBuf.get(row * yRowStride + col * yPixelStride);
                    }
                }
            }
        }

        int cw = w / 2;
        int ch = h / 2;
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        if (uPixelStride == 1 && uRowStride == cw
                && vPixelStride == 1 && vRowStride == cw) {
            uBuf.get(uOut, 0, cw * ch);
            vBuf.get(vOut, 0, cw * ch);
        } else if (uPixelStride == 2 && vPixelStride == 2) {
            for (int row = 0; row < ch; row++) {
                int srcBase = row * uRowStride;
                int dstBase = row * cw;
                for (int col = 0; col < cw; col++) {
                    uOut[dstBase + col] = uBuf.get(srcBase + col * 2);
                    vOut[dstBase + col] = vBuf.get(srcBase + col * 2);
                }
            }
        } else {
            for (int row = 0; row < ch; row++) {
                int dstBase = row * cw;
                for (int col = 0; col < cw; col++) {
                    uOut[dstBase + col] = uBuf.get(row * uRowStride + col * uPixelStride);
                    vOut[dstBase + col] = vBuf.get(row * vRowStride + col * vPixelStride);
                }
            }
        }
    }

    private static int selectVideoTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }
}
