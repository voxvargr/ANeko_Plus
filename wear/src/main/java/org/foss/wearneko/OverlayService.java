// SPDX-License-Identifier: LGPL-2.1-only
package org.foss.wearneko;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import org.nqmgaming.aneko.companion.CompanionSession;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-started watch overlays. Sleep removes every window and animation callback. */
public final class OverlayService extends Service {
    public static final String ACTION_START = "org.foss.wearneko.START";
    public static final String ACTION_STOP = "org.foss.wearneko.STOP";
    private static final String CHANNEL = "pet_overlay";
    private static final int NOTIFICATION_ID = 7;
    private static final long FRAME_MS = 50L;
    private static final long HANDOFF_MS = 600L;
    private static final int BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    private static volatile boolean runningInProcess;

    private static final class PetInstance {
        PetView view;
        PetMotion motion;
        WindowManager.LayoutParams layout;
        float fromX, fromY, toX, toY;
        boolean attached;
    }
    private enum Transition { NONE, ENTER, EXIT }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService imports = Executors.newSingleThreadExecutor();
    private final List<PetInstance> pets = new ArrayList<>();
    private final List<Runnable> departureCallbacks = new ArrayList<>();
    private final Random random = new Random();
    private final AttentionCycle attentionCycle = new AttentionCycle();
    private SharedPreferences preferences;
    private WindowManager windowManager;
    private DisplayManager displayManager;
    private PowerManager powerManager;
    private NotificationManager notificationManager;
    private CompanionSession companion;
    private WatchPetSettings settings = WatchPetSettings.defaults();
    private WatchSkinPack skinPack;
    private volatile boolean started, destroyed, importingSettings;
    private boolean animating, receiverRegistered, displayListenerRegistered;
    private boolean screenOffReceived, terminalFailure, ownerVisible;
    private Boolean lastPresence;
    private long previousFrameMs, transitionStarted, attentionDueAt;
    private boolean attentionReactionActive;
    private volatile long settingsGeneration;
    private int attentionPet;
    private Transition transition = Transition.NONE;
    private int displayWidth, displayHeight;
    private String currentStatus;

    public static boolean isRunning() { return runningInProcess; }

    private final CompanionSession.Host companionHost = new CompanionSession.Host() {
        @Override public boolean isReady() {
            return started && !destroyed && !terminalFailure && !importingSettings
                    && (!CompanionSession.isSupported() || skinPack != null && !settings.json.isEmpty())
                    && Settings.canDrawOverlays(OverlayService.this);
        }
        @Override public void depart(Runnable onGone) { runOnMain(() -> departPets(onGone)); }
        @Override public void arrive() {
            runOnMain(() -> {
                if (!started || destroyed || terminalFailure) return;
                cancelTransition(false);
                ownerVisible = true;
                if (isAwake()) attachPets(true);
                else {
                    updateAwakeState();
                    publishStatus("Companions ready here; waiting for the watch to wake.");
                }
            });
        }
        @Override public void hide() {
            runOnMain(() -> {
                ownerVisible = false;
                pauseOverlay(true);
                if (started && !destroyed) publishStatus("Companions are on the phone or waiting for a transfer.");
            });
        }
        @Override public void onSettings(String json, File pack) { receiveSettings(json, pack); }
    };

