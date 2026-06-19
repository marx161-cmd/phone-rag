package com.termux.phonerag;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

public final class PhoneRagReceiver extends BroadcastReceiver {
    private static final String TAG = "PhoneRagReceiver";
    static final String ACTION_HEALTH = "com.termux.phonerag.HEALTH";
    static final String ACTION_INDEX = "com.termux.phonerag.INDEX";
    static final String ACTION_QUERY = "com.termux.phonerag.QUERY";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        PhoneRagHttpServer.start(app);
        if (ACTION_INDEX.equals(intent.getAction()) || ACTION_QUERY.equals(intent.getAction())) {
            Intent service = new Intent(app, PhoneRagService.class);
            service.setAction(intent.getAction());
            service.putExtras(intent);
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(service);
            } else {
                app.startService(service);
            }
            return;
        }
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                JSONObject result = handle(app, intent);
                writeResult(app, result);
            } catch (Exception exc) {
                try {
                    JSONObject error = new JSONObject();
                    error.put("ok", false);
                    error.put("error", exc.getMessage() == null ? exc.toString() : exc.getMessage());
                    writeResult(app, error);
                } catch (Exception writeExc) {
                    Log.e(TAG, "failed to write error", writeExc);
                }
            } finally {
                pending.finish();
            }
        }, "phone-rag-broadcast").start();
    }

    private JSONObject handle(Context context, Intent intent) throws Exception {
        PhoneRagEngine engine = new PhoneRagEngine(context);
        String action = intent.getAction();
        if (ACTION_HEALTH.equals(action)) {
            JSONObject result = engine.health();
            result.put("action", ACTION_HEALTH);
            result.put("completed_at", System.currentTimeMillis());
            return result;
        }
        if (ACTION_INDEX.equals(action)) {
            JSONObject request = new JSONObject();
            request.put("text", intent.getStringExtra("text"));
            request.put("title", intent.getStringExtra("title"));
            request.put("source", intent.getStringExtra("source"));
            request.put("chunk_size", intent.getIntExtra("chunk_size", 1000));
            request.put("overlap", intent.getIntExtra("overlap", 120));
            JSONObject result = engine.index(request);
            result.put("action", ACTION_INDEX);
            result.put("completed_at", System.currentTimeMillis());
            return result;
        }
        if (ACTION_QUERY.equals(action)) {
            JSONObject request = new JSONObject();
            request.put("query", intent.getStringExtra("query"));
            request.put("top_k", intent.getIntExtra("top_k", 6));
            request.put("min_score", intent.getFloatExtra("min_score", 0.0f));
            JSONObject result = engine.query(request);
            result.put("action", ACTION_QUERY);
            result.put("completed_at", System.currentTimeMillis());
            return result;
        }
        JSONObject error = new JSONObject();
        error.put("ok", false);
        error.put("error", "unknown action");
        error.put("action", action);
        return error;
    }

    private void writeResult(Context context, JSONObject result) throws Exception {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        dir.mkdirs();
        File out = new File(dir, "last-result.json");
        try (FileWriter writer = new FileWriter(out, false)) {
            writer.write(result.toString(2));
            writer.write("\n");
        }
        Log.i(TAG, "wrote " + out.getAbsolutePath());
    }
}
