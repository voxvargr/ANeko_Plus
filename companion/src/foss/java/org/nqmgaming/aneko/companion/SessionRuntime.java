package org.nqmgaming.aneko.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import java.io.File;

/** The FOSS flavor intentionally has no Google classes or wearable runtime dependency. */
final class SessionRuntime {
    private final SharedPreferences preferences;
    private final CompanionSession.Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started;
    private boolean closed;
    private boolean interactive;
    private boolean visible;
    SessionRuntime(Context context, boolean phone, CompanionSession.Host host) {
        this.host = host;
        preferences = context.getSharedPreferences("companion_sync", Context.MODE_PRIVATE);
        PowerManager power = context.getSystemService(PowerManager.class);
        interactive = power != null && power.isInteractive();
    }
    static boolean isSupported() { return false; }
    void start() { main.post(() -> {
        if (started || closed) return;
        started = true;
        preferences.edit().putString("status", getStatus()).apply();
        updateVisibility();
    }); }
    void close() { main.post(() -> { closed = true; started = false; visible = false; }); }
    void updatePresence(boolean value) { main.post(() -> {
        interactive = value;
        updateVisibility();
    }); }
    void publishSettings(String json, File skinPack) { }
    void setMode(String mode) {
        preferences.edit().putString("mode", CompanionSession.validatedMode(mode)).apply();
    }
    String getStatus() { return "Watch connection is available in the connected build."; }

    private void updateVisibility() {
        if (!started) return;
        if (interactive && host.isReady()) {
            if (!visible) host.arrive();
            visible = true;
        } else {
            host.hide();
            visible = false;
        }
    }
}
