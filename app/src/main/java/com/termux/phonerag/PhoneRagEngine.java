package com.termux.phonerag;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class PhoneRagEngine {
    static final int DIMENSIONS = 768;
    static final int DEFAULT_CHUNK_SIZE = 1800;
    static final int DEFAULT_OVERLAP = 180;
    static final int MIN_CHUNK_SIZE = 120;
    static final int MAX_CHUNK_SIZE = 4000;
    static final String MODEL_PATH = NativeNpuEmbeddingModel.MODEL_PATH;
    static final String TOKENIZER_PATH = NativeNpuEmbeddingModel.TOKENIZER_PATH;

    private final Context context;
    private final Object lock = new Object();
    private NativeNpuEmbeddingModel embedder;
// chunker removed
    private File databasePath;
    private SQLiteDatabase database;

    PhoneRagEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    JSONObject health() throws Exception {
        JSONObject json = new JSONObject();
        File model = new File(MODEL_PATH);
        File tokenizer = new File(TOKENIZER_PATH);
        File db = dbPath();
        synchronized (lock) {
            json.put("ok", true);
            json.put("initialized", embedder != null);
        }
        json.put("resident_worker_alive", NativeNpuEmbeddingModel.isResidentWorkerAlive());
        json.put("model_path", MODEL_PATH);
        json.put("model_exists", model.exists());
        json.put("model_size", model.exists() ? model.length() : 0);
        json.put("tokenizer_path", TOKENIZER_PATH);
        json.put("tokenizer_exists", tokenizer.exists());
        File worker = NativeNpuEmbeddingModel.workerFile(context);
        json.put("worker_path", worker.getAbsolutePath());
        json.put("external_worker_path", NativeNpuEmbeddingModel.EXTERNAL_WORKER_PATH);
        json.put("worker_exists", worker.exists());
        json.put("worker_can_execute", worker.canExecute());
        json.put("dispatch_dir", NativeNpuEmbeddingModel.dispatchDir(context));
        json.put("external_dispatch_dir", NativeNpuEmbeddingModel.EXTERNAL_DISPATCH_DIR);
        json.put("backend", "litert_compiled_model_google_tensor_npu_worker");
        json.put("db_path", db.getAbsolutePath());
        json.put("db_exists", db.exists());
        json.put("db_size", db.exists() ? db.length() : 0);
        json.put("dimensions", DIMENSIONS);
        return json;
    }

    JSONObject index(JSONObject request) throws Exception {
        writeStage("index_start");
        String text = request.optString("text", "").trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text is required");
        }
        String title = request.optString("title", "");
        String source = request.optString("source", "");
        int chunkSize = clamp(request.optInt("chunk_size", DEFAULT_CHUNK_SIZE), MIN_CHUNK_SIZE, MAX_CHUNK_SIZE);
        int overlap = clamp(request.optInt("overlap", DEFAULT_OVERLAP), 0, Math.max(0, chunkSize / 2));
        boolean replaceSource = request.optBoolean("replace_source", true);

        ensureInitialized();
        writeStage("chunk_start");
        List<String> chunks = chunkText(text, chunkSize, overlap);
        writeStage("embed_start_chunks_" + chunks.size());

        synchronized (lock) {
            List<List<Float>> embeddings = new ArrayList<>();
            for (String chunk : chunks) {
                embeddings.add(embedder.embedDocument(chunk));
            }
            writeStage("embed_done");
            database.beginTransaction();
            try {
                if (replaceSource && !source.isEmpty()) {
                    database.delete("chunks", "source = ?", new String[] {source});
                }
                for (int i = 0; i < chunks.size(); i++) {
                    ContentValues values = new ContentValues();
                    values.put("title", title);
                    values.put("source", source);
                    values.put("chunk_index", i);
                    values.put("chunk_count", chunks.size());
                    values.put("chunk_size", chunkSize);
                    values.put("overlap", overlap);
                    values.put("text", chunks.get(i));
                    values.put("embedding", floatsToBlob(embeddings.get(i)));
                    values.put("created_at", System.currentTimeMillis());
                    database.insertOrThrow("chunks", null, values);
                }
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
            writeStage("record_done");
            JSONObject json = health();
            json.put("ok", true);
            json.put("chunks_indexed", chunks.size());
            json.put("chunk_size", chunkSize);
            json.put("overlap", overlap);
            json.put("replace_source", replaceSource);
            return json;
        }
    }

    JSONObject query(JSONObject request) throws Exception {
        writeStage("query_start");
        String query = request.optString("query", "").trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("query is required");
        }
        int topK = clamp(request.optInt("top_k", 6), 1, 30);
        double minScore = request.optDouble("min_score", 0.0);

        ensureInitialized();
        writeStage("query_embed_start");
        synchronized (lock) {
            List<Float> queryVector = embedder.embedQuery(query);
            writeStage("query_embed_done");
            List<SearchHit> hits = new ArrayList<>();
            try (Cursor cursor = database.query(
                    "chunks",
                    new String[] {"title", "source", "chunk_index", "chunk_count", "text", "embedding"},
                    null,
                    null,
                    null,
                    null,
                    null)) {
                while (cursor.moveToNext()) {
                    float score = cosine(queryVector, blobToFloats(cursor.getBlob(5)));
                    if (score < minScore) {
                        continue;
                    }
                    JSONObject metadata = new JSONObject();
                    metadata.put("title", cursor.getString(0));
                    metadata.put("source", cursor.getString(1));
                    metadata.put("chunk", cursor.getInt(2));
                    metadata.put("chunks", cursor.getInt(3));
                    hits.add(new SearchHit(score, cursor.getString(4), metadata));
                }
            }
            hits.sort(Comparator.comparingDouble((SearchHit hit) -> hit.score).reversed());
            JSONArray results = new JSONArray();
            for (int i = 0; i < Math.min(topK, hits.size()); i++) {
                SearchHit hit = hits.get(i);
                JSONObject item = new JSONObject();
                item.put("score", hit.score);
                item.put("text", hit.text);
                item.put("metadata", hit.metadata);
                results.put(item);
            }
            writeStage("retrieve_done");
            JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("query", query);
            json.put("results", results);
            return json;
        }
    }

    private void ensureInitialized() {
        synchronized (lock) {
            if (embedder != null) {
                writeStage("ensure_already_initialized");
                return;
            }
            writeStage("ensure_start");
            File model = new File(MODEL_PATH);
            File tokenizer = new File(TOKENIZER_PATH);
            if (!model.exists()) {
                throw new IllegalStateException("missing model file: " + MODEL_PATH);
            }
            if (!tokenizer.exists()) {
                throw new IllegalStateException("missing tokenizer file: " + TOKENIZER_PATH);
            }
            writeStage("before_chunker");
            // TextChunker removed - using character fallback only
            writeStage("before_native_npu_worker");
            File worker = NativeNpuEmbeddingModel.workerFile(context);
            if (!worker.exists()) {
                throw new IllegalStateException("missing embedding worker: " + worker.getAbsolutePath());
            }
            if (!worker.canExecute()) {
                throw new IllegalStateException("embedding worker is not executable: " + worker.getAbsolutePath());
            }
            embedder = new NativeNpuEmbeddingModel(context);
            writeStage("after_native_npu_worker");
            database = SQLiteDatabase.openOrCreateDatabase(dbPath(), null);
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS chunks ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + "title TEXT,"
                            + "source TEXT,"
                            + "chunk_index INTEGER,"
                            + "chunk_count INTEGER,"
                            + "chunk_size INTEGER,"
                            + "overlap INTEGER,"
                            + "text TEXT NOT NULL,"
                            + "embedding BLOB NOT NULL,"
                            + "created_at INTEGER NOT NULL"
                            + ")");
            ensureColumn(database, "chunks", "chunk_size", "INTEGER");
            ensureColumn(database, "chunks", "overlap", "INTEGER");
            writeStage("after_sqlite_db");
        }
    }

    private static void ensureColumn(SQLiteDatabase database, String table, String column, String type) {
        try (Cursor cursor = database.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(1))) {
                    return;
                }
            }
        }
        database.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }

    private File dbPath() {
        if (databasePath == null) {
            databasePath = new File(context.getFilesDir(), "pocket-rag.db");
        }
        return databasePath;
    }

    private List<String> chunkText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            chunks.add(text.substring(start, end).trim());
            if (end >= text.length()) break;
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static byte[] floatsToBlob(List<Float> values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (Float value : values) {
            buffer.putFloat(value == null ? 0.0f : value);
        }
        return buffer.array();
    }

    private static float[] blobToFloats(byte[] blob) {
        ByteBuffer buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[blob.length / 4];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getFloat();
        }
        return values;
    }

    private static float cosine(List<Float> left, float[] right) {
        int n = Math.min(left.size(), right.length);
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < n; i++) {
            float a = left.get(i) == null ? 0.0f : left.get(i);
            float b = right[i];
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0f;
        }
        return (float) (dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm)));
    }

    private static final class SearchHit {
        final float score;
        final String text;
        final JSONObject metadata;

        SearchHit(float score, String text, JSONObject metadata) {
            this.score = score;
            this.text = text;
            this.metadata = metadata;
        }
    }

    private void writeStage(String stage) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) {
                dir = context.getFilesDir();
            }
            dir.mkdirs();
            File out = new File(dir, "stage.json");
            try (FileWriter writer = new FileWriter(out, false)) {
                JSONObject json = new JSONObject();
                json.put("stage", stage);
                json.put("time", System.currentTimeMillis());
                writer.write(json.toString(2));
                writer.write("\n");
            }
        } catch (Exception ignored) {
        }
    }
}
