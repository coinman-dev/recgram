/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.telegram.ui.LaunchActivity;

public class NotificationsService extends Service {

    // RecGram: у RecGram нет push от Firebase (сервер Telegram шлёт их только официальному приложению),
    // поэтому сообщения приходят по своему фоновому соединению. Служба переднего плана с тихим
    // уведомлением — иначе Android 8+ закрывает её через минуту после ухода приложения в фон.
    private static final String CHANNEL = "recgram_background";
    private static final int NOTIFICATION_ID = 7301;

    @Override
    public void onCreate() {
        super.onCreate();
        startInForeground();
        ApplicationLoader.postInitApplication();
    }

    private void startInForeground() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL,
                        org.recgram.RLocale.s("Фоновое соединение", "Background connection"), NotificationManager.IMPORTANCE_MIN);
                ch.setDescription(org.recgram.RLocale.s(
                        "Держит связь с Telegram, чтобы сообщения приходили, когда RecGram закрыт. Уведомление можно скрыть, отключив эту категорию.",
                        "Keeps the connection to Telegram so messages arrive while RecGram is closed. You can hide it by turning this category off."));
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, LaunchActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(org.recgram.RLocale.s("RecGram на связи", "RecGram is connected"))
                    .setContentText(org.recgram.RLocale.s("Получает сообщения, пока приложение закрыто", "Receives messages while the app is closed"))
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", true)) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }
}
