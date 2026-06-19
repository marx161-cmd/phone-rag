# Phone RAG

Small Android localhost vector service for phone-side personal knowledge.

This is homelab research code published for people who want to inspect, reuse,
or adapt the approach. Use it freely, but do not expect maintained releases,
support, or compatibility guarantees.

It uses a native LiteRT worker for EmbeddingGemma:

- Tensor G5 AOT EmbeddingGemma through LiteRT `CompiledModel`.
- Packaged resident `libembeddinggemma_npu_worker.so` for text-to-vector calls.
- Packaged `libLiteRtDispatch_GoogleTensor.so` plus `libedgetpu_litert.so`.
- Android's built-in SQLite for persistent vectors.
- Brute-force cosine search over 768-dimensional float blobs.

The old `localagents-rag` `GemmaEmbeddingModel` path is not used; it could not
initialize the Tensor G5 model. Storage is kept in plain app-owned SQLite.

## Credits

- Google AI Edge / LiteRT and LiteRT-LM for the Android runtime stack.
- Google's EmbeddingGemma model family for the embedding model.
- Android / Termux tooling for the rooted phone-side bridge.

Native Google Tensor dispatch libraries, Edge TPU/LiteRT vendor binaries,
EmbeddingGemma model files, and locally built worker binaries are not committed
to this repository. Provision them locally and follow the licenses attached to
those upstream artifacts. The native worker source is kept under `native/`.

Runtime API:

```text
GET  http://127.0.0.1:8791/health
POST http://127.0.0.1:8791/index  {"text":"...","title":"...","source":"..."}
POST http://127.0.0.1:8791/query  {"query":"...","top_k":6}
```

Indexing defaults to 1800 character chunks with 180 characters of overlap.
Pass `chunk_size`, `overlap`, and `replace_source` in the JSON body to override
that. `replace_source=true` is the default, so reindexing the same source path
replaces its old chunks instead of duplicating them.

Expected model files on the phone:

```text
/data/local/tmp/embeddinggemma/model.tflite
/data/local/tmp/embeddinggemma/sentencepiece.model
```

Build/install:

```bash
./gradlew :app:assembleDebug
adb -s 100.69.13.12:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

Before building a fully self-contained APK, place the locally built/provisioned
native runtime files under:

```text
app/src/main/jniLibs/arm64-v8a/libembeddinggemma_npu_worker.so
app/src/main/jniLibs/arm64-v8a/libLiteRtDispatch_GoogleTensor.so
app/src/main/jniLibs/arm64-v8a/libedgetpu_litert.so
```

If those files are absent, the app falls back to the external worker/runtime
paths documented in `NativeNpuEmbeddingModel.java`.

Fetch/push model:

```bash
scripts/fetch_embeddinggemma.sh
scripts/push_embeddinggemma_to_pixel.sh
```

The push script defaults to the generic seq512 LiteRT model because it works on
the current ROM. Override `EMBEDDINGGEMMA_TFLITE` to test Tensor G5-specific
models later.

For the NPU path, the active model should be:

```text
/data/local/tmp/embeddinggemma/model.tflite
```

with the Tensor G5 AOT artifact. `phonerag health` should report:

```text
backend=litert_compiled_model_google_tensor_npu_worker
worker_can_execute=true
dispatch_dir=<app native lib dir>
```

Start and forward the local API:

```bash
scripts/start_phone_rag.sh
```

Phone-native Termux steering:

```bash
scp -P 8022 scripts/termux_phonerag.sh u0_a464@100.69.13.12:~/bin/phonerag
ssh -p 8022 u0_a464@100.69.13.12 'chmod +x ~/bin/phonerag'
```

Then, from Termux on the phone:

```bash
phonerag start
phonerag health
phonerag query "what did I save about this topic?" 6
phonerag index /path/to/file.txt "Title" "source"
phonerag index-dir --chunk-size 1800 --overlap 180 ~/notes ~/downloads/saved-context
phonerag index-dir --chunk-size 2400 --overlap 240 --append ~/archive
phonerag index-paths ~/rag-paths.txt
```

This path does not use AMD-side `adb forward` or the APK HTTP listener. It uses
Android intents to steer `PhoneRagService` and reads
`/sdcard/Android/data/com.termux.phonerag/files/last-result.json`. Because
Android blocks normal Termux access to another app's `Android/data` directory,
the script uses `su -c` only for result/inbox file handoff.

`index-dir` walks files on the phone, skips common noisy directories
(`.git`, `node_modules`, `build`, `.gradle`, `__pycache__`), filters to likely
text/code/document files under 2 MB, and indexes sequentially. Sequential
indexing is intentional: it keeps the NPU worker and foreground service on one
job at a time instead of heating the phone with parallel embedding work.

The native embedding process is kept resident inside the app process after the
first embedding request. The first index/query still pays LiteRT startup and
delegate setup; later chunks and follow-up requests reuse the warm worker while
Android keeps the Phone RAG process alive.

Use `phonerag start` before a larger batch if you want the foreground service
up first. It starts the service and opens the small app UI best-effort.

Index/query from the host:

```bash
scripts/index_text.sh /path/to/article.txt "Article title" "source-url-or-path"
scripts/query.sh "what did I save about this topic?"
```

The SQLite DB lives in the app private files directory as `pocket-rag.db`.
On the rooted Pixel it can be copied directly if needed.

Clear the DB:

```bash
scripts/clear_db.sh
```
