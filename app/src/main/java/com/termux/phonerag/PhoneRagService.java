package com.termux.phonerag;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PhoneRagService extends Service {
    private static final String TAG = "PhoneRagService";
    private static final String CHANNEL_ID = "phone_rag";
    private static final int NOTIFICATION_ID = 8791;
    private static final int PORT = 8791;

    private final ExecutorService clientExecutor = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private PhoneRagEngine engine;

    @Override
    public void onCreate() {
        super.onCreate();
        PhoneRagHttpServer.start(this);
        engine = new PhoneRagEngine(this);
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand action=" + (intent == null ? "null" : intent.getAction()));
        PhoneRagHttpServer.start(this);
        handleWorkIntent(intent);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
        clientExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private synchronized void startHttpServer() {
        if (running) {
            return;
        }
        running = true;
        serverThread = new Thread(this::serve, "phone-rag-http");
        serverThread.start();
    }

    private void serve() {
        try {
            serverSocket = new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"));
            Log.i(TAG, "listening on http://127.0.0.1:" + PORT);
            while (running) {
                Socket socket = serverSocket.accept();
                Log.d(TAG, "accepted local client");
                clientExecutor.execute(() -> handle(socket));
            }
        } catch (Exception exc) {
            if (running) {
                Log.e(TAG, "HTTP server failed", exc);
            }
        } finally {
            running = false;
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(300000);
            Request request = readRequest(s.getInputStream());
            JSONObject response;
            int status = 200;
            try {
                response = route(request);
            } catch (Exception exc) {
                status = 500;
                response = new JSONObject();
                response.put("ok", false);
                response.put("error", exc.getMessage() == null ? exc.toString() : exc.getMessage());
            }
            writeResponse(s, status, response);
        } catch (Exception exc) {
            Log.w(TAG, "client failed", exc);
        }
    }

    private JSONObject route(Request request) throws Exception {
        if ("GET".equals(request.method) && "/health".equals(request.path)) {
            return engine.health();
        }
        if ("POST".equals(request.method) && "/index".equals(request.path)) {
            return engine.index(request.jsonBody());
        }
        if ("POST".equals(request.method) && "/query".equals(request.path)) {
            return engine.query(request.jsonBody());
        }
        JSONObject json = new JSONObject();
        json.put("ok", false);
        json.put("error", "unknown route");
        json.put("routes", "GET /health, POST /index, POST /query");
        return json;
    }

    private Request readRequest(InputStream inputStream) throws Exception {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int matched = 0;
        int b;
        byte[] marker = new byte[] {'\r', '\n', '\r', '\n'};
        while ((b = inputStream.read()) != -1) {
            headerBytes.write(b);
            if ((byte) b == marker[matched]) {
                matched++;
                if (matched == marker.length) {
                    break;
                }
            } else {
                matched = ((byte) b == marker[0]) ? 1 : 0;
            }
            if (headerBytes.size() > 64 * 1024) {
                throw new IllegalArgumentException("request headers too large");
            }
        }

        String headers = headerBytes.toString(StandardCharsets.UTF_8.name());
        String[] lines = headers.split("\\r?\\n");
        if (lines.length == 0 || lines[0].isEmpty()) {
            throw new IllegalArgumentException("empty request");
        }
        String[] parts = lines[0].split(" ", 3);
        if (parts.length < 2) {
            throw new IllegalArgumentException("bad request line: " + lines[0]);
        }
        int contentLength = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if ("content-length".equals(name)) {
                contentLength = Integer.parseInt(value);
            }
        }
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = inputStream.read(body, read, contentLength - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return new Request(parts[0], parts[1], new String(body, 0, read, StandardCharsets.UTF_8));
    }

    private void writeResponse(Socket socket, int status, JSONObject json) throws Exception {
        byte[] body = json.toString(2).getBytes(StandardCharsets.UTF_8);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        writer.write("HTTP/1.1 " + status + " " + statusText(status) + "\r\n");
        writer.write("Content-Type: application/json; charset=utf-8\r\n");
        writer.write("Content-Length: " + body.length + "\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.flush();
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    private static String statusText(int status) {
        return status == 200 ? "OK" : "Internal Server Error";
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Phone RAG",
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_stat_phonerag)
                .setContentTitle("Phone RAG")
                .setContentText("Local vector DB on http://127.0.0.1:8791")
                .setOngoing(true)
                .build();
    }

    private void handleWorkIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        Log.i(TAG, "handleWorkIntent action=" + action);
        if (!PhoneRagReceiver.ACTION_INDEX.equals(action) && !PhoneRagReceiver.ACTION_QUERY.equals(action)) {
            Log.i(TAG, "ignoring non-work action=" + action);
            return;
        }
        Intent copy = new Intent(intent);
        clientExecutor.execute(() -> runWork(copy));
    }

    private void runWork(Intent intent) {
        Log.i(TAG, "runWork start action=" + intent.getAction());
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager powerManager = getSystemService(PowerManager.class);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneRag:index");
                wakeLock.acquire(20 * 60 * 1000L);
            }
            JSONObject result;
            if (PhoneRagReceiver.ACTION_INDEX.equals(intent.getAction())) {
                JSONObject request = new JSONObject();
                String text = intent.getStringExtra("text");
                String textFile = intent.getStringExtra("text_file");
                if ((text == null || text.isEmpty()) && textFile != null && !textFile.isEmpty()) {
                    text = new String(Files.readAllBytes(new File(textFile).toPath()), StandardCharsets.UTF_8);
                }
                request.put("text", text);
                request.put("title", readExtraOrFile(intent, "title", "title_file"));
                request.put("source", readExtraOrFile(intent, "source", "source_file"));
                request.put("chunk_size", intent.getIntExtra("chunk_size", 1000));
                request.put("overlap", intent.getIntExtra("overlap", 120));
                result = engine.index(request);
                result.put("action", PhoneRagReceiver.ACTION_INDEX);
            } else {
                JSONObject request = new JSONObject();
                request.put("query", readExtraOrFile(intent, "query", "query_file"));
                request.put("top_k", intent.getIntExtra("top_k", 6));
                request.put("min_score", intent.getFloatExtra("min_score", 0.0f));
                result = engine.query(request);
                result.put("action", PhoneRagReceiver.ACTION_QUERY);
            }
            result.put("completed_at", System.currentTimeMillis());
            writeResult(result);
            Log.i(TAG, "runWork complete action=" + intent.getAction());
        } catch (Throwable exc) {
            Log.e(TAG, "runWork failed", exc);
            try {
                JSONObject error = new JSONObject();
                error.put("ok", false);
                error.put("action", intent.getAction());
                error.put("error", exc.getMessage() == null ? exc.toString() : exc.getMessage());
                error.put("completed_at", System.currentTimeMillis());
                writeResult(error);
            } catch (Exception writeExc) {
                Log.e(TAG, "failed to write work error", writeExc);
            }
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    private void writeResult(JSONObject result) throws Exception {
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null) {
            dir = getFilesDir();
        }
        dir.mkdirs();
        java.io.File out = new java.io.File(dir, "last-result.json");
        try (java.io.FileWriter writer = new java.io.FileWriter(out, false)) {
            writer.write(result.toString(2));
            writer.write("\n");
        }
        Log.i(TAG, "wrote " + out.getAbsolutePath());
    }

    private static String readExtraOrFile(Intent intent, String textKey, String fileKey) throws Exception {
        String text = intent.getStringExtra(textKey);
        String file = intent.getStringExtra(fileKey);
        if ((text == null || text.isEmpty()) && file != null && !file.isEmpty()) {
            return new String(Files.readAllBytes(new File(file).toPath()), StandardCharsets.UTF_8).trim();
        }
        return text;
    }

    private static final class Request {
        final String method;
        final String path;
        final String body;

        Request(String method, String rawPath, String body) {
            this.method = method;
            int query = rawPath.indexOf('?');
            this.path = query >= 0 ? rawPath.substring(0, query) : rawPath;
            this.body = body == null ? "" : body;
        }

        JSONObject jsonBody() throws Exception {
            if (body.trim().isEmpty()) {
                return new JSONObject();
            }
            return new JSONObject(body);
        }
    }
}
