package com.termux.phonerag;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PhoneRagHttpServer {
    private static final String TAG = "PhoneRagHttpServer";
    private static final int PORT = 8791;
    private static PhoneRagHttpServer instance;

    static synchronized PhoneRagHttpServer start(Context context) {
        if (instance == null) {
            instance = new PhoneRagHttpServer(context.getApplicationContext());
            instance.start();
        }
        return instance;
    }

    private final ExecutorService clientExecutor = Executors.newCachedThreadPool();
    private final PhoneRagEngine engine;
    private volatile boolean running;
    private ServerSocket serverSocket;

    private PhoneRagHttpServer(Context context) {
        this.engine = new PhoneRagEngine(context);
    }

    private void start() {
        running = true;
        Thread thread = new Thread(this::serve, "phone-rag-http");
        thread.setDaemon(true);
        thread.start();
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
            Log.e(TAG, "HTTP server stopped", exc);
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
        writer.write("HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Internal Server Error") + "\r\n");
        writer.write("Content-Type: application/json; charset=utf-8\r\n");
        writer.write("Content-Length: " + body.length + "\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.flush();
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
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
