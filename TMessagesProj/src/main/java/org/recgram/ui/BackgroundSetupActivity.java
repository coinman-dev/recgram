package org.recgram.ui;

import static org.recgram.RLocale.s;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.widget.Toast;

import org.recgram.BackgroundConnection;
import org.recgram.PhoneSetup;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Настройки → RecGram → Уведомления в фоне. Шаги {@link PhoneSetup} для этого телефона: что включить
 * и кнопка, открывающая нужный экран. Выполненное Android-шаги отмечаются сами, фирменные — пользователем.
 */
public class BackgroundSetupActivity extends UniversalFragment {

    private static final int ID_SERVICE = 1;
    private static final int ID_OPEN_BASE = 100;   // + индекс шага
    private static final int ID_DONE_BASE = 200;   // + индекс шага (только фирменные)

    private List<PhoneSetup.Step> steps = new ArrayList<>();

    @Override
    protected CharSequence getTitle() {
        return s("Уведомления в фоне", "Background notifications");
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false); // вернулись из настроек телефона — перечитать состояние
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        Context ctx = getContext() != null ? getContext() : ApplicationLoader.applicationContext;
        steps = PhoneSetup.steps();

        items.add(UItem.asCheck(ID_SERVICE, s("Фоновое соединение", "Background connection")).setChecked(BackgroundConnection.isEnabled()));
        items.add(UItem.asShadow(s(
                "Push-уведомления Telegram приходят только в официальное приложение, поэтому RecGram получает сообщения по своему соединению. Чтобы телефон его не обрывал, пройдите шаги ниже. Выключите соединение — и сообщения будут приходить только при открытом RecGram.",
                "Telegram push notifications only reach the official app, so RecGram receives messages over its own connection. Go through the steps below so that the phone does not cut it. Turn the connection off and messages arrive only while RecGram is open.")));

        android.app.ApplicationExitInfo kill = PhoneSetup.lastSystemKill(ctx);
        if (kill != null && Build.VERSION.SDK_INT >= 30) {
            String when = new java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(kill.getTimestamp()));
            items.add(UItem.asShadow("⚠️ " + s("Система закрыла RecGram ", "The system closed RecGram on ") + when
                    + s(" — в это время сообщения могли не приходить. Проверьте шаги ниже; на vivo чаще всего виноваты смахивание из недавних и «Оптимизация во время сна».",
                    " — messages may not have arrived then. Check the steps below; on vivo the usual causes are swiping it off recents and sleep standby optimisation.")));
        }

        String brand = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        items.add(UItem.asHeader(s("Шаги для этого телефона", "Steps for this phone") + (brand.isEmpty() ? "" : " (" + brand + ")")));
        for (int i = 0; i < steps.size(); i++) {
            PhoneSetup.Step st = steps.get(i);
            boolean done = PhoneSetup.isDone(ctx, st);
            boolean auto = PhoneSetup.isAutomatic(ctx, st);
            String mark = done ? "✅ " : auto ? "⚠️ " : "◻️ ";
            items.add(UItem.asButton(ID_OPEN_BASE + i, mark + st.title, s("Открыть", "Open")));
            if (!auto) {
                items.add(UItem.asCheck(ID_DONE_BASE + i, s("Сделано", "Done")).setChecked(done));
            }
            items.add(UItem.asShadow(st.text));
        }
        if (!PhoneSetup.hasVendorSteps()) {
            items.add(UItem.asShadow(s(
                    "У этой марки телефона нет своего диспетчера питания, который нужно настраивать отдельно: достаточно шагов выше.",
                    "This phone brand has no power manager of its own to set up separately: the steps above are enough.")));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == ID_SERVICE) {
            boolean on = !BackgroundConnection.isEnabled();
            MessagesController.getGlobalNotificationsSettings().edit()
                    .putBoolean("pushService", on).putBoolean("pushConnection", on).commit();
            ApplicationLoader.startPushService();
            for (int a = 0; a < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (org.telegram.messenger.UserConfig.getInstance(a).isClientActivated()) {
                    org.telegram.tgnet.ConnectionsManager.getInstance(a).setPushConnectionEnabled(on);
                }
            }
            listView.adapter.update(true);
            return;
        }
        if (id >= ID_OPEN_BASE && id < ID_OPEN_BASE + steps.size()) {
            PhoneSetup.Step st = steps.get(id - ID_OPEN_BASE);
            if (!PhoneSetup.open(getParentActivity(), st)) {
                Toast.makeText(getParentActivity(), s("Не удалось открыть настройки", "Could not open settings"), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (id >= ID_DONE_BASE && id < ID_DONE_BASE + steps.size()) {
            PhoneSetup.Step st = steps.get(id - ID_DONE_BASE);
            PhoneSetup.setManualDone(st, !PhoneSetup.isDone(getParentActivity(), st));
            listView.adapter.update(true);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
