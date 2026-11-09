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
 * Запись звонка: микрофон и собеседник, сведённые в моно 48 кГц, 64 кбит/с (Opus в .opus или AAC в .m4a).
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
                    MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1);
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
    /** Общий сборщик файла при видеозаписи; null — аудио пишется в свой файл. */
    private final MuxerHub videoHub;
    private final ShortFifo micFifo = new ShortFifo(RATE * 2);
    private final boolean autoLevel = org.recgram.RecGramConfig.isAutoLevel();
    private final AutoLevel micLevel = new AutoLevel();
    private final AutoLevel peerLevel = new AutoLevel();
    private final LinkedBlockingQueue<short[]> stereo = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile boolean stopping;
    private volatile boolean failed;
    private long frames;
    private long aacFrames;

    private CallRecorder(File file, MuxerHub videoHub) {
        this.file = file;
        this.videoHub = videoHub;
        thread = new Thread(this::encodeLoop, "RecGramCallRecorder");
    }

    /** Начать запись в файл. Возвращает null, если запись уже идёт. */
    static synchronized CallRecorder start(File file) {
        return start(file, null);
    }

    /** С videoHub звук пишется AAC в общий .mp4 вместе с видео. */
    static synchronized CallRecorder start(File file, MuxerHub videoHub) {
        if (active != null) {
            return null;
        }
        CallRecorder r = new CallRecorder(file, videoHub);
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
        VoiceNotice.process(buf, bytes, sampleRate, channels); // голосовое предупреждение вместо микрофона
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
        // После записи: владелец слышит предупреждение вместо собеседника (в файл оно попадает один раз — из микрофона)
        VoiceNotice.processPlayout(buf, bytes, sampleRate, channels);
    }

    private void mic(short[] m) {
        short[] flush = null;
        synchronized (micFifo) {
            if (autoLevel) micLevel.process(m);
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
        if (autoLevel) peerLevel.process(p);
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

    /** Сведение в моно: голос владельца + голос собеседника, с ограничением по краям. */
    private void emit(short[] mine, short[] peer, int n) {
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            int v = mine[i] + (peer != null ? peer[i] : 0);
            out[i] = autoLevel ? AutoLevel.softLimit(v) : (short) Math.max(-32768, Math.min(32767, v));
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
        MuxerHub hub = videoHub;
        int track = -1;
        boolean trackAdded = false;
        try {
            final boolean opus = videoHub == null && useOpus();
            final String mime = opus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
            MediaFormat format = MediaFormat.createAudioFormat(mime, RATE, 1);
            if (!opus) {
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            }
            format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec = MediaCodec.createEncoderByType(mime);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            if (hub == null) {
                hub = new MuxerHub(file, opus ? MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, 1);
            }
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
                            for (int i = 0; i < maxShorts; i++) {
                                short v = pending[pendingOff + i];
                                ib.put((byte) (v & 0xff));
                                ib.put((byte) ((v >> 8) & 0xff));
                            }
                            long pts = frames * 1000000L / RATE;
                            codec.queueInputBuffer(in, 0, maxShorts * 2, pts, 0);
                            frames += maxShorts;
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
                    track = hub.addTrack(codec.getOutputFormat());
                    trackAdded = true;
                } else if (out >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(out);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0 && trackAdded) {
                        if (!opus) {
                            // AAC: ровные метки времени — 1024 отсчёта на кадр (кодировщик выдаёт их неравномерно)
                            info.presentationTimeUs = aacFrames * 1024L * 1000000L / RATE;
                            aacFrames++;
                        }
                        hub.write(track, ob, info);
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
            if (hub != null) {
                if (!trackAdded) {
                    failed = true;
                    hub.abandonTrack();
                } else {
                    hub.finish();
                }
                if (videoHub == null) {
                    hub.awaitReleased(3000);
                    if (!hub.isStarted()) {
                        failed = true;
                    }
                }
            }
            synchronized (CallRecorder.class) {
                if (active == this) {
                    active = null;
                }
            }
        }
    }

    /**
     * Выравнивание громкости одного голоса: тихое плавно усиливается (до +18 дБ), громкое быстро
     * приглушается. Шум тише −50 дБ не усиливается.
     */
    static final class AutoLevel {
        private static final double TARGET = 0.1;     // ≈ −20 дБ от полной шкалы, среднее значение
        private static final double GATE = 0.00316;   // ≈ −50 дБ
        private static final double MAX_GAIN = 8.0;   // +18 дБ
        private static final double MIN_GAIN = 0.5;   // −6 дБ
        private double gain = 1.0;

        void process(short[] x) {
            if (x.length == 0) return;
            double sum = 0;
            for (short v : x) {
                double f = v / 32768.0;
                sum += f * f;
            }
            double rms = Math.sqrt(sum / x.length);
            double target = gain;
            if (rms > GATE) {
                target = Math.max(MIN_GAIN, Math.min(MAX_GAIN, TARGET / rms));
            }
            // быстро вниз (≈30 мс), медленно вверх (≈0,5 с) при блоках по 10 мс
            double k = target < gain ? 0.3 : 0.02;
            double next = gain + (target - gain) * k;
            for (int i = 0; i < x.length; i++) {
                double g = gain + (next - gain) * i / x.length;
                double v = x[i] * g;
                x[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
            }
            gain = next;
        }

        /** Мягкое ограничение суммы двух голосов: до ~90% шкалы без изменений, выше — плавно. */
        static short softLimit(int v) {
            final double knee = 29491; // 90%
            double a = Math.abs(v);
            if (a <= knee) return (short) v;
            double over = (a - knee) / (32767 - knee);
            double out = knee + (32767 - knee) * Math.tanh(over);
            return (short) (v < 0 ? -Math.round(out) : Math.round(out));
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
