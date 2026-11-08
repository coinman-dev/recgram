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

    private CallRecordController() {
    }

    /** Из VoIPService.dispatchStateChanged. */
    public static void onCallState(VoIPService service, int state) {
        if (service == null || service.groupCall != null) {
            return; // пока только личные звонки
        }
        if (state == VoIPService.STATE_ESTABLISHED && !established) {
            established = true;
            notifiedThisCall = false;
            if (RecGramConfig.getRecordingMode() == RecGramConfig.RECORD_AUTO && canStart()) {
                start(service);
            }
        } else if (state == VoIPService.STATE_ENDED || state == VoIPService.STATE_FAILED
                || state == VoIPService.STATE_HANGING_UP || state == VoIPService.STATE_BUSY) {
            established = false;
            stop();
        }
    }

    /** Можно ли начать запись прямо сейчас (страна, настройки, идёт личный звонок). */
    public static boolean canStart() {
        VoIPService s = VoIPService.getSharedInstance();
        return recorder == null && established && s != null && s.groupCall == null
                && RecordingRules.isRecordingAllowed() && RecGramConfig.isRecordAudio()
                && RecGramConfig.getRecordingMode() != RecGramConfig.RECORD_OFF;
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
        TLRPC.User user = service.getUser();
        account = service.getAccount();
        peerId = user != null ? user.id : 0;
        peerName = user != null ? UserObject.getUserName(user) : "";
        peerPhone = user != null && user.phone != null ? user.phone : "";
        outgoing = service.isOutgoing();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(recordingsDir(), "call_" + stamp + "_" + peerId + (outgoing ? "_out" : "_in") + ".m4a");
        recorder = CallRecorder.start(file);
        if (recorder == null) {
            return;
        }
        startedAt = System.currentTimeMillis();
        peerNotified = false;
        showNotification();
        if (RecGramConfig.isNotifyPeer() && peerId != 0 && !notifiedThisCall) {
            notifiedThisCall = true;
            peerNotified = true;
            SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(
                    "🔴 Этот звонок записывается · This call is being recorded (RecGram)", peerId));
        }
    }

    public static synchronized void stop() {
        final CallRecorder r = recorder;
        if (r == null) {
            return;
        }
        recorder = null;
        hideNotification();
        final long started = startedAt;
        final long id = peerId;
        final String name = peerName, phone = peerPhone;
        final boolean out = outgoing, notified = peerNotified;
        Utilities.globalQueue.postRunnable(() -> {
            r.stop();
            writeMetadata(r, started, id, name, phone, out, notified);
        });
    }

    public static File recordingsDir() {
        Context ctx = ApplicationLoader.applicationContext;
        File dir = ctx.getExternalFilesDir("recordings");
        if (dir == null) {
            dir = new File(ctx.getFilesDir(), "recordings");
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static void writeMetadata(CallRecorder r, long started, long id, String name, String phone, boolean out, boolean notified) {
        try {
            File f = r.getFile();
            JSONObject o = new JSONObject();
            o.put("file", f.getName());
            o.put("started", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date(started)));
            o.put("duration_ms", r.durationMs());
            o.put("peer_id", id);
            o.put("peer_name", name);
            o.put("peer_phone", phone);
            o.put("direction", out ? "outgoing" : "incoming");
            RecordingRules.Country c = RecordingRules.selected();
            o.put("country", c != null ? c.id : "");
            o.put("country_rule", c != null ? c.self : "");
            o.put("peer_notified", notified);
            o.put("channels", "left=me,right=peer");
            o.put("failed", r.isFailed());
            o.put("sha256", f.exists() ? sha256(f) : "");
            o.put("app", "RecGram " + BuildVars.BUILD_VERSION_STRING);
            File meta = new File(f.getParentFile(), f.getName().replace(".m4a", ".json"));
            try (FileOutputStream os = new FileOutputStream(meta)) {
                os.write(o.toString(1).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable e) {
            FileLog.e(e);
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
