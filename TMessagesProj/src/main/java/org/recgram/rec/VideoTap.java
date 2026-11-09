package org.recgram.rec;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.webrtc.EglBase;
import org.webrtc.GlRectDrawer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoFrameDrawer;
import org.webrtc.VideoSink;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Видео звонка: собеседник на весь кадр, своя камера в окне справа сверху.
 * Кадры рисуются через EGL в поверхность кодировщика H.264, результат — в общий MuxerHub.
 */
final class VideoTap {

    private static final int W = 720;
    private static final int H = 1280;
    private static final int FPS = 30;
    private static final int BITRATE = 2_500_000;

    private final MuxerHub hub;
    private final long startNs;
    private final HandlerThread glThread = new HandlerThread("RecGramVideo");
    private Handler gl;
    private EglBase egl;
    private GlRectDrawer drawer;
    private VideoFrameDrawer frameDrawer;
    private MediaCodec encoder;
    private Surface input;
    private Thread drainThread;
    private volatile boolean failed;
    private volatile boolean stopped;
    private VideoFrame lastLocal;
    private long lastRemoteNs;
    private long lastPtsNs = -1;
    private final AtomicInteger queued = new AtomicInteger();

    final VideoSink remoteSink = this::onRemote;
    final VideoSink localSink = this::onLocal;

    private VideoTap(MuxerHub hub, long startNs) {
        this.hub = hub;
        this.startNs = startNs;
    }

    static VideoTap start(MuxerHub hub, long startNs) {
        VideoTap t = new VideoTap(hub, startNs);
        t.glThread.start();
        t.gl = new Handler(t.glThread.getLooper());
        t.gl.post(t::init);
        return t;
    }

    private void init() {
        try {
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = encoder.createInputSurface();
            encoder.start();
            egl = EglBase.create(VideoCapturerDevice.getEglBase().getEglBaseContext(), EglBase.CONFIG_RECORDABLE);
            egl.createSurface(input);
            egl.makeCurrent();
            drawer = new GlRectDrawer();
            frameDrawer = new VideoFrameDrawer();
            drainThread = new Thread(this::drainLoop, "RecGramVideoDrain");
            drainThread.start();
            // Чёрный кадр сразу: кодировщик выдаёт формат, и файл начинает писаться без ожидания видео
            render(null);
        } catch (Throwable e) {
            FileLog.e(e);
            failed = true;
            releaseGl();
            hub.abandonTrack();
        }
    }

    boolean isFailed() {
        return failed;
    }

    // ---- кадры из звонка (потоки WebRTC) ----

    private void onRemote(VideoFrame frame) {
        if (stopped || failed || gl == null || queued.get() > 2) {
            return;
        }
        frame.retain();
        queued.incrementAndGet();
        if (!gl.post(() -> {
            queued.decrementAndGet();
            lastRemoteNs = System.nanoTime();
            render(frame);
            frame.release();
        })) {
            queued.decrementAndGet();
            frame.release();
        }
    }

    private void onLocal(VideoFrame frame) {
        if (stopped || failed || gl == null) {
            return;
        }
        frame.retain();
        if (!gl.post(() -> {
            if (lastLocal != null) {
                lastLocal.release();
            }
            lastLocal = frame;
            // Видео собеседника нет — пишем свою камеру
            if (System.nanoTime() - lastRemoteNs > 300_000_000L) {
                render(null);
            }
        })) {
            frame.release();
        }
    }

    /** remote == null: собеседника нет, на весь кадр своя камера (или чёрный экран). */
    private void render(VideoFrame remote) {
        if (egl == null || stopped) {
            return;
        }
        try {
            GLES20.glViewport(0, 0, W, H);
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (remote != null) {
                drawFill(remote);
                if (lastLocal != null) {
                    drawPip(lastLocal);
                }
            } else if (lastLocal != null) {
                drawFill(lastLocal);
            }
            long pts = System.nanoTime() - startNs;
            if (pts <= lastPtsNs) {
                pts = lastPtsNs + 1000;
            }
            lastPtsNs = pts;
            egl.swapBuffers(pts, false);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void drawFill(VideoFrame f) {
        int fw = f.getRotatedWidth(), fh = f.getRotatedHeight();
        if (fw <= 0 || fh <= 0) return;
        float scale = Math.max((float) W / fw, (float) H / fh);
        int vw = Math.round(fw * scale), vh = Math.round(fh * scale);
        frameDrawer.drawFrame(f, drawer, null, (W - vw) / 2, (H - vh) / 2, vw, vh, false, false);
    }

    private void drawPip(VideoFrame f) {
        int fw = f.getRotatedWidth(), fh = f.getRotatedHeight();
        if (fw <= 0 || fh <= 0) return;
        int pw = W / 4;
        int ph = pw * fh / fw;
        int margin = 24;
        // В GL ось Y снизу вверх: окно в правом верхнем углу
        frameDrawer.drawFrame(f, drawer, null, W - pw - margin, H - ph - margin, pw, ph, false, false);
    }

    // ---- кодировщик ----

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int track = -1;
        boolean added = false;
        try {
            while (true) {
                int out = encoder.dequeueOutputBuffer(info, 10000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = hub.addTrack(encoder.getOutputFormat());
                    added = true;
                } else if (out >= 0) {
                    ByteBuffer ob = encoder.getOutputBuffer(out);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (added && info.size > 0) {
                        hub.write(track, ob, info);
                    }
                    encoder.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (added) {
                hub.finish();
            } else {
                hub.abandonTrack();
            }
            try {
                encoder.stop();
                encoder.release();
            } catch (Throwable ignore) {
            }
        }
    }

    void stop() {
        if (stopped) {
            return;
        }
        if (gl == null || failed) {
            stopped = true;
            return;
        }
        gl.post(() -> {
            stopped = true;
            try {
                encoder.signalEndOfInputStream();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            releaseGl();
        });
        try {
            glThread.quitSafely();
            glThread.join(3000);
            if (drainThread != null) {
                drainThread.join(5000);
            }
        } catch (InterruptedException ignore) {
        }
    }

    private void releaseGl() {
        if (lastLocal != null) {
            lastLocal.release();
            lastLocal = null;
        }
        try {
            if (frameDrawer != null) frameDrawer.release();
            if (drawer != null) drawer.release();
            if (egl != null) egl.release();
            if (input != null) input.release();
        } catch (Throwable ignore) {
        }
        egl = null;
    }
}
