package org.recgram.bypass;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * Включён ли на телефоне системный VPN (v2RayTun, AmneziaWG, WireGuard и т. п.) для трафика RecGram.
 * Android отдаёт приложению сеть VPN как активную, только если трафик этого приложения действительно
 * идёт через VPN: если RecGram исключён из VPN (раздельное туннелирование), здесь будет false.
 * Пока системный VPN работает, второй туннель (встроенные VLESS/AmneziaWG) не запускается.
 */
public final class SystemVpn {

    private SystemVpn() {
    }

    private static boolean watching;
    private static volatile boolean lastState;
    private static final Runnable RECHECK = SystemVpn::recheck;

    public static boolean isActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Следить за включением и выключением VPN; при смене вызывается {@link Vless#onSystemVpnChanged}. */
    public static synchronized void watch() {
        if (watching) return;
        watching = true;
        lastState = isActive();
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkRequest req = new NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            cm.registerNetworkCallback(req, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    schedule();
                }

                @Override
                public void onLost(Network network) {
                    schedule();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    schedule();
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void schedule() {
        // при переключении сети событий бывает много подряд — смотрим, когда всё успокоится
        AndroidUtilities.cancelRunOnUIThread(RECHECK);
        AndroidUtilities.runOnUIThread(RECHECK, 1500);
    }

    private static void recheck() {
        boolean now = isActive();
        if (now == lastState) return;
        lastState = now;
        Vless.onSystemVpnChanged(now);
    }
}
