package org.nqmgaming.aneko.companion;

import android.content.Context;
import java.io.File;

/** A connection exists only for the lifetime of an explicitly started overlay session. */
public final class CompanionSession implements AutoCloseable {
    public interface Host {
        /** True only while the user-started overlay and its permission are ready. */
        boolean isReady();
        /** Call onGone only after the departure animation has completely disappeared. */
        void depart(Runnable onGone);
        void arrive();
        void hide();
        /** Called on the main thread. Copy/import the optional ZIP before replacing it. */
        void onSettings(String json, File skinPack);
    }

    private final SessionRuntime runtime;

    public CompanionSession(Context context, boolean phone, Host host) {
        if (context == null || host == null) throw new IllegalArgumentException("Context and host required");
        runtime = new SessionRuntime(context.getApplicationContext(), phone, host);
    }

    public static boolean isSupported() { return SessionRuntime.isSupported(); }
    public void start() { runtime.start(); }
    @Override public void close() { runtime.close(); }
    public void updatePresence(boolean interactive) { runtime.updatePresence(interactive); }
    public void publishSettings(String json, File skinPack) { runtime.publishSettings(json, skinPack); }
    public void setMode(String mode) { runtime.setMode(mode); }
    public String getStatus() { return runtime.getStatus(); }

    static String validatedMode(String mode) {
        if ("phone".equals(mode) || "watch".equals(mode) || "both".equals(mode) || "auto".equals(mode)) {
            return mode;
        }
        return "phone";
    }
}
