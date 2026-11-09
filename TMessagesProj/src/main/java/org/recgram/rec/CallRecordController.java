package org.recgram.rec;

import static org.recgram.RLocale.s;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;
import org.recgram.RecGramConfig;
import org.recgram.rules.RecordingRules;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Когда начинать и заканчивать запись личного звонка. Вызывается из VoIPService и с экрана звонка. */
public final class CallRecordController {

    private static final String CHANNEL = "recgram_recording";
    private static final int NOTIFICATION_ID = 0x52454301;

    private static CallRecorder recorder;
    private static boolean established;
    private static boolean notifiedThisCall;
    private static long startedAt;
    private static int account;
    private static long peerId;
    private static String peerName;
    private static String peerPhone;
    private static boolean outgoing;
    private static boolean peerNotified;
    private static boolean group;
    private static VideoTap videoTap;
    private static MuxerHub videoHub;
    private static String fromName;
    private static String toName;

    private CallRecordController() {
    }

    /** Из VoIPService.dispatchStateChanged. */
    public static void onCallState(VoIPService service, int state) {
        if (service == null) {
            return;
        }
        if (service.groupCall == null && (state == VoIPService.STATE_WAITING || state == VoIPService.STATE_RINGING
                || state == VoIPService.STATE_WAITING_INCOMING)) {
            notifyBeforeAnswer(service, state == VoIPService.STATE_WAITING_INCOMING);
        } else if (state == VoIPService.STATE_ESTABLISHED && !established) {
            established = true;
            if (RecGramConfig.getRecordingMode() == RecGramConfig.RECORD_AUTO && canStart()) {
                startWithNotice(service);
            }
        } else if (state == VoIPService.STATE_ENDED || state == VoIPService.STATE_FAILED
                || state == VoIPService.STATE_HANGING_UP || state == VoIPService.STATE_BUSY) {
            established = false;
            notifiedThisCall = false;
            noticePending = false;
            VoiceNotice.cancel();
            stop();
        }
    }

