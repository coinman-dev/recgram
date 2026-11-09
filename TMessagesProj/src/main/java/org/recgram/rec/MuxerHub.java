package org.recgram.rec;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Общий MediaMuxer для нескольких кодировщиков (звук и видео в одном файле).
 * Запускается, когда добавлены все дорожки; до этого сэмплы копятся в памяти.
 * Закрывается, когда все кодировщики закончили.
 */
final class MuxerHub {

    private static final long MAX_PENDING_BYTES = 32L * 1024 * 1024;

    private static final class Pending {
        final int track;
        final byte[] data;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        Pending(int track, ByteBuffer buf, MediaCodec.BufferInfo src) {
            this.track = track;
            data = new byte[src.size];
            buf.position(src.offset);
            buf.get(data, 0, src.size);
            info.set(0, src.size, src.presentationTimeUs, src.flags);
        }
    }

    private final MediaMuxer muxer;
    private int expected;
    private int added;
    private int finished;
    private boolean started;
    private boolean released;
    private long pendingBytes;
    private final ArrayList<Pending> pending = new ArrayList<>();

    MuxerHub(File file, int outputFormat, int expectedTracks) throws IOException {
        muxer = new MediaMuxer(file.getAbsolutePath(), outputFormat);
        expected = expectedTracks;
    }

    synchronized boolean isStarted() {
        return started;
    }

    synchronized int addTrack(MediaFormat format) {
        int t = muxer.addTrack(format);
        added++;
        maybeStart();
        return t;
    }

    /** Кодировщик не смог запуститься — файл пишется без его дорожки. */
    synchronized void abandonTrack() {
        expected--;
        maybeStart();
        tryRelease();
    }

    private void maybeStart() {
        if (!started && added > 0 && added >= expected) {
            muxer.start();
            started = true;
            for (Pending p : pending) {
                muxer.writeSampleData(p.track, ByteBuffer.wrap(p.data), p.info);
            }
            pending.clear();
            pendingBytes = 0;
        }
    }

    synchronized void write(int track, ByteBuffer buf, MediaCodec.BufferInfo info) {
        if (released || info.size <= 0) {
            return;
        }
        if (started) {
            buf.position(info.offset);
            buf.limit(info.offset + info.size);
            muxer.writeSampleData(track, buf, info);
        } else if (pendingBytes < MAX_PENDING_BYTES) {
            pending.add(new Pending(track, buf, info));
            pendingBytes += info.size;
        }
    }

    /** Кодировщик закончил. Последний закрывает файл. */
    synchronized void finish() {
        finishLocked();
    }

    private void finishLocked() {
        finished++;
        tryRelease();
    }

    private void tryRelease() {
        if (finished >= expected && !released) {
            released = true;
            try {
                if (started) {
                    muxer.stop();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            try {
                muxer.release();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            notifyAll();
        }
    }

    /** Ждать закрытия файла. */
    synchronized void awaitReleased(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!released) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                break;
            }
            try {
                wait(left);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
