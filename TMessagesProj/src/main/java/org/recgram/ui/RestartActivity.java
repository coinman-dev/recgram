package org.recgram.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

/**
 * Перезапуск RecGram. Живёт в отдельном процессе (":restart"): основной процесс запускает этот экран,
 * пока сам ещё на экране (Android 10+ не даёт открывать окна из фона), и завершается;
 * этот экран дожидается конца старого процесса и открывает RecGram заново.
 */
public class RestartActivity extends Activity {

    public static final String EXTRA_PID = "recgram_restart_pid";

    /** Текущий процесс — служебный ":restart" (по /proc/self/cmdline, работает на всех версиях Android). */
    public static boolean isRestartProcess() {
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            int len = 0;
            while (len < n && buf[len] != 0) len++;
            return new String(buf, 0, len, "UTF-8").endsWith(":restart");
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pid = getIntent().getIntExtra(EXTRA_PID, 0);
        if (pid != 0 && pid != Process.myPid()) {
            Process.killProcess(pid);
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(launch);
        }
        finish();
        Runtime.getRuntime().exit(0);
    }
}
