package com.gaz.tvaudio;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * One screen for both devices.
 * On the TV press "Send TV sound"; on the phone press "Listen here".
 */
public class MainActivity extends Activity {
    private static final int REQ_PERMS_SEND = 1;
    private static final int REQ_PERMS_LISTEN = 2;
    private static final int REQ_CAPTURE = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView ipView;
    private EditText hostInput;
    private String lastError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (24 * d);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        ipView = new TextView(this);
        ipView.setTextSize(16);
        ipView.setPadding(0, pad / 3, 0, pad);
        col.addView(ipView);

        Button send = bigButton("Send TV sound (use on the TV)");
        send.setOnClickListener(v -> startSending());
        col.addView(send);

        hostInput = new EditText(this);
        hostInput.setHint("TV address — leave empty to find it automatically");
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        col.addView(hostInput, lp);

        Button listen = bigButton("Listen here (use on the phone)");
        listen.setOnClickListener(v -> startListening());
        col.addView(listen);

        Button stop = bigButton("Stop");
        stop.setOnClickListener(v -> stopAll());
        col.addView(stop);

        statusView = new TextView(this);
        statusView.setTextSize(18);
        statusView.setPadding(0, pad, 0, 0);
        col.addView(statusView);

        TextView help = new TextView(this);
        help.setTextSize(14);
        help.setTextColor(Color.GRAY);
        help.setPadding(0, pad, 0, 0);
        help.setText("Both devices must be on the same WiFi. "
                + "Apps with copy protection (e.g. Netflix) may send silence.");
        col.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(col);
        send.requestFocus();   // so the TV remote starts on the right button
        return scroll;
    }

    private Button bigButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (12 * getResources().getDisplayMetrics().density);
        b.setLayoutParams(lp);
        return b;
    }

    // ---- sending (TV) ----

    private void startSending() {
        lastError = null;
        if (needPermissions(REQ_PERMS_SEND, Manifest.permission.RECORD_AUDIO)) return;
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (ActivityNotFoundException e) {
            lastError = "This TV has no capture-permission screen. From a computer run:\n"
                    + "adb shell appops set " + getPackageName() + " PROJECT_MEDIA allow\n"
                    + "then try again.";
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            lastError = "Capture permission was refused.";
            return;
        }
        Intent i = new Intent(this, SenderService.class)
                .putExtra(SenderService.EXTRA_CODE, resultCode)
                .putExtra(SenderService.EXTRA_DATA, data);
        startForegroundService(i);
    }

    // ---- listening (phone) ----

    private void startListening() {
        lastError = null;
        if (needPermissions(REQ_PERMS_LISTEN)) return;
        Intent i = new Intent(this, ReceiverService.class)
                .putExtra(ReceiverService.EXTRA_HOST, hostInput.getText().toString());
        startForegroundService(i);
    }

    private void stopAll() {
        stopService(new Intent(this, SenderService.class));
        stopService(new Intent(this, ReceiverService.class));
        lastError = null;
    }

    // ---- permissions ----

    /** Requests anything missing; returns true if we must wait for the answer. */
    private boolean needPermissions(int req, String... required) {
        List<String> missing = new ArrayList<>();
        for (String p : required) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (missing.isEmpty()) return false;
        requestPermissions(missing.toArray(new String[0]), req);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        for (int i = 0; i < perms.length; i++) {
            if (Manifest.permission.RECORD_AUDIO.equals(perms[i])
                    && results[i] != PackageManager.PERMISSION_GRANTED) {
                lastError = "Audio permission is needed to capture the TV sound.";
                return;
            }
        }
        // Notification permission is optional; carry on either way.
        if (req == REQ_PERMS_SEND) startSending();
        else if (req == REQ_PERMS_LISTEN) startListening();
    }

    // ---- live status ----

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            String ip = Net.localIp();
            ipView.setText(ip == null ? "Not connected to WiFi" : "This device: " + ip);
            String s;
            if (lastError != null) s = lastError;
            else if (SenderService.running) s = SenderService.status;
            else if (ReceiverService.running) s = ReceiverService.status;
            else if (!"Not sending".equals(SenderService.status)) s = SenderService.status;
            else s = "Idle";
            statusView.setText(s);
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
    }
}