    /**
     * Входящий звонок звонит (VoIPPreNotificationService): VoIPService ещё не запущен,
     * поэтому предупреждение отправляется отсюда.
     */
    public static void onIncomingRinging(int account, long userId) {
        if (notifiedThisCall || userId == 0 || !RecordingRules.chatNoticeRequired()
                || RecGramConfig.getRecordingMode() != RecGramConfig.RECORD_AUTO
                || !RecordingRules.isRecordingAllowed() || !RecGramConfig.isRecordAudio()) {
            return;
        }
        notifiedThisCall = true;
        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(
                s("🔴 Если ответить, этот звонок будет записан (RecGram)", "🔴 If answered, this call will be recorded (RecGram)"), userId));
    }

    /**
     * Предупреждение до соединения: исходящий — вместе с вызовом, входящий — пока звонит.
     * Только для режима «все звонки»: в ручном режиме неизвестно, будет ли запись.
     */
    private static void notifyBeforeAnswer(VoIPService service, boolean incoming) {
        if (notifiedThisCall || !RecordingRules.chatNoticeRequired()
                || RecGramConfig.getRecordingMode() != RecGramConfig.RECORD_AUTO
                || !RecordingRules.isRecordingAllowed() || !RecGramConfig.isRecordAudio()) {
            return;
        }
        TLRPC.User user = service.getUser();
        if (user == null || user.id == 0) {
            return;
        }
        notifiedThisCall = true;
        String text = incoming
                ? s("🔴 Если ответить, этот звонок будет записан (RecGram)", "🔴 If answered, this call will be recorded (RecGram)")
                : s("🔴 Этот звонок будет записан (RecGram)", "🔴 This call will be recorded (RecGram)");
        SendMessagesHelper.getInstance(service.getAccount()).sendMessage(SendMessagesHelper.SendMessageParams.of(text, user.id));
    }

    /** Можно ли начать запись прямо сейчас (страна, настройки, идёт личный звонок). */
    public static boolean canStart() {
        VoIPService s = VoIPService.getSharedInstance();
        return recorder == null && established && s != null
                && RecordingRules.isRecordingAllowed() && RecGramConfig.isRecordAudio()
                && RecGramConfig.getRecordingMode() != RecGramConfig.RECORD_OFF;
    }

    private static boolean noticePending;

    /** Сначала голосовое предупреждение (если нужно), потом запись. Для личных звонков. */
    public static void startWithNotice(VoIPService service) {
        if (service == null || recorder != null || noticePending) {
            return;
        }
        if (service.groupCall != null || !RecordingRules.voiceNoticeRequired()) {
            start(service);
            return;
        }
        // Запись начинается сразу, чтобы предупреждение попало в файл
        start(service);
        noticePending = true;
        VoiceNotice.play(() -> noticePending = false);
    }

    public static boolean isNoticePlaying() {
        return noticePending;
    }

    public static boolean isRecording() {
        return recorder != null;
    }

    public static long elapsedMs() {
        return recorder != null ? System.currentTimeMillis() - startedAt : 0;
    }

    public static synchronized void start(VoIPService service) {
        if (recorder != null || service == null) {
            return;
        }
        account = service.getAccount();
        TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
        String myName = me != null ? UserObject.getUserName(me) : s("Я", "Me");
        String from, to;
        group = service.groupCall != null;
        if (group) {
            // Групповой звонок или трансляция: «собеседник» — чат
            TLRPC.Chat chat = service.getChat();
            peerId = chat != null ? -chat.id : 0;
            peerName = chat != null ? "👥 " + chat.title : s("👥 Групповой звонок", "👥 Group call");
            peerPhone = "";
            outgoing = true;
            from = myName;
            to = peerName;
        } else {
            TLRPC.User user = service.getUser();
            peerId = user != null ? user.id : 0;
            peerName = user != null ? UserObject.getUserName(user) : "";
            peerPhone = user != null && user.phone != null ? user.phone : "";
            outgoing = service.isOutgoing();
            from = outgoing ? myName : peerName;
            to = outgoing ? peerName : myName;
        }
        fromName = from;
        toName = to;
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        String base = safe(from) + "→" + safe(to) + "_" + stamp;
        boolean video = !group && RecGramConfig.isRecordVideo() && service.videoCall;
        File file = new File(recordingsDir(), base + (video ? ".mp4" : CallRecorder.extension()));
        videoHub = null;
        videoTap = null;
        if (video) {
            try {
                videoHub = new MuxerHub(file, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, 2);
            } catch (Throwable e) {
                FileLog.e(e);
                video = false;
                file = new File(recordingsDir(), base + CallRecorder.extension());
            }
        }
        long t0 = System.nanoTime();
        recorder = CallRecorder.start(file, videoHub);
        if (recorder != null && videoHub != null) {
            videoTap = VideoTap.start(videoHub, t0);
            service.recgramVideoTaps(videoTap.remoteSink, videoTap.localSink, true);
        }
        if (recorder == null) {
            return;
        }
        startedAt = System.currentTimeMillis();
        peerNotified = notifiedThisCall; // предупреждение могло уйти ещё до ответа
        showNotification();
        if (RecordingRules.chatNoticeRequired() && peerId != 0 && !notifiedThisCall) {
            if (group) {
                // В группу пишем, только если там можно отправлять сообщения
                TLRPC.Chat chat = service.getChat();
                if (chat != null && org.telegram.messenger.ChatObject.canSendPlain(chat)) {
                    notifiedThisCall = true;
                    peerNotified = true;
                    SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(
                            s("🔴 Этот голосовой чат записывается (RecGram)", "🔴 This voice chat is being recorded (RecGram)"), peerId));
                }
            } else {
                notifiedThisCall = true;
                peerNotified = true;
                SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(
                        s("🔴 Этот звонок записывается (RecGram)", "🔴 This call is being recorded (RecGram)"), peerId));
            }
        }
    }

    public static synchronized void stop() {
        final CallRecorder r = recorder;
        final VideoTap vt = videoTap;
        final MuxerHub vh = videoHub;
        videoTap = null;
        videoHub = null;
        if (vt != null) {
            VoIPService s = VoIPService.getSharedInstance();
            if (s != null) {
                s.recgramVideoTaps(vt.remoteSink, vt.localSink, false);
            }
        }
        if (r == null) {
            return;
        }
        recorder = null;
        hideNotification();
        final long started = startedAt;
        final long id = peerId;
        final String name = peerName, phone = peerPhone;
        final boolean out = outgoing, notified = peerNotified;
        final String fromN = fromName, toN = toName;
        Utilities.globalQueue.postRunnable(() -> {
            r.stop();
            if (vt != null) {
                vt.stop();
            }
            if (vh != null) {
                vh.awaitReleased(5000);
            }
            String title = (out ? s("Звонок: ", "Call to ") : s("Звонок от ", "Call from ")) + name;
            boolean tagged = false;
            if (!r.isFailed() && r.getFile().getName().endsWith(".opus")) {
                tagged = OggOpusTags.write(r.getFile(), "RecGram " + BuildVars.BUILD_VERSION_STRING,
                        buildTags(r, started, id, fromN, toN, phone, out, notified));
            }
            String published = r.isFailed() ? null : RecordingStore.publish(r.getFile(), title);
            File meta = writeMetadata(r, started, id, name, phone, out, notified, published);
            // Данные звонка всегда копируются в Documents/RecGram (в .opus они ещё и в тегах)
            String metaPublished = meta != null ? RecordingStore.publishJson(meta) : null;
            if (published != null) {
                r.getFile().delete();
            }
            showSaved(published, metaPublished);
        });
    }

    /** Теги .opus с данными звонка (Vorbis comments). */
    private static java.util.LinkedHashMap<String, String> buildTags(CallRecorder r, long started, long id, String from, String to,
                                                                    String phone, boolean out, boolean notified) {
        java.util.LinkedHashMap<String, String> t = new java.util.LinkedHashMap<>();
        Date d = new Date(started);
        String iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(d);
        t.put("TITLE", from + " → " + to + ", " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(d));
        t.put("ARTIST", from);
        t.put("ALBUM", s("Звонки RecGram", "RecGram calls"));
        t.put("DATE", iso);
        t.put("COMMENT", s("Запись звонка Telegram, сделанная RecGram", "Telegram call recorded with RecGram"));
        t.put("RECGRAM_FROM", from);
        t.put("RECGRAM_TO", to);
        t.put("RECGRAM_DIRECTION", group ? "group" : out ? "outgoing" : "incoming");
        t.put("RECGRAM_PEER_ID", String.valueOf(id));
        t.put("RECGRAM_PEER_PHONE", phone);
        t.put("RECGRAM_STARTED", iso);
        t.put("RECGRAM_DURATION_MS", String.valueOf(r.durationMs()));
        RecordingRules.Country c = RecordingRules.selected();
        t.put("RECGRAM_COUNTRY", c != null ? c.id : "");
        t.put("RECGRAM_COUNTRY_RULE", c != null ? c.self : "");
        t.put("RECGRAM_PEER_NOTIFIED", String.valueOf(notified));
        t.put("RECGRAM_AUTO_LEVEL", String.valueOf(RecGramConfig.isAutoLevel()));
        return t;
    }

    /** Имя для файла: без символов, запрещённых в Android и Windows. */
    private static String safe(String name) {
        String s = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        if (s.length() > 40) {
            s = s.substring(0, 40).trim();
        }
        return s.isEmpty() ? "unknown" : s;
    }

    public static File recordingsDir() {
        Context ctx = ApplicationLoader.applicationContext;
        File dir = new File(ctx.getFilesDir(), "recordings");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static File writeMetadata(CallRecorder r, long started, long id, String name, String phone, boolean out, boolean notified, String published) {
        try {
            File f = r.getFile();
            JSONObject o = new JSONObject();
            o.put("file", f.getName());
            o.put("started", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date(started)));
            o.put("duration_ms", r.durationMs());
            o.put("peer_id", id);
            o.put("peer_name", name);
            o.put("peer_phone", phone);
            o.put("direction", group ? "group" : out ? "outgoing" : "incoming");
            RecordingRules.Country c = RecordingRules.selected();
            o.put("country", c != null ? c.id : "");
            o.put("country_rule", c != null ? c.self : "");
            o.put("peer_notified", notified);
            o.put("auto_level", RecGramConfig.isAutoLevel());
            o.put("channels", "mono: me + peer");
            String kb = (RecGramConfig.getAudioBitrate() / 1000) + "k";
            o.put("codec", f.getName().endsWith(".mp4") ? VideoTap.lastCodec + " + aac mono " + kb : CallRecorder.useOpus() ? "opus/ogg mono " + kb : "aac/mp4 mono " + kb);
            o.put("failed", r.isFailed());
            o.put("sha256", f.exists() ? sha256(f) : "");
            o.put("saved_to", published != null ? published : f.getAbsolutePath());
            o.put("app", "RecGram " + BuildVars.BUILD_VERSION_STRING);
            String n = f.getName();
            File meta = new File(f.getParentFile(), n.substring(0, n.lastIndexOf('.')) + ".json");
            try (FileOutputStream os = new FileOutputStream(meta)) {
                os.write(o.toString(1).getBytes(StandardCharsets.UTF_8));
            }
            return meta;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    private static void showNotification() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, s("Запись звонков", "Call recording"), NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(s("Идёт запись звонка", "Call is being recorded"))
                    .setContentText(peerName)
                    .setOngoing(true)
                    .setUsesChronometer(true)
                    .setWhen(startedAt)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void showSaved(String path, String metaPath) {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(path != null ? s("Запись звонка сохранена", "Call recording saved") : s("Не удалось сохранить запись", "Could not save the recording"))
                    .setContentText(path != null ? path : "")
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(path == null ? "" :
                            path + (metaPath == null ? "" : metaPath.startsWith(RecordingStore.relativeDir()) ? "\n" + s("Данные звонка: рядом, файл .json", "Call data: next to it, .json file") : "\n" + s("Данные звонка: ", "Call data: ") + metaPath)))
                    .setAutoCancel(true)
                    .setContentIntent(openRecordingsIntent(ctx))
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            nm.notify(NOTIFICATION_ID + 1, b.build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Открыть RecGram на экране «Записи звонков». */
    private static android.app.PendingIntent openRecordingsIntent(Context ctx) {
        android.content.Intent i = new android.content.Intent(ctx, org.telegram.ui.LaunchActivity.class);
        i.setAction("org.recgram.OPEN_RECORDINGS");
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return android.app.PendingIntent.getActivity(ctx, 0x52454302, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
    }

    private static void hideNotification() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                NotificationManager nm = (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.cancel(NOTIFICATION_ID);
            } catch (Throwable ignore) {
            }
        });
    }
}
