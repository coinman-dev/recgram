package org.recgram.rec;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import org.telegram.messenger.FileLog;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Запись звонка: микрофон (левый канал) и собеседник (правый канал), AAC 48 кГц в .m4a.
 * Данные приходят из потоков WebRTC: onMic — после чтения микрофона, onPlayout — перед воспроизведением.
 */
public final class CallRecorder {

    private static final int RATE = 48000;

    /** Opus в Ogg есть в Android 10+, на старых версиях — AAC в MP4. */
    private static Boolean opusAvailable;

    public static synchronized boolean useOpus() {
        if (opusAvailable == null) {
            boolean ok = false;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                try {
                    MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 2);
                    ok = new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f) != null;
                } catch (Throwable ignore) {
                }
            }
            opusAvailable = ok;
        }
        return opusAvailable;
    }

    public static String extension() {
        return useOpus() ? ".opus" : ".m4a";
    }

    public static String mimeType() {
        return useOpus() ? "audio/ogg" : "audio/mp4";
    }
    /** Если звука собеседника нет дольше 200 мс, микрофон пишется с тишиной справа. */
    private static final int MIC_BACKLOG = RATE / 5;

    private static volatile CallRecorder active;

    private final File file;
    private final ShortFifo micFifo = new ShortFifo(RATE * 2);
    private final LinkedBlockingQueue<short[]> stereo = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile boolean stopping;
    private volatile boolean failed;
    private long frames;

    private CallRecorder(File file) {
        this.file = file;
        thread = new Thread(this::encodeLoop, "RecGramCallRecorder");
    }

    /** Начать запись в файл. Возвращает null, если запись уже идёт. */
    static synchronized CallRecorder start(File file) {
        if (active != null) {
            return null;
        }
        CallRecorder r = new CallRecorder(file);
        r.thread.start();
        active = r;
        return r;
    }

    /** Остановить запись и дождаться закрытия файла. */
    void stop() {
        synchronized (CallRecorder.class) {
            if (active == this) {
                active = null;
            }
        }
        flushMic();
        stopping = true;
        try {
            thread.join(5000);
        } catch (InterruptedException ignore) {
        }
    }

    boolean isFailed() {
        return failed;
    }

    long durationMs() {
        return frames * 1000L / RATE;
    }

    File getFile() {
        return file;
    }

    // ---- данные из WebRTC ----

    public static void onMic(ByteBuffer buf, int bytes, int sampleRate, int channels) {
        CallRecorder r = active;
        if (r != null) {
            r.mic(toMono48k(buf, bytes, sampleRate, channels));
        }
    }

    public static void onPlayout(ByteBuffer buf, int bytes, int sampleRate, int channels) {
        CallRecorder r = active;
        if (r != null) {
            r.playout(toMono48k(buf, bytes, sampleRate, channels));
        }
    }

    private void mic(short[] m) {
        short[] flush = null;
        synchronized (micFifo) {
            micFifo.write(m);
            if (micFifo.size() > MIC_BACKLOG) {
                flush = new short[micFifo.size() / 2];
                micFifo.read(flush, flush.length);
            }
        }
        if (flush != null) {
            emit(flush, null, flush.length);
        }
    }

    private void playout(short[] p) {
        short[] m = new short[p.length];
        synchronized (micFifo) {
            micFifo.read(m, p.length);
        }
        emit(m, p, p.length);
    }

    private void flushMic() {
        short[] rest;
        synchronized (micFifo) {
            rest = new short[micFifo.size()];
            micFifo.read(rest, rest.length);
        }
        if (rest.length > 0) {
            emit(rest, null, rest.length);
        }
    }

    private void emit(short[] left, short[] right, int n) {
        short[] out = new short[n * 2];
        for (int i = 0; i < n; i++) {
            out[i * 2] = left[i];
            out[i * 2 + 1] = right != null ? right[i] : 0;
        }
        stereo.offer(out);
    }

    /** PCM 16 бит little-endian → моно 48 кГц. */
    static short[] toMono48k(ByteBuffer buf, int bytes, int sampleRate, int channels) {
        if (channels < 1) channels = 1;
        int n = bytes / 2 / channels;
        short[] mono = new short[n];
        for (int i = 0; i < n; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int idx = (i * channels + c) * 2;
                sum += (short) ((buf.get(idx) & 0xff) | (buf.get(idx + 1) << 8));
            }
            mono[i] = (short) (sum / channels);
        }
        if (sampleRate == RATE || sampleRate <= 0 || n == 0) {
            return mono;
        }
        int outN = (int) ((long) n * RATE / sampleRate);
        short[] out = new short[outN];
        for (int i = 0; i < outN; i++) {
            double pos = (double) i * sampleRate / RATE;
            int a = (int) pos;
            int b = Math.min(a + 1, n - 1);
            double f = pos - a;
            out[i] = (short) (mono[a] * (1 - f) + mono[b] * f);
        }
        return out;
    }

    // ---- кодирование ----

    private void encodeLoop() {
        MediaCodec codec = null;
        MediaMuxer muxer = null;
        int track = -1;
        boolean muxerStarted = false;
        try {
            final boolean opus = useOpus();
            final String mime = opus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
            MediaFormat format = MediaFormat.createAudioFormat(mime, RATE, 2);
            if (!opus) {
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            }
            format.setInteger(MediaFormat.KEY_BIT_RATE, opus ? 64000 : 128000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec = MediaCodec.createEncoderByType(mime);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(file.getAbsolutePath(), opus ? MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            short[] pending = null;
            int pendingOff = 0;
            boolean eosSent = false;
            while (true) {
                if (!eosSent) {
                    if (pending == null) {
                        pending = stereo.poll(20, TimeUnit.MILLISECONDS);
                        pendingOff = 0;
                    }
                    if (pending != null) {
                        int in = codec.dequeueInputBuffer(10000);
                        if (in >= 0) {
                            ByteBuffer ib = codec.getInputBuffer(in);
                            ib.clear();
                            int maxShorts = Math.min(ib.remaining() / 2, pending.length - pendingOff);
                            maxShorts -= maxShorts % 2;
                            for (int i = 0; i < maxShorts; i++) {
                                short v = pending[pendingOff + i];
                                ib.put((byte) (v & 0xff));
                                ib.put((byte) ((v >> 8) & 0xff));
                            }
                            long pts = frames * 1000000L / RATE;
                            codec.queueInputBuffer(in, 0, maxShorts * 2, pts, 0);
                            frames += maxShorts / 2;
                            pendingOff += maxShorts;
                            if (pendingOff >= pending.length) {
                                pending = null;
                            }
                        }
                    } else if (stopping && stereo.isEmpty()) {
                        int in = codec.dequeueInputBuffer(10000);
                        if (in >= 0) {
                            codec.queueInputBuffer(in, 0, 0, frames * 1000000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            eosSent = true;
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, eosSent ? 10000 : 0);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (out >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(out);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0 && muxerStarted) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        muxer.writeSampleData(track, ob, info);
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            failed = true;
            FileLog.e(e);
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Throwable ignore) {
            }
            try {
                if (muxer != null) {
                    if (muxerStarted) {
                        muxer.stop();
                    }
                    muxer.release();
                }
            } catch (Throwable e) {
                failed = true;
                FileLog.e(e);
            }
            synchronized (CallRecorder.class) {
                if (active == this) {
                    active = null;
                }
            }
        }
    }

    /** Кольцевой буфер сэмплов. */
    private static final class ShortFifo {
        private short[] data;
        private int head, size;

        ShortFifo(int capacity) {
            data = new short[capacity];
        }

        int size() {
            return size;
        }

        void write(short[] src) {
            if (size + src.length > data.length) {
                short[] grown = new short[Math.max(data.length * 2, size + src.length)];
                for (int i = 0; i < size; i++) {
                    grown[i] = data[(head + i) % data.length];
                }
                data = grown;
                head = 0;
            }
            for (short s : src) {
                data[(head + size) % data.length] = s;
                size++;
            }
        }

        /** Читает до n сэмплов, остаток dst заполняется нулями. */
        int read(short[] dst, int n) {
            int k = Math.min(n, size);
            for (int i = 0; i < k; i++) {
                dst[i] = data[(head + i) % data.length];
            }
            for (int i = k; i < n; i++) {
                dst[i] = 0;
            }
            head = (head + k) % data.length;
            size -= k;
            return k;
        }
    }
}
