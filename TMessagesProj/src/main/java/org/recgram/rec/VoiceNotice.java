package org.recgram.rec;

import static org.recgram.RLocale.s;

import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.recgram.RLocale;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Голосовое предупреждение «Этот разговор записывается»: синтезируется встроенным TTS телефона
 * и подаётся собеседнику вместо микрофона в начале звонка. Запись начинается после него.
 */
public final class VoiceNotice {

    private static short[] pcm;          // моно, 16 бит
    private static int pcmRate;
    private static String pcmLang;
    private static boolean preparing;

    /** Идёт воспроизведение собеседнику. */
    private static volatile short[] playing;
    private static volatile int playRate;
    private static double playPos;
    private static double localPos;
    private static Runnable onDone;

    private VoiceNotice() {
    }

    public static boolean isPlaying() {
        return playing != null;
    }

    private static String text() {
        return s("Внимание. Этот разговор записывается. Говорите после сигнала.", "Attention. This call is being recorded. Please speak after the tone.");
    }

    /** Заранее синтезировать фразу (например, при включении настройки). */
    public static void prepare() {
        prepare(null);
    }

    private static synchronized void prepare(Runnable ready) {
        final String lang = RLocale.isRussian() ? "ru" : "en";
        if (pcm != null && lang.equals(pcmLang)) {
            if (ready != null) ready.run();
            return;
        }
        if (preparing) {
            if (ready != null) AndroidUtilities.runOnUIThread(() -> prepare(ready), 300);
            return;
        }
        preparing = true;
        AndroidUtilities.runOnUIThread(() -> {
            final File out = new File(ApplicationLoader.applicationContext.getCacheDir(), "recgram_notice2_" + lang + ".wav");
            final TextToSpeech[] holder = new TextToSpeech[1];
            holder[0] = new TextToSpeech(ApplicationLoader.applicationContext, status -> {
                TextToSpeech tts = holder[0];
                if (status != TextToSpeech.SUCCESS) {
                    finishPrepare(null, 0, lang, tts, ready);
                    return;
                }
                tts.setLanguage(RLocale.uiLocale());
                tts.setSpeechRate(0.95f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) {
                        int[] rate = new int[1];
                        short[] data = readWav(out, rate);
                        finishPrepare(data, rate[0], lang, tts, ready);
                    }
                    @Override public void onError(String id) {
                        finishPrepare(null, 0, lang, tts, ready);
                    }
                });
                Bundle params = new Bundle();
                tts.synthesizeToFile(text(), params, out, "recgram_notice");
            });
        });
    }

    private static synchronized void finishPrepare(short[] data, int rate, String lang, TextToSpeech tts, Runnable ready) {
        preparing = false;
        if (data != null && data.length > 0 && rate > 0) {
            short[] beep = beep(rate);
            short[] gap = new short[rate / 5]; // 0,2 с тишины между сигналом и речью
            short[] all = new short[beep.length * 2 + gap.length * 2 + data.length];
            int o = 0;
            System.arraycopy(beep, 0, all, o, beep.length); o += beep.length;
            o += gap.length;
            System.arraycopy(data, 0, all, o, data.length); o += data.length;
            o += gap.length;
            System.arraycopy(beep, 0, all, o, beep.length);
            data = all;
            pcm = data;
            pcmRate = rate;
            pcmLang = lang;
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                tts.shutdown();
            } catch (Throwable ignore) {
            }
            if (ready != null) ready.run();
        });
    }

    /** Проиграть собеседнику, затем вызвать done (на UI-потоке). Если синтез недоступен — done сразу. */
    public static void play(Runnable done) {
        prepare(() -> {
            short[] data = pcm;
            if (data == null) {
                FileLog.d("RecGram: voice notice unavailable (TTS)");
                done.run();
                return;
            }
            synchronized (VoiceNotice.class) {
                onDone = done;
                playRate = pcmRate;
                playPos = 0;
                localPos = 0;
                playing = data;
            }
            // Страховка: если микрофонный поток не идёт, всё равно продолжить
            long ms = data.length * 1000L / pcmRate + 1500;
            AndroidUtilities.runOnUIThread(VoiceNotice::finishPlay, ms);
        });
    }

    private static void finishPlay() {
        Runnable r;
        synchronized (VoiceNotice.class) {
            if (playing == null && onDone == null) {
                return;
            }
            playing = null;
            r = onDone;
            onDone = null;
        }
        if (r != null) {
            AndroidUtilities.runOnUIThread(r);
        }
    }

    /** Звонок закончился раньше — отменить. */
    public static void cancel() {
        synchronized (VoiceNotice.class) {
            playing = null;
            onDone = null;
        }
    }

    /** Из потока микрофона WebRTC: пока идёт предупреждение, заменить звук микрофона на фразу. */
    static void process(ByteBuffer buf, int bytes, int sampleRate, int channels) {
        short[] data = playing;
        if (data == null || sampleRate <= 0) {
            return;
        }
        if (channels < 1) channels = 1;
        int frames = bytes / 2 / channels;
        double step = (double) playRate / sampleRate;
        boolean end = false;
        synchronized (VoiceNotice.class) {
            for (int i = 0; i < frames; i++) {
                int idx = (int) playPos;
                short v = 0;
                if (idx < data.length) {
                    v = data[idx];
                    playPos += step;
                } else {
                    end = true;
                }
                for (int c = 0; c < channels; c++) {
                    int o = (i * channels + c) * 2;
                    buf.put(o, (byte) (v & 0xff));
                    buf.put(o + 1, (byte) ((v >> 8) & 0xff));
                }
            }
        }
        if (end) {
            finishPlay();
        }
    }

    /** Сигнал 1 с, 1000 Гц, с плавными краями. */
    private static short[] beep(int rate) {
        short[] b = new short[rate];
        int fade = rate / 50;
        for (int i = 0; i < rate; i++) {
            double env = Math.min(1.0, Math.min(i, rate - 1 - i) / (double) fade);
            b[i] = (short) (Math.sin(2 * Math.PI * 1000 * i / rate) * 9000 * env);
        }
        return b;
    }

    /**
     * Из потока воспроизведения WebRTC (после того, как запись взяла звук собеседника):
     * пока идёт предупреждение, владелец тоже слышит его вместо собеседника.
     */
    static void processPlayout(ByteBuffer buf, int bytes, int sampleRate, int channels) {
        short[] data = playing;
        if (data == null || sampleRate <= 0) {
            return;
        }
        if (channels < 1) channels = 1;
        int frames = bytes / 2 / channels;
        double step = (double) playRate / sampleRate;
        synchronized (VoiceNotice.class) {
            for (int i = 0; i < frames; i++) {
                int idx = (int) localPos;
                short v = 0;
                if (idx < data.length) {
                    v = data[idx];
                    localPos += step;
                }
                for (int c = 0; c < channels; c++) {
                    int o = (i * channels + c) * 2;
                    buf.put(o, (byte) (v & 0xff));
                    buf.put(o + 1, (byte) ((v >> 8) & 0xff));
                }
            }
        }
    }

    /** WAV PCM 16 бит → моно. */
    private static short[] readWav(File f, int[] rateOut) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] all = new byte[(int) raf.length()];
            raf.readFully(all);
            ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
            if (all.length < 12 || b.getInt(0) != 0x46464952 /* RIFF */) {
                return null;
            }
            int pos = 12, channels = 1, bits = 16, rate = 0;
            while (pos + 8 <= all.length) {
                int id = b.getInt(pos);
                int size = b.getInt(pos + 4);
                int body = pos + 8;
                if (id == 0x20746d66 /* "fmt " */) {
                    channels = b.getShort(body + 2);
                    rate = b.getInt(body + 4);
                    bits = b.getShort(body + 14);
                } else if (id == 0x61746164 /* "data" */) {
                    if (bits != 16 || rate <= 0) return null;
                    int len = Math.min(size, all.length - body);
                    int n = len / 2 / Math.max(1, channels);
                    short[] out = new short[n];
                    for (int i = 0; i < n; i++) {
                        int sum = 0;
                        for (int c = 0; c < channels; c++) {
                            sum += b.getShort(body + (i * channels + c) * 2);
                        }
                        out[i] = (short) (sum / Math.max(1, channels));
                    }
                    rateOut[0] = rate;
                    return out;
                }
                pos = body + size + (size & 1);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }
}
