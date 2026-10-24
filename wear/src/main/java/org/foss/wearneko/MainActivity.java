// SPDX-License-Identifier: LGPL-2.1-only
package org.foss.wearneko;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.nqmgaming.aneko.companion.CompanionSession;

/** Small local control panel; the pet itself is owned by OverlayService. */
public final class MainActivity extends Activity
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int NOTIFICATION_REQUEST = 21;
    private SharedPreferences state;
    private TextView permissionView;
    private TextView statusView;
    private Button startButton;
    private Button stopButton;
    private boolean startAfterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(savedInstanceState);
        state = getSharedPreferences("wearneko_state", MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(15, 18, 24));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(30), dp(24), dp(30), dp(30));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("ANeko", 22, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        content.addView(title, fullWidth());
        TextView version = text(CompanionSession.isSupported()
                ? "Connected companion" : "FOSS · local companion", 12, Color.rgb(170, 182, 201));
        version.setGravity(Gravity.CENTER);
        add(content, version, 4);

        permissionView = text("", 14, Color.WHITE);
        add(content, permissionView, 18);
        statusView = text("", 14, Color.WHITE);
        add(content, statusView, 10);

        startButton = new Button(this);
        startButton.setText(R.string.start_pet);
        startButton.setAllCaps(false);
        startButton.setTextSize(14);
        startButton.setMinHeight(dp(48));
        startButton.setOnClickListener(view -> requestPetStart());
        add(content, startButton, 14);

        stopButton = new Button(this);
        stopButton.setText(R.string.stop_pet);
        stopButton.setAllCaps(false);
        stopButton.setTextSize(14);
        stopButton.setMinHeight(dp(48));
        stopButton.setOnClickListener(view -> {
            startAfterPermission = false;
            stopService(new Intent(this, OverlayService.class));
            refresh();
        });
        add(content, stopButton, 4);

        TextView returnHint = text("Start, then press the crown to return to your watch face.",
                12, Color.rgb(183, 192, 209));
        add(content, returnHint, 14);
        TextView setup = text("Wear OS 5 needs an ADB overlay grant. See setup guide.",
                12, Color.rgb(183, 192, 209));
        add(content, setup, 10);
        TextView behavior = text(CompanionSession.isSupported()
                ? "Start pets here and on your phone. Use the phone to select Phone, Watch, Both or Automatic and sync skins and settings. The watch pauses while asleep or ambient."
                : "Pets move while the display is awake and pause when it sleeps or enters ambient mode. The FOSS build has no phone connection.",
                12, Color.rgb(183, 192, 209));
        add(content, behavior, 10);
        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        state.registerOnSharedPreferenceChangeListener(this);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onStop() {
        state.unregisterOnSharedPreferenceChangeListener(this);
        super.onStop();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        runOnUiThread(this::refresh);
    }

    private void refresh() {
        boolean granted = Settings.canDrawOverlays(this);
        boolean running = OverlayService.isRunning();
        permissionView.setText(granted ? "Overlay permission: ready" : "Overlay permission: missing");
        permissionView.setTextColor(granted ? Color.rgb(150, 225, 178) : Color.rgb(255, 196, 116));
        String status = state.getString("status", "Stopped. Tap Start pet.");
        if (!running && state.getBoolean("running", false)) {
            status = "Stopped. Tap Start pet.";
        }
        statusView.setText(getString(R.string.service_report, status));
        startButton.setEnabled(granted && !running);
        startButton.setText(running ? R.string.pet_running : R.string.start_pet);
        stopButton.setEnabled(running);
    }

    private void requestPetStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant overlay access with ADB first.", Toast.LENGTH_LONG).show();
            refresh();
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
            return;
        }
        startPet();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_REQUEST && startAfterPermission) {
            startAfterPermission = false;
            // Notification permission denial does not forbid a foreground service.
            startPet();
        }
    }

    private void startPet() {
        if (!Settings.canDrawOverlays(this)) {
            refresh();
            return;
        }
        try {
            Intent intent = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_START);
            startForegroundService(intent);
        } catch (RuntimeException error) {
            state.edit().putBoolean("running", false)
                    .putString("status", "Start failed: " + error.getClass().getSimpleName()).apply();
            Toast.makeText(this, "Could not start the pet. See service report.", Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void add(LinearLayout parent, android.view.View view, int topMargin) {
        LinearLayout.LayoutParams layout = fullWidth();
        layout.topMargin = dp(topMargin);
        parent.addView(view, layout);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