    private void runOnMain(Runnable action) {
        // Session ownership callbacks already run on main. Apply them before a later
        // protocol event can revoke the grant, rather than queueing stale visibility.
        if (Looper.myLooper() == handler.getLooper()) action.run();
        else handler.post(action);
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) screenOffReceived = true;
            else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                    || Intent.ACTION_USER_PRESENT.equals(intent.getAction())) screenOffReceived = false;
            updateAwakeState();
        }
    };
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { updateAwakeState(); }
        @Override public void onDisplayRemoved(int id) { updateAwakeState(); }
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) { updateBounds(); updateAwakeState(); }
        }
    };

    private final Runnable attention = () -> {
        long dueAt = attentionDueAt;
        attentionDueAt = 0;
        if (!canSeekAttention()) return;
        long now = SystemClock.uptimeMillis();
        if (dueAt == 0 || now < dueAt || now-dueAt > 1_000L) {
            scheduleAttention();
            return;
        }
        attentionCycle.start(now);
        attentionPet = random.nextInt(pets.size());
        attentionReactionActive = true;
        pets.get(attentionPet).view.reactToTap();
    };

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!started || destroyed || !animating) return;
            if (!isAwake()) { updateAwakeState(); return; }
            if (!Settings.canDrawOverlays(OverlayService.this)) {
                fail("Overlay permission was removed. Grant it again, then tap Start pet.", null); return;
            }
            long now = SystemClock.uptimeMillis();
            long frameGap = now-previousFrameMs;
            boolean continuousFrames = frameGap >= 0 && frameGap <= AttentionCycle.MAX_FRAME_GAP_MS;
            if (attentionCycle.isRunning() && !canSeekAttention()) cancelAttention();
            AttentionCycle.Update attentionUpdate = attentionCycle.advance(now);
            if (attentionUpdate == AttentionCycle.Update.CANCELLED) {
                clearAttentionReaction();
                scheduleAttention();
            }
            float seconds = Math.min(0.25f, Math.max(0f, (now-previousFrameMs)/1000f));
            previousFrameMs = now;
            try {
                if (transition != Transition.NONE) {
                    float progress = ValueAnimator.areAnimatorsEnabled()
                            ? Math.min(1f, (now-transitionStarted)/(float) HANDOFF_MS) : 1f;
                    for (PetInstance pet : pets) {
                        pet.layout.x = Math.round(pet.fromX+(pet.toX-pet.fromX)*progress);
                        pet.layout.y = Math.round(pet.fromY+(pet.toY-pet.fromY)*progress);
                        updatePetWindow(pet);
                    }
                    if (progress >= 1f) {
                        if (transition == Transition.EXIT) { pauseOverlay(true); return; }
                        transition = Transition.NONE;
                        for (PetInstance pet : pets) {
                            pet.layout.flags = BASE_FLAGS;
                            pet.motion.placeAt(pet.layout.x+pet.layout.width/2f, pet.layout.y+pet.layout.height/2f);
                            updatePetWindow(pet);
                        }
                        if (!pets.isEmpty() && continuousFrames) haptic(pets.get(0), false);
                        scheduleAttention();
                        publishStatus(settings.quiet ? "Companions are resting here." : "Companions are roaming here.");
                    }
                } else {
                    for (int i=0; i<pets.size(); i++) {
                        PetInstance pet = pets.get(i);
                        if (!settings.quiet && (!attentionCycle.isRunning() || i != attentionPet)) pet.motion.advance(seconds);
                        pet.view.setPose(pet.motion.getDirection(), settings.quiet || attentionCycle.isRunning() && i == attentionPet
                                ? PetMotion.Behavior.IDLE : pet.motion.getBehavior());
                        pet.layout.x = Math.round(pet.motion.getX());
                        pet.layout.y = Math.round(pet.motion.getY());
                        if (attentionCycle.isRunning() && i == attentionPet) {
                            float progress = attentionCycle.progress(now);
                            if (ValueAnimator.areAnimatorsEnabled()) {
                                float hop = (float)Math.sin(progress*Math.PI)*4*getResources().getDisplayMetrics().density;
                                pet.layout.y = Math.max(0, Math.round(pet.layout.y-hop));
                            }
                        }
                        if (attentionUpdate == AttentionCycle.Update.LANDED && i == attentionPet) {
                            clearAttentionReaction();
                            haptic(pet, true);
                            scheduleAttention();
                        }
                        updatePetWindow(pet);
                    }
                }
            } catch (RuntimeException error) {
                fail("Watch rejected the pet overlay. Stop and restart; see the setup guide.", error); return;
            }
            handler.postDelayed(this, FRAME_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences("wearneko_state", MODE_PRIVATE);
        windowManager = getSystemService(WindowManager.class);
        displayManager = getSystemService(DisplayManager.class);
        powerManager = getSystemService(PowerManager.class);
        notificationManager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Pet controls", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Status and Stop control for watch companions");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            started = false;
            ownerVisible = false;
            runningInProcess = false;
            cancelAttention();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (started) { updateAwakeState(); return START_NOT_STICKY; }
        try {
            startForeground(NOTIFICATION_ID, notification("Starting companions…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            if (!Settings.canDrawOverlays(this)) {
                fail("Overlay permission missing. Use the ADB grant in the setup guide.", null); return START_NOT_STICKY;
            }
            Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            displayWidth = bounds.width(); displayHeight = bounds.height();
            restoreSettings();
            buildPets();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
            displayManager.registerDisplayListener(displayListener, handler);
            displayListenerRegistered = true;
            started = true;
            runningInProcess = true;
            ownerVisible = false;
            companion = new CompanionSession(this, false, companionHost);
            companion.start();
            updateAwakeState();
        } catch (RuntimeException error) {
            fail("Could not start companions: " + error.getClass().getSimpleName()+". Check overlay access and try again.", error);
        }
        return START_NOT_STICKY;
    }

    private void restoreSettings() {
        try {
            String stored = preferences.getString("companion.settings", "");
            if (!stored.isEmpty()) settings = WatchPetSettings.parse(stored);
            String packName = preferences.getString("companion.pack", "");
            if (packName.matches("pack-[a-f0-9-]+")) {
                skinPack = WatchSkinPack.read(new File(new File(getFilesDir(), "watch-frames"), packName));
            }
        } catch (Exception error) {
            Log.w("WearNeko", "Stored frame pack unavailable; using built-in frames", error);
            skinPack = null;
        }
    }

    @SuppressLint("RtlHardcoded")
    private void buildPets() {
        float density = getResources().getDisplayMetrics().density;
        for (int i=0; i<settings.pets.length; i++) {
            WatchPetSettings.PetOptions options = settings.pets[i];
            int size = Math.max(8, Math.round(16*density*options.sizeScale));
            size = Math.min(size, Math.max(8, Math.min(displayWidth, displayHeight)/4));
            PetInstance pet = new PetInstance();
            pet.motion = new PetMotion(displayWidth, displayHeight, size, 57.6f*density*options.speed, System.nanoTime()+i);
            double angle = i*Math.PI*2/settings.pets.length;
            float spread = settings.pets.length == 1 ? 0f : Math.min(displayWidth, displayHeight)*0.22f;
            pet.motion.placeAt(displayWidth/2f+(float)Math.cos(angle)*spread, displayHeight/2f+(float)Math.sin(angle)*spread);
            WatchSkinPack.Skin skin = skinPack == null ? null : skinPack.skin(options.id, i);
            pet.view = new PetView(this, skinPack, skin, options.speed);
            pet.view.setAlpha(options.opacity);
            pet.view.setContentDescription(options.name);
            pet.view.setOnClickListener(view -> {
                if (transition != Transition.NONE || !isPetActive(pet)) return;
                cancelAttention();
                pet.motion.reactToTap(); pet.view.reactToTap(); haptic(pet, false);
                scheduleAttention();
            });
            pet.layout = new WindowManager.LayoutParams(size, size,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, BASE_FLAGS, PixelFormat.TRANSLUCENT);
            pet.layout.gravity = Gravity.TOP | Gravity.LEFT;
            pet.layout.setTitle("ANeko watch companion "+(i+1));
            pet.layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            pet.layout.x = Math.round(pet.motion.getX()); pet.layout.y = Math.round(pet.motion.getY());
            pets.add(pet);
        }
    }

    private void receiveSettings(String json, File archive) {
        if (destroyed || !started) return;
        final WatchPetSettings proposed;
        try { proposed = WatchPetSettings.parse(json); }
        catch (Exception error) {
            Log.w("WearNeko", "Ignoring invalid companion settings", error);
            publishStatus("Could not import phone settings; keeping the last good companions."); return;
        }
        final long generation = ++settingsGeneration;
        importingSettings = true;
        imports.execute(() -> {
            try {
                WatchSkinPack imported = archive == null ? null : WatchSkinPack.importArchive(this, archive);
                handler.post(new Runnable() {
                    @Override public void run() {
                    if (destroyed || generation != settingsGeneration) {
                        if (imported != null) { imported.release(); SafeFrameArchive.deleteTree(imported.directory); }
                        return;
                    }
                    // A settings refresh cannot truncate an in-progress source departure.
                    if (transition != Transition.NONE) { handler.postDelayed(this, FRAME_MS); return; }
                    boolean needsEntry = !animating;
                    pauseOverlay(true);
                    for (PetInstance pet : pets) pet.view.dispose();
                    pets.clear();
                    WatchSkinPack previous = skinPack;
                    if (imported != null) skinPack = imported;
                    settings = proposed;
                    SharedPreferences.Editor editor = preferences.edit().putString("companion.settings", proposed.json);
                    if (skinPack != null) editor.putString("companion.pack", skinPack.directory.getName());
                    editor.apply();
                    buildPets();
                    importingSettings = false;
                    if (previous != null && previous != skinPack) previous.release();
                    if (ownerVisible && isAwake()) attachPets(needsEntry);
                    else publishStatus("Phone settings synced. Companions are ready.");
                    File keep = skinPack == null ? null : skinPack.directory;
                    imports.execute(() -> {
                        if (generation == settingsGeneration) WatchSkinPack.cleanup(OverlayService.this, keep);
                    });
                    if (companion != null) companion.updatePresence(isAwake());
                    }
                });
            } catch (Exception error) {
                Log.w("WearNeko", "Ignoring invalid phone frames", error);
                handler.post(() -> {
                    if (!destroyed && generation == settingsGeneration) {
                        importingSettings = false;
                        publishStatus("Could not import phone frames; keeping the last good companions.");
                        if (companion != null) companion.updatePresence(isAwake());
                    }
                });
            }
        });
    }

    private boolean isAwake() {
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        return !screenOffReceived && powerManager.isInteractive() && display != null && display.getState() == Display.STATE_ON;
    }
    private void updateAwakeState() {
        if (!started || destroyed || terminalFailure) return;
        boolean awake = isAwake();
        if (!awake) {
            // The linked watch must receive a fresh grant on wake. A previous local
            // owner cannot reattach before the phone has reconciled current ownership.
            if (CompanionSession.isSupported()) ownerVisible = false;
            pauseOverlay(true);
            publishStatus("Paused: screen asleep or ambient. No animation is running.");
        }
        if (lastPresence == null || lastPresence != awake) {
            lastPresence = awake;
            if (companion != null) companion.updatePresence(awake);
        }
        if (awake && ownerVisible && !animating) attachPets(true);
    }
    private void updateBounds() {
        if (destroyed || pets.isEmpty()) return;
        Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
        if (bounds.width() == displayWidth && bounds.height() == displayHeight) return;
        pauseOverlay(true);
        displayWidth = bounds.width(); displayHeight = bounds.height();
        for (PetInstance pet : pets) {
            pet.motion.setBounds(displayWidth, displayHeight, pet.layout.width);
            pet.layout.x = Math.round(pet.motion.getX()); pet.layout.y = Math.round(pet.motion.getY());
        }
    }

    private void attachPets(boolean animateEntry) {
        if (animating || !started || destroyed || importingSettings || !ownerVisible || !isAwake()) return;
        try {
            if (!Settings.canDrawOverlays(this)) { fail("Overlay permission missing. Grant it again, then tap Start pet.", null); return; }
            updateBounds();
            transition = animateEntry ? Transition.ENTER : Transition.NONE;
            transitionStarted = SystemClock.uptimeMillis();
            for (PetInstance pet : pets) {
                pet.toX = pet.motion.getX(); pet.toY = pet.motion.getY();
                pet.layout.flags = animateEntry ? BASE_FLAGS | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS : BASE_FLAGS;
                if (animateEntry) {
                    float[] outside = outsidePoint(pet.toX, pet.toY, pet.layout.width);
                    pet.fromX = outside[0]; pet.fromY = outside[1];
                    pet.layout.x = Math.round(pet.fromX); pet.layout.y = Math.round(pet.fromY);
                    pet.view.setPose(direction(pet.toX-pet.fromX, pet.toY-pet.fromY), PetMotion.Behavior.WALK);
                } else {
                    pet.layout.x = Math.round(pet.toX); pet.layout.y = Math.round(pet.toY);
                    pet.view.setPose(pet.motion.getDirection(), settings.quiet ? PetMotion.Behavior.IDLE : pet.motion.getBehavior());
                }
                windowManager.addView(pet.view, pet.layout);
                pet.attached = true;
                pet.view.resumeAnimation();
            }
            animating = true;
            previousFrameMs = SystemClock.uptimeMillis();
            handler.removeCallbacks(frame); handler.post(frame);
            if (!animateEntry) scheduleAttention();
            publishStatus(animateEntry ? "Companions are running onto the watch…" : "Companions are here.");
        } catch (RuntimeException error) { fail("Watch rejected the overlay. Check the ADB grant and try again.", error); }
    }
    private void departPets(Runnable onGone) {
        cancelAttention();
        if (destroyed || !started || !animating || !isAwake()) {
            ownerVisible = false; pauseOverlay(true); onGone.run(); return;
        }
        departureCallbacks.add(onGone);
        ownerVisible = false;
        if (transition == Transition.EXIT) return;
        transition = Transition.EXIT;
        transitionStarted = SystemClock.uptimeMillis();
        for (PetInstance pet : pets) {
            pet.fromX = pet.layout.x; pet.fromY = pet.layout.y;
            float[] outside = outsidePoint(pet.fromX, pet.fromY, pet.layout.width);
            pet.toX = outside[0]; pet.toY = outside[1];
            pet.layout.flags = BASE_FLAGS | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            pet.view.setPose(direction(pet.toX-pet.fromX, pet.toY-pet.fromY), PetMotion.Behavior.WALK);
        }
        publishStatus("Companions are running off before transferring…");
    }
    private float[] outsidePoint(float x, float y, int size) {
        float[] distances = {x+size, displayWidth-x, y+size, displayHeight-y};
        int edge = 0;
        for (int i=1; i<distances.length; i++) if (distances[i] < distances[edge]) edge=i;
        switch (edge) {
            case 0: return new float[]{-size-8f, y};
            case 1: return new float[]{displayWidth+8f, y};
            case 2: return new float[]{x, -size-8f};
            default: return new float[]{x, displayHeight+8f};
        }
    }
    private static int direction(float dx, float dy) {
        return ((int)Math.round(Math.atan2(dy, dx)/(Math.PI/4d))+8)%8;
    }
    private void updatePetWindow(PetInstance pet) {
        if (pet.attached) windowManager.updateViewLayout(pet.view, pet.layout);
    }
    private void cancelTransition(boolean completeDeparture) {
        transition = Transition.NONE;
        List<Runnable> callbacks = new ArrayList<>(departureCallbacks);
        departureCallbacks.clear();
        if (completeDeparture) for (Runnable callback : callbacks) callback.run();
    }
    private void pauseOverlay(boolean completeDeparture) {
        animating = false; handler.removeCallbacks(frame); cancelAttention();
        for (PetInstance pet : pets) {
            pet.view.pauseAnimation();
            if (pet.attached) {
                try { windowManager.removeViewImmediate(pet.view); }
                catch (RuntimeException error) { Log.w("WearNeko", "Could not detach overlay", error); }
                pet.attached = false;
            }
            pet.layout.flags = BASE_FLAGS;
        }
        // Every source window is detached before the transfer acknowledgement.
        cancelTransition(completeDeparture);
    }
    private boolean canSeekAttention() {
        return started && !destroyed && ownerVisible && animating && isAwake()
                && !terminalFailure && transition == Transition.NONE && settings.attention && !settings.quiet
                && !pets.isEmpty() && pets.stream().allMatch(this::isPetVisible);
    }
    private void scheduleAttention() {
        handler.removeCallbacks(attention);
        attentionDueAt = 0;
        if (canSeekAttention()) {
            long delay = (8+random.nextInt(8))*60_000L;
            attentionDueAt = SystemClock.uptimeMillis()+delay;
            handler.postDelayed(attention, delay);
        }
    }
    private void clearAttentionReaction() {
        if (attentionReactionActive && attentionPet >= 0 && attentionPet < pets.size()) {
            pets.get(attentionPet).view.cancelReaction();
        }
        attentionReactionActive = false;
    }
    private void cancelAttention() {
        handler.removeCallbacks(attention);
        attentionDueAt = 0;
        attentionCycle.cancel();
        clearAttentionReaction();
    }
    private boolean isPetVisible(PetInstance pet) {
        return pet != null && pets.contains(pet) && pet.attached && pet.view.isAttachedToWindow()
                && pet.view.isShown() && pet.view.getWindowVisibility() == View.VISIBLE && pet.view.getAlpha() > 0f;
    }
    private boolean isPetActive(PetInstance pet) {
        return started && !destroyed && !terminalFailure && ownerVisible && animating
                && isPetVisible(pet) && isAwake();
    }
    private void haptic(PetInstance pet, boolean automaticAttention) {
        if (!settings.haptics || !isPetActive(pet)) return;
        if (automaticAttention && (!settings.attention || settings.quiet || transition != Transition.NONE)) return;
        if (Settings.System.getInt(getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return;
        // Recheck immediately before the only haptic call. No wake lock, alarm, or
        // vibrator request is used to make an inactive watch eligible for attention.
        if (isPetActive(pet)) {
            pet.view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private Notification notification(String status) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, OverlayService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_pet)
                .setContentTitle("ANeko watch companions").setContentText(status).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Stop pets", stop).build()).build();
    }
    private void publishStatus(String status) {
        String link = companion == null ? "" : companion.getStatus();
        String combined = link.isEmpty() ? status : status+"\n"+link;
        if (combined.equals(currentStatus)) return;
        currentStatus = combined;
        preferences.edit().putBoolean("running", started).putString("status", combined).apply();
        Log.i("WearNeko", combined);
        if (started && !destroyed && !terminalFailure) notificationManager.notify(NOTIFICATION_ID, notification(status));
    }
    private void fail(String status, RuntimeException error) {
        terminalFailure = true; started = false; runningInProcess = false;
        pauseOverlay(true);
        if (error != null) Log.e("WearNeko", status, error);
        publishStatus(status); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() {
        destroyed = true; started = false; runningInProcess = false;
        if (companion != null) companion.close();
        pauseOverlay(false); handler.removeCallbacksAndMessages(null); imports.shutdown();
        if (receiverRegistered) unregisterReceiver(screenReceiver);
        if (displayListenerRegistered) displayManager.unregisterDisplayListener(displayListener);
        for (PetInstance pet : pets) pet.view.dispose();
        pets.clear(); if (skinPack != null) skinPack.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (!terminalFailure) publishStatus("Stopped. Tap Start pet to run again.");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
