package org.recgram;

import static org.recgram.RLocale.s;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

import java.util.List;

/**
 * Уведомления ботов (Walt/Кошелёк и т. п.) — отдельной стопкой от обычных сообщений. Telegram кладёт все
 * чаты в одну группу, и Samsung сворачивает её под последним чатом: сообщение от человека прячется под ботом.
 * Здесь у ботов своя группа со своей тихой сводкой; звук по-прежнему даёт основная сводка.
 */
public final class BotNotifications {

    private BotNotifications() {
    }

    public static boolean isBot(int account, long dialogId) {
        if (dialogId <= 0) return false;
        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        return user != null && user.bot;
    }

    public static String group(String mainGroup) {
        return mainGroup + "_bots";
    }

    private static int summaryId(int account) {
        return 0x52470000 + account; // «RG»: не пересекается с id чатов и основной сводки (account + 1)
    }

    /** Тихая сводка группы ботов; names — названия ботов в стопке. */
    public static void showSummary(int account, String mainGroup, String channelId, List<String> names) {
        Context ctx = ApplicationLoader.applicationContext;
        Intent intent = new Intent(ctx, LaunchActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("currentAccount", account);
        PendingIntent open = PendingIntent.getActivity(ctx, summaryId(account), intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(R.drawable.notification)
                .setColor(0xff11acfa)
                .setContentTitle(s("Боты", "Bots"))
                .setContentText(android.text.TextUtils.join(", ", names))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setGroup(group(mainGroup))
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE);
        try {
            NotificationManagerCompat.from(ctx).notify(summaryId(account), b.build());
        } catch (SecurityException ignore) {
        }
    }

    public static void cancelSummary(int account) {
        NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(summaryId(account));
    }
}
