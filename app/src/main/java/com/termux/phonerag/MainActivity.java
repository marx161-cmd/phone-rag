package com.termux.phonerag;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermission();
        PhoneRagHttpServer.start(this);
        startServer();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Phone RAG");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView status = new TextView(this);
        status.setText("Server: http://127.0.0.1:8791\nModel: /data/local/tmp/embeddinggemma/\nDB: app files/pocket-rag.db");
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, pad, 0, pad);
        layout.addView(status);

        Button start = new Button(this);
        start.setText("Restart server");
        start.setOnClickListener(v -> startServer());
        layout.addView(start);

        setContentView(layout);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    private void startServer() {
        Intent intent = new Intent(this, PhoneRagService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
