# Native Worker

`embeddinggemma_npu_worker.cc` is the LiteRT-LM based native worker used by the
Android app.

It supports two modes:

- one-shot probe mode for direct smoke tests
- resident `--worker` mode for Phone RAG, where the process loads the
  EmbeddingGemma Tensor G5 model once, prints `EMBEDDING_READY`, then reads text
  file paths from stdin and emits `EMBEDDING_JSON:{...}` per request

Build it inside a LiteRT-LM checkout with the same dependencies used for the
Google Tensor dispatch probe, then package the resulting arm64 binary as:

```text
app/src/main/jniLibs/arm64-v8a/libembeddinggemma_npu_worker.so
```

The checked-in Android app does not commit the built `.so` or Google Tensor
runtime libraries. Those must be provisioned locally according to their
upstream licenses.
