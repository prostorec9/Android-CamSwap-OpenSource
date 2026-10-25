package io.github.zensu357.camswap;

import android.media.MediaPlayer;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Surface;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

/**
 * Manages all player backends, GLVideoRenderer, and SurfaceRelay instances.
 * Centralizes player lifecycle, restart, rotation, and release logic.
 */
public final class MediaPlayerManager {
    private final Object mediaLock = new Object();
    private final Map<MediaPlayer, ParcelFileDescriptor> activePfds = new ConcurrentHashMap<>();
    private String currentPackageName;
    private volatile long lastCamera2PlaybackStartRealtimeMs;
    private volatile int lastCamera2PlaybackDurationMs;

    // ---- Сохранение позиции воспроизведения при пересоздании камеры в баттлах ----
    private static volatile int savedPlaybackPositionMs = 0;
    private static volatile long savedPositionTimestampRealtimeMs = 0;
    private static volatile String lastKnownVideoId = "";

    // ---- Camera1 players (created by Camera1Handler) ----
    MediaPlayer mplayer1;
    MediaPlayer mMediaPlayer;
    GLVideoRenderer c1_renderer_holder;
    GLVideoRenderer c1_renderer_texture;

    // ---- Camera2 preview players ----
    MediaPlayer c2_player;
    MediaPlayer c2_player_1;
    GLVideoRenderer c2_renderer;
    GLVideoRenderer c2_renderer_1;
    SurfaceRelay c2_relay;
    SurfaceRelay c2_relay_1;

    // ---- Camera2 reader players ----
    MediaPlayer c2_reader_player;
    MediaPlayer c2_reader_player_1;
    GLVideoRenderer c2_reader_renderer;
    GLVideoRenderer c2_reader_renderer_1;
    SurfaceRelay c2_reader_relay;
    SurfaceRelay c2_reader_relay_1;

    // Per-slot surface tracking: skip re-init when surface unchanged
    private Surface lastC2ReaderSurface, lastC2ReaderSurface1;
    private Surface lastC2PreviewSurface, lastC2PreviewSurface1;

    // ---- Stream mode: single shared ExoPlayerBackend ----
    private SurfacePlayerBackend streamBackend;

    /** Set current package name (future per-app video). */
    public void setPackageName(String packageName) {
        this.currentPackageName = packageName;
    }

    /**
     * Central video path query.
     */
    String getVideoPath() {
        return VideoManager.getCurrentVideoPath();
    }

    /** Get current media source descriptor from config. */
    MediaSourceDescriptor getMediaSource() {
        return VideoManager.getCurrentMediaSource();
    }

    /** Whether we are currently in stream mode. */
    boolean isStreamMode() {
        return VideoManager.isStreamMode();
    }

