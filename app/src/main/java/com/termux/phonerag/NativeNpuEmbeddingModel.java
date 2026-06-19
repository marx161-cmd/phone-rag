package com.termux.phonerag;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class NativeNpuEmbeddingModel {
    static final String EXTERNAL_WORKER_PATH = "/data/local/tmp/embeddinggemma/embeddinggemma_npu_probe";
    private static final String PACKAGED_WORKER_NAME = "libembeddinggemma_npu_worker.so";
    static final String MODEL_PATH = "/data/local/tmp/embeddinggemma/model.tflite";
    static final String TOKENIZER_PATH = "/data/local/tmp/embeddinggemma/sentencepiece.model";
    static final String EXTERNAL_DISPATCH_DIR = "/data/local/tmp/poll-e-worker-test";
    static final String SIGNATURE = "embed_512";

    private final Context context;
    private static final Object WORKER_LOCK = new Object();
    private static Process workerProcess;
    private static PrintWriter workerWriter;
    private static BufferedReader workerReader;

    NativeNpuEmbeddingModel(Context context) {
        this.context = context.getApplicationContext();
    }

    static File workerFile(Context context) {
        File packaged = new File(context.getApplicationInfo().nativeLibraryDir, PACKAGED_WORKER_NAME);
        if (packaged.exists()) {
            return packaged;
        }
        return new File(EXTERNAL_WORKER_PATH);
    }

    static String dispatchDir(Context context) {
        File packagedDispatch = new File(context.getApplicationInfo().nativeLibraryDir, "libLiteRtDispatch_GoogleTensor.so");
        if (packagedDispatch.exists()) {
            return context.getApplicationInfo().nativeLibraryDir;
        }
        return EXTERNAL_DISPATCH_DIR;
    }

    List<Float> embedDocument(String text) throws Exception {
        return embed(text);
    }

    List<Float> embedQuery(String text) throws Exception {
        return embed(text);
    }

    private List<Float> embed(String text) throws Exception {
        File input = File.createTempFile("phonerag-embed-", ".txt", context.getCacheDir());
        File outputFile = File.createTempFile("phonerag-embed-", ".json", context.getCacheDir());
        try {
            try (FileOutputStream out = new FileOutputStream(input)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }

            try {
                return embedWithResidentWorker(input);
            } catch (Exception residentFailure) {
                stopResidentWorker();
            }

            ProcessBuilder builder = new ProcessBuilder(
                    workerFile(context).getAbsolutePath(),
                    "--model_path=" + MODEL_PATH,
                    "--dispatch_dir=" + dispatchDir(context),
                    "--signature=" + SIGNATURE,
                    "--tokenizer_path=" + TOKENIZER_PATH,
                    "--text_file=" + input.getAbsolutePath(),
                    "--output_file=" + outputFile.getAbsolutePath());
            Map<String, String> env = builder.environment();
            env.put("LD_LIBRARY_PATH", dispatchDir(context) + ":/vendor/lib64");
            builder.redirectErrorStream(true);

            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("embedding worker timed out");
            }
            int exit = process.exitValue();
            if (exit != 0 || !outputFile.isFile() || outputFile.length() == 0) {
                throw new IllegalStateException("embedding worker failed exit=" + exit + " output=" + trimOutput(output));
            }

            String jsonLine = new String(Files.readAllBytes(outputFile.toPath()), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonLine);
            JSONArray values = json.getJSONArray("embedding");
            List<Float> embedding = new ArrayList<>(values.length());
            for (int i = 0; i < values.length(); i++) {
                embedding.add((float) values.getDouble(i));
            }
            return embedding;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            input.delete();
            //noinspection ResultOfMethodCallIgnored
            outputFile.delete();
        }
    }

    private synchronized List<Float> embedWithResidentWorker(File input) throws Exception {
        synchronized (WORKER_LOCK) {
            ensureResidentWorker();
            workerWriter.println(input.getAbsolutePath());
            workerWriter.flush();

            long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
            String line;
            while (System.currentTimeMillis() < deadline && (line = workerReader.readLine()) != null) {
                if (line.startsWith("EMBEDDING_JSON:")) {
                    return parseEmbeddingJson(line.substring("EMBEDDING_JSON:".length()));
                }
                if (line.startsWith("EMBEDDING_ERROR:")) {
                    throw new IllegalStateException(line);
                }
            }
            throw new IllegalStateException("resident embedding worker timed out");
        }
    }

    private void ensureResidentWorker() throws Exception {
        if (workerProcess != null && workerProcess.isAlive() && workerWriter != null && workerReader != null) {
            return;
        }
        stopResidentWorker();
        ProcessBuilder builder = new ProcessBuilder(
                workerFile(context).getAbsolutePath(),
                "--model_path=" + MODEL_PATH,
                "--dispatch_dir=" + dispatchDir(context),
                "--signature=" + SIGNATURE,
                "--tokenizer_path=" + TOKENIZER_PATH,
                "--worker");
        Map<String, String> env = builder.environment();
        env.put("LD_LIBRARY_PATH", dispatchDir(context) + ":/vendor/lib64");
        builder.redirectErrorStream(true);
        workerProcess = builder.start();
        workerWriter = new PrintWriter(workerProcess.getOutputStream(), true);
        workerReader = new BufferedReader(new InputStreamReader(workerProcess.getInputStream(), StandardCharsets.UTF_8));

        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        StringBuilder startup = new StringBuilder();
        String line;
        while (System.currentTimeMillis() < deadline && (line = workerReader.readLine()) != null) {
            startup.append(line).append('\n');
            if (line.contains("EMBEDDING_READY")) {
                return;
            }
        }
        throw new IllegalStateException("resident embedding worker failed to start: " + trimOutput(startup));
    }

    void stopResidentWorker() {
        synchronized (WORKER_LOCK) {
            stopResidentWorkerLocked();
        }
    }

    static boolean isResidentWorkerAlive() {
        synchronized (WORKER_LOCK) {
            return workerProcess != null && workerProcess.isAlive();
        }
    }

    private static void stopResidentWorkerLocked() {
        try {
            if (workerWriter != null) workerWriter.close();
        } catch (Exception ignored) {
        }
        try {
            if (workerReader != null) workerReader.close();
        } catch (Exception ignored) {
        }
        if (workerProcess != null) {
            workerProcess.destroyForcibly();
        }
        workerProcess = null;
        workerWriter = null;
        workerReader = null;
    }

    private static List<Float> parseEmbeddingJson(String jsonLine) throws Exception {
        JSONObject json = new JSONObject(jsonLine);
        JSONArray values = json.getJSONArray("embedding");
        List<Float> embedding = new ArrayList<>(values.length());
        for (int i = 0; i < values.length(); i++) {
            embedding.add((float) values.getDouble(i));
        }
        return embedding;
    }

    private static String trimOutput(StringBuilder output) {
        String text = output.toString();
        if (text.length() <= 4000) {
            return text;
        }
        return text.substring(text.length() - 4000);
    }
}
