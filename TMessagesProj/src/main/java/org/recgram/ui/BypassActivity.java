package org.recgram.ui;

import static org.recgram.RLocale.s;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Toast;

import org.recgram.bypass.Bypass;
import org.recgram.bypass.Vless;
import org.recgram.bypass.VlessConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.ProxyListActivity;

import java.util.ArrayList;

/**
 * Настройки → RecGram → Обход блокировок. Тот же экран — шаг первого запуска «Подключение к Telegram»
 * (после ввода API-ключа, до входа): пока нет связи с Telegram, дальше не пустит, если страна блокирует Telegram.
 */
public class BypassActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_CONTINUE = 30;
    private final boolean setup;

    public BypassActivity() {
        this(false);
    }

    public BypassActivity(boolean setup) {
        this.setup = setup;
    }

    private static final int ID_PROXY_LIST = 1, ID_IMPORT = 2, ID_SUBSCRIPTION = 3, ID_SUB_UPDATE = 4, ID_BEST = 5;
    private static final int ID_MODE_BASE = 10;
    private static final int ID_DIRECT = 20, ID_AUTO = 21;
    private static final int ID_VLESS_FILE = 40, ID_VLESS_CHECK = 41, ID_VLESS_OFF = 42, ID_VLESS_LOG = 43, ID_VLESS_BASE = 100;
    private static final int REQ_VLESS_FILE = 7301;

    private boolean vlessChecking;
    private boolean vlessStarting;
    private String vlessStatus;
    private ArrayList<VlessConfig> vlessList = new ArrayList<>();

    private boolean busy;          // идёт автоподбор
    private boolean checking;      // идёт «проверить все»
    private String status;         // итог действий с прокси (под блоком «Прокси»)
    private String checkProgress;  // ход «проверить все» — строкой на всю ширину под кнопкой
    private String autoProgress;   // ход автоподбора — строкой на всю ширину под кнопкой
    private final StringBuilder autoLog = new StringBuilder(); // итоги по режимам — под кнопкой автоподбора

    @Override
    protected CharSequence getTitle() {
        return setup ? s("Подключение к Telegram", "Connecting to Telegram") : s("Обход блокировок", "Bypass blocking");
    }

    @Override
    public android.view.View createView(android.content.Context context) {
        android.view.View v = super.createView(context);
        if (setup && actionBar != null) {
            actionBar.setBackButtonDrawable(null); // шаг первого запуска: назад некуда
        }
        return v;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        return !setup && super.onBackPressed(invoked);
    }

    private int connectionState() {
        return org.telegram.tgnet.ConnectionsManager.getInstance(currentAccount).getConnectionState();
    }

    private boolean connected() {
        int st = connectionState();
        return st == org.telegram.tgnet.ConnectionsManager.ConnectionStateConnected
                || st == org.telegram.tgnet.ConnectionsManager.ConnectionStateUpdating;
    }

    private String connectionText() {
        switch (connectionState()) {
            case org.telegram.tgnet.ConnectionsManager.ConnectionStateConnected:
            case org.telegram.tgnet.ConnectionsManager.ConnectionStateUpdating:
                return "✅ " + (SharedConfig.isProxyEnabled() ? s("Связь есть, через прокси", "Connected via proxy") : s("Связь есть", "Connected"));
            case org.telegram.tgnet.ConnectionsManager.ConnectionStateWaitingForNetwork:
                return "📵 " + s("Нет сети", "No network");
            case org.telegram.tgnet.ConnectionsManager.ConnectionStateConnectingToProxy:
                return "⏳ " + s("Подключение к прокси…", "Connecting to proxy…");
            default:
                return "⏳ " + s("Подключение к Telegram…", "Connecting to Telegram…");
        }
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.didUpdateConnectionState);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.didUpdateConnectionState);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        update();
    }

    @Override
    public void onResume() {
        super.onResume();
        update();
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private static String modeTitle(int mode) {
        switch (mode) {
            case Bypass.DPI_SPLIT: return s("Разрез пакета", "Split packet");
            case Bypass.DPI_DISORDER: return s("Разрез + обратный порядок", "Split + disorder");
            case Bypass.DPI_OOB: return s("Разрез + срочный байт", "Split + OOB byte");
            default: return s("Выключена", "Off");
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        SharedConfig.loadProxyList();
        VlessConfig activeVless = Vless.isActive() ? Vless.find(Vless.getSelectedId()) : null;
        String current = activeVless != null ? "VLESS: " + activeVless.name
                : SharedConfig.isProxyEnabled() && SharedConfig.currentProxy != null
                ? SharedConfig.currentProxy.settings.getAddress() + ":" + SharedConfig.currentProxy.settings.getPort()
                : s("выключен", "off");

        // статус связи — сверху, на обоих экранах
        items.add(UItem.asHeader(s("Связь с Telegram", "Connection to Telegram")));
        items.add(UItem.asButton(0, connectionText()));
        String iso = Bypass.countryIso();
        boolean blocking = Bypass.isBlockingCountry(iso);
        if (setup) {
            items.add(UItem.asShadow((blocking
                    ? s("В вашей стране (" + iso + ") Telegram блокируют: без обхода войти не получится. Добавьте прокси ниже, при необходимости включите защиту от DPI и дождитесь «Связь есть».",
                        "Telegram is blocked in your country (" + iso + "): you cannot sign in without a bypass. Add proxies below, enable DPI protection if needed and wait for \"Connected\".")
                    : s("Если Telegram у вас не блокируют, связь появится сама. Иначе добавьте прокси и включите защиту от DPI ниже.",
                        "If Telegram is not blocked where you are, the connection appears by itself. Otherwise add proxies and enable DPI protection below."))
                    + "\n\n" + s("Настройки можно поменять позже: Настройки → RecGram → Обход блокировок.",
                        "You can change this later: Settings → RecGram → Bypass blocking.")));
        } else {
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asHeader(s("Прокси", "Proxy")));
        items.add(UItem.asButton(ID_PROXY_LIST, s("Список прокси", "Proxy list"), current + " · " + SharedConfig.proxyList.size()));
        items.add(UItem.asButton(ID_IMPORT, s("Импорт из буфера обмена", "Import from clipboard")));
        String sub = Bypass.getSubscriptionUrl();
        items.add(UItem.asButton(ID_SUBSCRIPTION, s("Подписка", "Subscription"), sub.isEmpty() ? s("не задана", "not set") : shortUrl(sub)));
        if (!sub.isEmpty()) {
            items.add(UItem.asButton(ID_SUB_UPDATE, s("Обновить подписку", "Update subscription")));
        }
        items.add(UItem.asButton(ID_BEST, checking ? "⏳ " + s("Проверяю прокси…", "Checking proxies…")
                : s("Проверить и включить лучший", "Check and use the best")).accent());
        if (checking && checkProgress != null) {
            items.add(UItem.asShadow("⏳ " + checkProgress));
        }
        items.add(UItem.asShadow((status != null ? status + "\n\n" : "") + s(
                "Блокировку Telegram по адресам серверов обходит только прокси. Лучше всего работает MTProto-прокси с маскировкой под HTTPS (ссылка с секретом, начинающимся на ee). Импорт понимает ссылки tg://proxy, t.me/proxy, tg://socks, t.me/socks и vless:// — сразу много из скопированного текста. Подписка — ссылка на страницу или файл со списком таких ссылок.",
                "Only a proxy gets around blocking of Telegram server addresses. An MTProto proxy disguised as HTTPS works best (a link with a secret starting with ee). Import understands tg://proxy, t.me/proxy, tg://socks, t.me/socks and vless:// links — many at once from copied text. A subscription is a link to a page or file with such links.")));

        fillVless(items);

        int mode = Bypass.getDpiMode();
        items.add(UItem.asHeader(s("Защита от DPI", "DPI protection")));
        for (int m = Bypass.DPI_OFF; m <= Bypass.DPI_OOB; m++) {
            items.add(UItem.asRadio(ID_MODE_BASE + m, modeTitle(m)).setChecked(mode == m).setEnabled(!busy));
        }
        items.add(UItem.asCheck(ID_DIRECT, s("Также без маскировки под HTTPS", "Also without HTTPS disguise"))
                .setChecked(Bypass.isDpiDirect()).setEnabled(!busy && mode != Bypass.DPI_OFF));
        items.add(UItem.asButton(ID_AUTO, busy ? "⏳ " + s("Подбираю режим…", "Selecting a mode…")
                : s("Подобрать автоматически", "Select automatically")).accent());
        if (autoLog.length() > 0 || busy && autoProgress != null) {
            items.add(UItem.asShadow(((busy && autoProgress != null ? "⏳ " + autoProgress + "\n" : "") + autoLog).trim()));
        }
        items.add(UItem.asShadow(s(
                "Оборудование провайдера (DPI) узнаёт прокси по имени сайта в первом пакете. RecGram режет этот пакет так, чтобы DPI не смог его прочитать, а сервер получил всё без изменений.\n"
                        + "• «Разрез + обратный порядок» — рекомендуется: первая часть приходит позже второй, и сервер получает пакет целиком.\n"
                        + "• «Разрез пакета» — некоторые прокси-серверы (mtg, mtprotoproxy) могут отвергнуть пакет, пришедший двумя частями.\n"
                        + "• «Срочный байт» — только для прокси с маскировкой под HTTPS; для остальных соединений вместо него обычный разрез.\n"
                        + "Флажок «также без маскировки» режет и первый пакет прямого подключения и обычного MTProto-прокси. Автоподбор проверяет все прокси в каждом режиме и оставляет лучший. От блокировки по адресам и для звонков это не помогает — нужен прокси.",
                "Provider equipment (DPI) recognises a proxy by the site name in the first packet. RecGram splits that packet so DPI cannot read it while the server gets it unchanged.\n"
                        + "• \"Split + disorder\" is recommended: the first part arrives after the second and the server gets the packet whole.\n"
                        + "• \"Split packet\": some proxy servers (mtg, mtprotoproxy) may reject a packet that arrives in two parts.\n"
                        + "• \"OOB byte\": only for proxies disguised as HTTPS; other connections get a plain split instead.\n"
                        + "\"Also without disguise\" also splits the first packet of direct connections and plain MTProto proxies. Automatic selection checks all proxies in each mode and keeps the best. This does not help against address blocking or for calls — a proxy is needed.")));
        if (setup) {
            items.add(UItem.asShadow(null));
            boolean ok = connected();
            items.add(UItem.asButton(ID_CONTINUE, ok ? s("Продолжить", "Continue")
                    : blocking ? s("Продолжить — нет связи с Telegram", "Continue — no connection to Telegram")
                    : s("Продолжить без проверки связи", "Continue without a connection check")).accent());
            items.add(UItem.asShadow(s("После «Продолжить» RecGram перезапустится с этими настройками и откроет вход в Telegram.",
                    "After \"Continue\" RecGram restarts with these settings and opens the Telegram sign-in.")));
        }
    }

    private void fillVless(ArrayList<UItem> items) {
        items.add(UItem.asHeader(s("VLESS-сервер (Xray)", "VLESS server (Xray)")));
        if (!Vless.isAvailable()) {
            items.add(UItem.asShadow(s("Встроенный Xray не входит в эту сборку RecGram.", "The built-in Xray is not part of this RecGram build.")));
            return;
        }
        vlessList = Vless.list();
        String sel = Vless.getSelectedId();
        boolean enabled = Vless.isEnabled();
        String err = Vless.getLastError();
        String state;
        if (vlessStarting || enabled && !Vless.isReady() && err == null) {
            state = "⏳ " + s("Запускаю Xray…", "Starting Xray…");
        } else if (Vless.isActive() && Vless.isReady()) {
            state = "✅ " + s("Telegram идёт через VLESS", "Telegram goes through VLESS");
        } else if (enabled && err != null) {
            state = "⚠️ " + err;
        } else {
            state = s("Выключен", "Off");
        }
        items.add(UItem.asButton(ID_VLESS_LOG, state, s("журнал", "log")));
        for (int i = 0; i < vlessList.size(); i++) {
            VlessConfig c = vlessList.get(i);
            boolean on = enabled && c.id.equals(sel);
            // пинг первым: длинное значение справа обрезается
            String ping = c.ping > 0 ? c.ping + s(" мс · ", " ms · ") : c.ping == 0 ? s("нет связи · ", "no link · ") : "";
            items.add(UItem.asButton(ID_VLESS_BASE + i, (on ? "● " : "") + c.name, ping + c.summary().replaceFirst("^vless · ", "")));
        }
        items.add(UItem.asButton(ID_VLESS_FILE, s("Импорт из файла", "Import from file")));
        if (!vlessList.isEmpty()) {
            items.add(UItem.asButton(ID_VLESS_CHECK, vlessChecking ? "⏳ " + s("Проверяю серверы…", "Checking servers…")
                    : s("Проверить серверы", "Check servers")).accent());
        }
        if (enabled) {
            items.add(UItem.asButton(ID_VLESS_OFF, s("Выключить VLESS", "Turn VLESS off")).red());
        }
        items.add(UItem.asShadow((vlessStatus != null ? vlessStatus + "\n\n" : "") + s(
                "Свой зарубежный сервер VLESS (Reality, xhttp, ws, gRPC…) — самый надёжный обход, если Telegram блокируют по адресам. RecGram запускает Xray внутри себя: через сервер идёт только трафик RecGram, системный VPN не нужен.\n"
                        + "Добавить сервер: скопируйте ссылку vless:// (в v2RayTun/v2rayNG — «Поделиться») и нажмите «Импорт из буфера обмена» выше, либо выберите файл — JSON-конфиг Xray или shared_preferences.json из v2RayTun. Подписка выше тоже понимает ссылки vless://.\n"
                        + "Нажмите на сервер, чтобы включить, переименовать или удалить. Звонки Telegram через прокси не идут.",
                "Your own VLESS server abroad (Reality, xhttp, ws, gRPC…) is the most reliable bypass when Telegram is blocked by address. RecGram runs Xray inside itself: only RecGram traffic goes through the server, no system VPN is needed.\n"
                        + "To add a server, copy a vless:// link (\"Share\" in v2RayTun/v2rayNG) and tap \"Import from clipboard\" above, or pick a file: an Xray JSON config or shared_preferences.json from v2RayTun. The subscription above also understands vless:// links.\n"
                        + "Tap a server to turn it on, rename or delete it. Telegram calls do not go through proxies.")));
    }

    private void vlessMenu(Context ctx, VlessConfig c) {
        boolean on = Vless.isEnabled() && c.id.equals(Vless.getSelectedId());
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(c.name);
        CharSequence[] opts = {
                on ? s("Выключить", "Turn off") : s("Включить", "Turn on"),
                s("Переименовать", "Rename"),
                s("Удалить", "Delete")
        };
        b.setItems(opts, (d, which) -> {
            if (which == 0) {
                if (on) {
                    Vless.disable();
                    vlessStatus = null;
                    update();
                } else {
                    vlessStarting = true;
                    vlessStatus = null;
                    update();
                    Vless.enable(c, ok -> {
                        vlessStarting = false;
                        vlessStatus = ok ? s("Включён «", "Using \"") + c.name + s("». Дождитесь «Связь есть» вверху.", "\". Wait for \"Connected\" at the top.")
                                : s("Не удалось запустить Xray: ", "Could not start Xray: ") + Vless.getLastError();
                        update();
                    });
                }
            } else if (which == 1) {
                showRenameDialog(ctx, c);
            } else {
                AlertDialog.Builder q = new AlertDialog.Builder(ctx);
                q.setTitle(s("Удалить сервер?", "Delete server?"));
                q.setMessage(c.name);
                q.setPositiveButton(s("Удалить", "Delete"), (d2, w2) -> {
                    Vless.delete(c);
                    update();
                });
                q.setNegativeButton(s("Отмена", "Cancel"), null);
                showDialog(q.create());
            }
        });
        showDialog(b.create());
    }

    private void showRenameDialog(Context ctx, VlessConfig c) {
        EditTextBoldCursor field = new EditTextBoldCursor(ctx);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setSingleLine(true);
        field.setBackground(null);
        field.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setText(c.name);
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(s("Имя сервера", "Server name"));
        android.widget.LinearLayout box = new android.widget.LinearLayout(ctx);
        box.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 24, 8, 24, 8));
        b.setView(box);
        b.setPositiveButton(s("Сохранить", "Save"), (d, w) -> {
            String name = field.getText().toString().trim();
            if (!name.isEmpty()) {
                Vless.rename(c, name);
                update();
            }
        });
        b.setNegativeButton(s("Отмена", "Cancel"), null);
        showDialog(b.create());
    }

    private void showVlessLog(Context ctx) {
        String log = Vless.logText();
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(s("Журнал Xray", "Xray log"));
        b.setMessage(log.isEmpty() ? s("Пусто.", "Empty.") : log);
        b.setPositiveButton("OK", null);
        showDialog(b.create());
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode != REQ_VLESS_FILE || resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Context ctx = getParentActivity();
        if (ctx == null) return;
        try (java.io.InputStream in = ctx.getContentResolver().openInputStream(data.getData())) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while (in != null && (n = in.read(buf)) > 0 && out.size() < 4 * 1024 * 1024) {
                out.write(buf, 0, n);
            }
            int added = Bypass.importLinks(VlessConfig.maybeBase64(out.toString("UTF-8")));
            vlessStatus = added > 0 ? s("Добавлено из файла: ", "Added from file: ") + added
                    : s("В файле нет новых серверов.", "No new servers in the file.");
        } catch (Throwable e) {
            vlessStatus = s("Не удалось прочитать файл.", "Could not read the file.");
        }
        update();
    }

    private static String shortUrl(String url) {
        String u = url.replaceFirst("^https?://", "");
        return u.length() > 32 ? u.substring(0, 31) + "…" : u;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        Context ctx = getParentActivity();
        if (ctx == null) {
            return;
        }
        if (id == ID_CONTINUE) {
            if (!connected() && Bypass.isBlockingCountry(Bypass.countryIso())) {
                Toast.makeText(ctx, s("Сначала нужна связь с Telegram: добавьте рабочий прокси.", "A connection to Telegram is needed first: add a working proxy."), Toast.LENGTH_LONG).show();
                return;
            }
            Bypass.setSetupDone();
            org.recgram.ApiKeys.restartApp();
            return;
        }
        if (id >= ID_VLESS_BASE && id < ID_VLESS_BASE + vlessList.size()) {
            if (!vlessStarting) vlessMenu(ctx, vlessList.get(id - ID_VLESS_BASE));
            return;
        }
        if (id == ID_VLESS_LOG) {
            showVlessLog(ctx);
            return;
        }
        if (id == ID_VLESS_OFF) {
            Vless.disable();
            vlessStatus = null;
            update();
            return;
        }
        if (id == ID_VLESS_FILE) {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(i, REQ_VLESS_FILE);
            } catch (Throwable e) {
                Toast.makeText(ctx, s("Нет приложения для выбора файла", "No file picker app"), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (id == ID_VLESS_CHECK) {
            if (vlessChecking) return;
            vlessChecking = true;
            vlessStatus = null;
            update();
            Vless.checkAll(res -> {
                vlessChecking = false;
                int ok = 0;
                for (VlessConfig c : res) if (c.ping > 0) ok++;
                vlessStatus = ok == 0 ? s("Ни один сервер не дал связи с Telegram. Подробности — в журнале (строка состояния выше).", "No server reached Telegram. See the log (status line above).")
                        : s("Связь с Telegram есть через ", "Telegram reachable via ") + ok + s(" из ", " of ") + res.size() + s(". Быстрейший — «", ". Fastest: \"") + res.get(0).name + s("».", "\".");
                update();
            });
            return;
        }
        if (id == ID_PROXY_LIST) {
            presentFragment(new ProxyListActivity());
        } else if (id == ID_IMPORT) {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm != null ? cm.getPrimaryClip() : null;
            CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(ctx) : null;
            int n = Bypass.importLinks(text == null ? null : text.toString());
            status = n > 0 ? s("Добавлено прокси и серверов: ", "Proxies and servers added: ") + n
                    : s("В буфере обмена нет новых ссылок на прокси или серверы VLESS.", "No new proxy or VLESS links in the clipboard.");
            update();
        } else if (id == ID_SUBSCRIPTION) {
            showSubscriptionDialog(ctx);
        } else if (id == ID_SUB_UPDATE) {
            status = s("Загружаю подписку…", "Loading subscription…");
            update();
            Bypass.updateSubscription(n -> {
                status = n < 0 ? s("Не удалось загрузить подписку.", "Could not load the subscription.")
                        : s("Подписка обновлена, новых прокси: ", "Subscription updated, new proxies: ") + n;
                update();
            });
        } else if (id == ID_BEST) {
            if (SharedConfig.proxyList.isEmpty()) {
                status = s("Список прокси пуст.", "The proxy list is empty.");
                update();
                return;
            }
            if (checking || busy) return;
            checking = true;
            checkProgress = null;
            status = null;
            update();
            Bypass.checkAll((done, total, okCount) -> {
                checkProgress = s("Проверено ", "Checked ") + done + s(" из ", " of ") + total + s(" прокси, ответили ", " proxies, responded ") + okCount;
                update();
            }, ok -> {
                checking = false;
                if (ok.isEmpty()) {
                    status = s("Ни один прокси не ответил.", "No proxy responded.");
                } else {
                    Bypass.enable(ok.get(0));
                    status = s("Включён ", "Using ") + ok.get(0).settings.getAddress() + " — " + ok.get(0).ping + s(" мс. Рабочих: ", " ms. Working: ") + ok.size();
                }
                update();
            });
        } else if (id >= ID_MODE_BASE && id <= ID_MODE_BASE + Bypass.DPI_OOB) {
            if (busy) return;
            Bypass.setDpi(id - ID_MODE_BASE, Bypass.isDpiDirect());
            update();
        } else if (id == ID_DIRECT) {
            if (busy) return;
            Bypass.setDpi(Bypass.getDpiMode(), !Bypass.isDpiDirect());
            update();
        } else if (id == ID_AUTO) {
            if (busy || checking) return;
            autoLog.setLength(0);
            if (SharedConfig.proxyList.isEmpty() || !SharedConfig.isProxyEnabled()) {
                // прокси нет или он выключен — подбираем режим для прямого подключения по факту связи
                busy = true;
                autoProgress = null;
                autoLog.append(s("Прокси не используется — проверяю прямое подключение к Telegram в 3 режимах, до 40 секунд на режим.\n",
                        "No proxy in use — checking the direct connection to Telegram in 3 modes, up to 40 seconds per mode.\n"));
                update();
                Bypass.autoSelectDirect(new Bypass.AutoProgress() {
                    @Override
                    public void onStep(int mode, int step, int steps, int sec, int total, int ignored) {
                        autoProgress = s("Сейчас: режим ", "Now: mode ") + step + s(" из ", " of ") + steps + " — «" + modeTitle(mode) + "», "
                                + s("жду связь: ", "waiting for connection: ") + sec + s(" из ", " of ") + total + s(" с", " s");
                        update();
                    }

                    @Override
                    public void onModeDone(int mode, int ok, long ms) {
                        autoLog.append("• ").append(modeTitle(mode)).append(": ")
                                .append(ok > 0 ? s("связь есть через ", "connected after ") + (ms / 1000) + s(" с", " s")
                                        : s("связи нет", "no connection"))
                                .append('\n');
                        update();
                    }
                }, r -> {
                    busy = false;
                    autoProgress = null;
                    autoLog.append(r == null
                            ? s("Итог: напрямую Telegram недоступен ни в одном режиме — нужен прокси. Добавьте его выше (импорт или подписка).",
                                "Result: Telegram is unreachable directly in every mode — a proxy is needed. Add one above (import or subscription).")
                            : s("Итог: выбран режим «", "Result: selected mode \"") + modeTitle((int) r[0]) + s("».", "\"."));
                    update();
                });
                return;
            }
            busy = true;
            autoProgress = null;
            autoLog.append(s("Проверяю все прокси в каждом из 4 режимов, до 15 секунд на режим.\n",
                    "Checking all proxies in each of 4 modes, up to 15 seconds per mode.\n"));
            update();
            Bypass.autoSelect(Bypass.isDpiDirect(), new Bypass.AutoProgress() {
                @Override
                public void onStep(int mode, int step, int steps, int done, int total, int okCount) {
                    autoProgress = s("Сейчас: режим ", "Now: mode ") + step + s(" из ", " of ") + steps + " — «" + modeTitle(mode) + "», "
                            + s("проверено ", "checked ") + done + s(" из ", " of ") + total + s(" прокси, ответили ", " proxies, responded ") + okCount;
                    update();
                }

                @Override
                public void onModeDone(int mode, int okCount, long bestPing) {
                    autoLog.append("• ").append(modeTitle(mode)).append(": ")
                            .append(okCount == 0 ? s("ни один прокси не ответил", "no proxy responded")
                                    : s("работают ", "working ") + okCount + s(", лучший пинг ", ", best ping ") + bestPing + s(" мс", " ms"))
                            .append('\n');
                    update();
                }
            }, r -> {
                busy = false;
                autoProgress = null;
                autoLog.append(r == null ? s("Итог: ни в одном режиме прокси не ответили. Режим не изменён.", "Result: no proxy responded in any mode. The mode was not changed.")
                        : s("Итог: выбран режим «", "Result: selected mode \"") + modeTitle((int) r[0]) + s("».", "\"."));
                update();
            });
        }
    }

    private void showSubscriptionDialog(Context ctx) {
        EditTextBoldCursor field = new EditTextBoldCursor(ctx);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setHint("https://…");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setBackground(null);
        field.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setText(Bypass.getSubscriptionUrl());
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(s("Подписка на прокси", "Proxy subscription"));
        b.setMessage(s("Ссылка на страницу или файл со списком ссылок на прокси.", "A link to a page or file with proxy links."));
        android.widget.LinearLayout box = new android.widget.LinearLayout(ctx);
        box.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 24, 8, 24, 8));
        b.setView(box);
        b.setPositiveButton(s("Сохранить", "Save"), (d, w) -> {
            String url = field.getText().toString().trim();
            if (!url.isEmpty() && !url.matches("(?i)https?://.+")) {
                Toast.makeText(ctx, s("Нужна ссылка http(s)://", "An http(s):// link is needed"), Toast.LENGTH_SHORT).show();
                return;
            }
            Bypass.setSubscriptionUrl(url);
            update();
            if (!url.isEmpty()) {
                onClick(UItem.asButton(ID_SUB_UPDATE, ""), null, 0, 0, 0);
            }
        });
        b.setNegativeButton(s("Отмена", "Cancel"), null);
        showDialog(b.create());
        AndroidUtilities.runOnUIThread(() -> {
            field.requestFocus();
            AndroidUtilities.showKeyboard(field);
        }, 300);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