    /**
     * Вычисляет актуальную позицию с учетом времени, прошедшего во время переключения в баттл.
     */
    private int getResumePositionMs(int durationMs) {
        String currentVideo = getVideoPath();
        if (currentVideo != null && !currentVideo.equals(lastKnownVideoId)) {
            lastKnownVideoId = currentVideo;
            savedPlaybackPositionMs = 0;
            savedPositionTimestampRealtimeMs = 0;
            return 0;
        }
        // Если какой-то плеер уже играет в параллельном слоте — синхронизируемся с ним
        MediaPlayer[] activePlayers = {
                c2_player, c2_player_1,
                c2_reader_player, c2_reader_player_1,
                mplayer1, mMediaPlayer
        };
        for (MediaPlayer mp : activePlayers) {
            if (mp != null) {
                try {
                    if (mp.isPlaying()) {
                        int pos = mp.getCurrentPosition();
                        if (pos > 0) {
                            savedPlaybackPositionMs = pos;
                            savedPositionTimestampRealtimeMs = SystemClock.elapsedRealtime();
                            return pos;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (savedPlaybackPositionMs > 0) {
            long targetPos = savedPlaybackPositionMs;
            if (savedPositionTimestampRealtimeMs > 0) {
                long delta = SystemClock.elapsedRealtime() - savedPositionTimestampRealtimeMs;
                // Компенсируем паузу переподключения камеры (если прошло менее 30 секунд)
                if (delta > 0 && delta < 30000) {
                    targetPos += delta;
                }
            }
            if (durationMs > 0) {
                targetPos = targetPos % durationMs;
            }
            return (int) targetPos;
        }
        return 0;
    }

    private void saveCurrentPositionBeforeRelease() {
        MediaPlayer[] players = {
                c2_player, c2_player_1,
                c2_reader_player, c2_reader_player_1,
                mplayer1, mMediaPlayer
        };
        for (MediaPlayer player : players) {
            if (player == null) continue;
            try {
                int pos = player.getCurrentPosition();
                if (pos > 0) {
                    savedPlaybackPositionMs = pos;
                    savedPositionTimestampRealtimeMs = SystemClock.elapsedRealtime();
                    LogUtil.log("【CS】Сохранена позиция перед переключением камеры: " + pos + " ms");
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        if (lastCamera2PlaybackStartRealtimeMs > 0) {
            long elapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - lastCamera2PlaybackStartRealtimeMs);
            if (lastCamera2PlaybackDurationMs > 0) {
                elapsedMs = elapsedMs % lastCamera2PlaybackDurationMs;
            }
            if (elapsedMs > 0) {
                savedPlaybackPositionMs = (int) elapsedMs;
                savedPositionTimestampRealtimeMs = SystemClock.elapsedRealtime();
            }
        }
    }

    private void seekPlayerSafely(MediaPlayer player, int positionMs) {
        if (player == null || positionMs <= 0) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST);
            } else {
                player.seekTo(positionMs);
            }
        } catch (Exception e) {
            try {
                player.seekTo(positionMs);
            } catch (Exception ignored) {
            }
        }
    }

    // =====================================================================
    // Camera2 player initialization
    // =====================================================================

    void initCamera2Players(Surface readerSurface, Surface readerSurface1,
            Surface previewSurface, Surface previewSurface1) {

        MediaSourceDescriptor source = getMediaSource();

        if (source.isStream()) {
            initCamera2PlayersStream(readerSurface, readerSurface1,
                    previewSurface, previewSurface1, source);
        } else {
            initCamera2PlayersLocal(readerSurface, readerSurface1,
                    previewSurface, previewSurface1);
        }
    }

    private void initCamera2PlayersLocal(Surface readerSurface, Surface readerSurface1,
            Surface previewSurface, Surface previewSurface1) {
        if (readerSurface != null && readerSurface != lastC2ReaderSurface) {
            c2_reader_player = recreatePlayer(c2_reader_player);
            GLVideoRenderer[] r = { c2_reader_renderer };
            SurfaceRelay[] rr = { c2_reader_relay };
            setupMediaPlayer(c2_reader_player, r, rr, readerSurface, "c2_reader", false);
            c2_reader_renderer = r[0];
            c2_reader_relay = rr[0];
            lastC2ReaderSurface = readerSurface;
        }
        if (readerSurface1 != null && readerSurface1 != lastC2ReaderSurface1) {
            c2_reader_player_1 = recreatePlayer(c2_reader_player_1);
            GLVideoRenderer[] r = { c2_reader_renderer_1 };
            SurfaceRelay[] rr = { c2_reader_relay_1 };
            setupMediaPlayer(c2_reader_player_1, r, rr, readerSurface1, "c2_reader_1", false);
            c2_reader_renderer_1 = r[0];
            c2_reader_relay_1 = rr[0];
            lastC2ReaderSurface1 = readerSurface1;
        }

        boolean playSound = VideoManager.getConfig().getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, false);

        if (previewSurface != null && previewSurface != lastC2PreviewSurface) {
            c2_player = recreatePlayer(c2_player);
            GLVideoRenderer[] r = { c2_renderer };
            SurfaceRelay[] rr = { c2_relay };
            setupMediaPlayer(c2_player, r, rr, previewSurface, "c2_preview", playSound);
            c2_renderer = r[0];
            c2_relay = rr[0];
            lastC2PreviewSurface = previewSurface;
        }
        if (previewSurface1 != null && previewSurface1 != lastC2PreviewSurface1) {
            c2_player_1 = recreatePlayer(c2_player_1);
            GLVideoRenderer[] r = { c2_renderer_1 };
            SurfaceRelay[] rr = { c2_relay_1 };
            setupMediaPlayer(c2_player_1, r, rr, previewSurface1, "c2_preview_1", playSound);
            c2_renderer_1 = r[0];
            c2_relay_1 = rr[0];
            lastC2PreviewSurface1 = previewSurface1;
        }
        LogUtil.log("【CS】Camera2处理过程完全执行（本地模式）");
    }

    private void initCamera2PlayersStream(Surface readerSurface, Surface readerSurface1,
            Surface previewSurface, Surface previewSurface1,
            MediaSourceDescriptor source) {
        releaseStreamBackend();

        Surface primaryTarget = previewSurface != null ? previewSurface : readerSurface;
        if (primaryTarget == null) {
            LogUtil.log("【CS】流模式：无可用目标 Surface");
            return;
        }

        if (readerSurface != null) {
            GLVideoRenderer.releaseSafely(c2_reader_renderer);
            SurfaceRelay.releaseSafely(c2_reader_relay);
            c2_reader_renderer = GLVideoRenderer.createSafely(readerSurface, "c2_reader_stream");
        }
        if (readerSurface1 != null) {
            GLVideoRenderer.releaseSafely(c2_reader_renderer_1);
            SurfaceRelay.releaseSafely(c2_reader_relay_1);
            c2_reader_renderer_1 = GLVideoRenderer.createSafely(readerSurface1, "c2_reader_1_stream");
        }
        if (previewSurface != null) {
            GLVideoRenderer.releaseSafely(c2_renderer);
            SurfaceRelay.releaseSafely(c2_relay);
            c2_renderer = GLVideoRenderer.createSafely(previewSurface, "c2_preview_stream");
        }
        if (previewSurface1 != null) {
            GLVideoRenderer.releaseSafely(c2_renderer_1);
            SurfaceRelay.releaseSafely(c2_relay_1);
            c2_renderer_1 = GLVideoRenderer.createSafely(previewSurface1, "c2_preview_1_stream");
        }

        try {
            streamBackend = createStreamBackend();
            GLVideoRenderer primaryRenderer = (previewSurface != null) ? c2_renderer : c2_reader_renderer;
            Surface backendSurface;
            if (primaryRenderer != null && primaryRenderer.isInitialized()) {
                backendSurface = primaryRenderer.getInputSurface();
            } else {
                backendSurface = primaryTarget;
            }
            streamBackend.setOutputSurface(backendSurface);
            streamBackend.setVolume(VideoManager.getConfig().getBoolean(
                    ConfigManager.KEY_PLAY_VIDEO_SOUND, false) ? 1.0f : 0f);
            streamBackend.setLooping(false);
            streamBackend.setListener(new SurfacePlayerBackend.Listener() {
                @Override
                public void onReady() {
                    LogUtil.log("【CS】流播放器就绪");
                    lastCamera2PlaybackStartRealtimeMs = SystemClock.elapsedRealtime();
                }

                @Override
                public void onError(String message, Throwable cause) {
                    LogUtil.log("【CS】流播放器错误: " + message
                            + (cause != null ? " " + cause : ""));
                }

                @Override
                public void onDisconnected() {
                    LogUtil.log("【CS】流断开连接");
                }

                @Override
                public void onReconnected() {
                    LogUtil.log("【CS】流重连成功");
                }

                @Override
                public void onCompletion() {
                    LogUtil.log("【CS】流播放完成");
                }
            });
            streamBackend.open(source);
            LogUtil.log("【CS】Camera2处理过程完全执行（流模式: " + source.streamUrl + "）");
        } catch (Exception e) {
            LogUtil.log("【CS】流模式初始化失败: " + android.util.Log.getStackTraceString(e));
        }
    }

    private SurfacePlayerBackend createStreamBackend() {
        try {
            Class<?> clazz = Class.forName("io.github.zensu357.camswap.ExoPlayerBackend");
            return (SurfacePlayerBackend) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            LogUtil.log("【CS】ExoPlayerBackend 不可用，回退到 AndroidMediaPlayerBackend: " + e);
            return new AndroidMediaPlayerBackend();
        }
    }

    private void releaseStreamBackend() {
        if (streamBackend != null) {
            streamBackend.release();
            streamBackend = null;
        }
    }

    public void bindCamera1Pfd(MediaPlayer player, ParcelFileDescriptor pfd) {
        bindPfd(player, pfd);
    }

    public void closeCamera1Pfd(MediaPlayer player) {
        closePfd(player);
    }

    private void bindPfd(MediaPlayer player, ParcelFileDescriptor pfd) {
        if (player == null || pfd == null) return;
        ParcelFileDescriptor old = activePfds.put(player, pfd);
        if (old != null && old != pfd) {
            try {
                old.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void closePfd(MediaPlayer player) {
        if (player == null) return;
        ParcelFileDescriptor pfd = activePfds.remove(player);
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
        }
    }

    private MediaPlayer recreatePlayer(MediaPlayer old) {
        if (old != null) {
            try {
                int pos = old.getCurrentPosition();
                if (pos > 0) {
                    savedPlaybackPositionMs = pos;
                    savedPositionTimestampRealtimeMs = SystemClock.elapsedRealtime();
                }
            } catch (Exception ignored) {
            }
            closePfd(old);
            old.release();
        }
        return new MediaPlayer();
    }

    long getCamera2PlaybackPositionMs() {
        if (streamBackend != null) {
            try {
                long pos = streamBackend.getCurrentPositionMs();
                if (pos > 0) return pos;
            } catch (Exception ignored) {
            }
        }

        MediaPlayer[] players = {
                c2_player, c2_player_1,
                c2_reader_player, c2_reader_player_1,
                mplayer1, mMediaPlayer
        };
        for (MediaPlayer player : players) {
            if (player == null) {
                continue;
            }
            try {
                int position = player.getCurrentPosition();
                if (position > 0) {
                    savedPlaybackPositionMs = position;
                    savedPositionTimestampRealtimeMs = SystemClock.elapsedRealtime();
                    return position;
                }
            } catch (Exception ignored) {
            }
        }
        if (lastCamera2PlaybackStartRealtimeMs > 0) {
            long elapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - lastCamera2PlaybackStartRealtimeMs);
            if (lastCamera2PlaybackDurationMs > 0) {
                return elapsedMs % lastCamera2PlaybackDurationMs;
            }
            return elapsedMs;
        }
        return savedPlaybackPositionMs;
    }

    private void markCamera2PlaybackStarted(MediaPlayer player, String tag, int startOffsetMs) {
        if (tag == null || !tag.startsWith("c2_") || player == null) {
            return;
        }
        lastCamera2PlaybackStartRealtimeMs = SystemClock.elapsedRealtime() - Math.max(0, startOffsetMs);
        try {
            lastCamera2PlaybackDurationMs = Math.max(0, player.getDuration());
        } catch (Exception ignored) {
            lastCamera2PlaybackDurationMs = 0;
        }
    }

    // =====================================================================
    // Restart / rotation / release
    // =====================================================================

    void restartAll() {
        synchronized (mediaLock) {
            saveCurrentPositionBeforeRelease();
            if (isStreamMode()) {
                if (streamBackend != null) {
                    streamBackend.restart();
                }
            } else {
                VideoManager.checkProviderAvailability();
                restartSinglePlayer(mplayer1, c1_renderer_holder, "mplayer1");
                restartSinglePlayer(mMediaPlayer, c1_renderer_texture, "mMediaPlayer");
                restartSinglePlayer(c2_reader_player, c2_reader_renderer, "c2_reader_player");
                restartSinglePlayer(c2_reader_player_1, c2_reader_renderer_1, "c2_reader_player_1");
                restartSinglePlayer(c2_player, c2_renderer, "c2_player");
                restartSinglePlayer(c2_player_1, c2_renderer_1, "c2_player_1");
            }
        }
    }

    private void restartSinglePlayer(MediaPlayer player, GLVideoRenderer renderer, String tag) {
        if (player == null)
            return;
        try {
            int posBeforeRestart = savedPlaybackPositionMs;
            try {
                if (player.isPlaying()) {
                    int current = player.getCurrentPosition();
                    if (current > 0) posBeforeRestart = current;
                    player.stop();
                }
            } catch (Exception ignored) {
            }
            player.reset();
            if (renderer != null && renderer.isInitialized()) {
                player.setSurface(renderer.getInputSurface());
            }
            android.os.ParcelFileDescriptor pfd = VideoManager.getVideoPFD();
            if (pfd != null) {
                bindPfd(player, pfd);
                player.setDataSource(pfd.getFileDescriptor());
            } else {
                closePfd(player);
                player.setDataSource(getVideoPath());
            }
            player.prepare();
            int resumePos = getResumePositionMs(player.getDuration());
            if (resumePos <= 0 && posBeforeRestart > 0) {
                resumePos = posBeforeRestart;
            }
            seekPlayerSafely(player, resumePos);
            player.start();
        } catch (Exception e) {
            LogUtil.log("【CS】重启 " + tag + " 失败: " + android.util.Log.getStackTraceString(e));
        }
    }

    void updateRotation(int degrees) {
        LogUtil.log("【CS】旋转偏移已更新: " + degrees + "°（渲染器保持0°，仅影响截帧）");
    }

    void releaseAllRenderers() {
        GLVideoRenderer.releaseSafely(c2_reader_renderer);
        c2_reader_renderer = null;
        GLVideoRenderer.releaseSafely(c2_reader_renderer_1);
        c2_reader_renderer_1 = null;
        GLVideoRenderer.releaseSafely(c2_renderer);
        c2_renderer = null;
        GLVideoRenderer.releaseSafely(c2_renderer_1);
        c2_renderer_1 = null;
        GLVideoRenderer.releaseSafely(c1_renderer_holder);
        c1_renderer_holder = null;
        GLVideoRenderer.releaseSafely(c1_renderer_texture);
        c1_renderer_texture = null;
    }

    void releaseCamera1Resources() {
        saveCurrentPositionBeforeRelease();
        GLVideoRenderer.releaseSafely(c1_renderer_holder);
        c1_renderer_holder = null;
        GLVideoRenderer.releaseSafely(c1_renderer_texture);
        c1_renderer_texture = null;
        stopAndRelease(mplayer1);
        mplayer1 = null;
        stopAndRelease(mMediaPlayer);
        mMediaPlayer = null;
    }

    void releaseCamera2Resources() {
        saveCurrentPositionBeforeRelease();
        releaseStreamBackend();
        GLVideoRenderer.releaseSafely(c2_renderer);
        c2_renderer = null;
        GLVideoRenderer.releaseSafely(c2_renderer_1);
        c2_renderer_1 = null;
        GLVideoRenderer.releaseSafely(c2_reader_renderer);
        c2_reader_renderer = null;
        GLVideoRenderer.releaseSafely(c2_reader_renderer_1);
        c2_reader_renderer_1 = null;
        stopAndRelease(c2_player);
        c2_player = null;
        stopAndRelease(c2_reader_player_1);
        c2_reader_player_1 = null;
        stopAndRelease(c2_reader_player);
        c2_reader_player = null;
        stopAndRelease(c2_player_1);
        c2_player_1 = null;
        lastC2ReaderSurface = null;
        lastC2ReaderSurface1 = null;
        lastC2PreviewSurface = null;
        lastC2PreviewSurface1 = null;
    }

    private void stopAndRelease(MediaPlayer player) {
        if (player == null)
            return;
        try {
            int pos = player.getCurrentPosition();
            if (pos > 0) {
                savedPlaybackPositionMs = pos;
                savedPositionTimestampRealtimeMs = SystemClock.elapsedRealtime();
            }
        } catch (Exception ignored) {
        }
        closePfd(player);
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        player.release();
    }

    // =====================================================================
    // Private: three-tier surface rendering setup (local mode)
    // =====================================================================

    private void setupMediaPlayer(MediaPlayer player, GLVideoRenderer[] rendererRef,
            SurfaceRelay[] relayRef, Surface targetSurface, String tag, boolean playSound) {
        if (targetSurface == null) {
            LogUtil.w("【CS】【播放器】[" + tag + "] 目标 Surface 为 null，跳过初始化");
            return;
        }
        boolean isSurfaceValid = targetSurface.isValid();
        LogUtil.log("【CS】【播放器】[" + tag + "] 开始配置 MediaPlayer -> TargetSurface: "
                + targetSurface + " (isValid=" + isSurfaceValid + ")");
        if (!isSurfaceValid) {
            LogUtil.w("【CS】【播放器】【警告】[" + tag + "] 目标 Surface 已失效 (isValid=false)，可能会导致渲染黑屏或失败！");
        }

        GLVideoRenderer.releaseSafely(rendererRef[0]);
        SurfaceRelay.releaseSafely(relayRef[0]);
        rendererRef[0] = null;
        relayRef[0] = null;

        if (!playSound)
            player.setVolume(0, 0);
        player.setLooping(true);
        try {
            int rotation = VideoManager.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
            rendererRef[0] = GLVideoRenderer.createSafely(targetSurface, tag);
            if (rendererRef[0] != null) {
                player.setSurface(rendererRef[0].getInputSurface());
                rendererRef[0].setRotation(rotation);
            } else {
                relayRef[0] = SurfaceRelay.createSafely(targetSurface, tag);
                if (relayRef[0] != null) {
                    player.setSurface(relayRef[0].getInputSurface());
                    relayRef[0].setRotation(rotation);
                } else {
                    player.setSurface(targetSurface);
                }
            }

            android.os.ParcelFileDescriptor pfd = VideoManager.getVideoPFD();
            String currentPath = getVideoPath();
            if (pfd != null) {
                bindPfd(player, pfd);
                player.setDataSource(pfd.getFileDescriptor());
            } else {
                closePfd(player);
                player.setDataSource(currentPath);
            }

            player.setOnErrorListener((mp, what, extra) -> {
                String diagnosis = LogUtil.explainMediaPlayerError(what, extra);
                LogUtil.e("【CS】【播放器】【致命错误】[" + tag + "] MediaPlayer 触发错误 -> "
                        + diagnosis + " (what=" + what + ", extra=" + extra + ")", null);
                return true;
            });

            long prepareStart = SystemClock.elapsedRealtime();
            player.prepare();
            long prepareDuration = SystemClock.elapsedRealtime() - prepareStart;

            int videoW = player.getVideoWidth();
            int videoH = player.getVideoHeight();
            int duration = player.getDuration();

            // Восстанавливаем позицию после переключения камеры в баттл
            int resumePosMs = getResumePositionMs(duration);
            if (resumePosMs > 0) {
                seekPlayerSafely(player, resumePosMs);
                LogUtil.log("【CS】【播放器】[" + tag + "] Продолжение воспроизведения с позиции: " + resumePosMs + " ms");
            }

            player.start();
            markCamera2PlaybackStarted(player, tag, resumePosMs);
            LogUtil.log("【CS】【播放器】[" + tag + "] 播放器启动成功！(视频规格: " + videoW + "x" + videoH
                    + ", 时长: " + duration + "ms, prepare耗时: " + prepareDuration + "ms, resumePos=" + resumePosMs + "ms)");
        } catch (Exception e) {
            LogUtil.e("【CS】【播放器】【初始化异常】[" + tag + "] 启动播放器失败: " + e.getMessage(), e);
        }
    }
}
