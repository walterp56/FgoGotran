# Changelog

## 0.1.0

- Extracted the local llama.cpp translation runtime into `FgoGotranLocal`.
- Added a translation-only Gradio control interface.
- Added one user-facing Windows launcher that checks and prepares the environment before starting.
- Added model, llama.cpp, phone connection, troubleshooting and security guides.
- Excluded voice profiles, GPT-SoVITS and all TTS dependencies.
- Added opt-in thinking control with llama.cpp `--reasoning off` and a capability-checked fallback for older builds.
- Kept thinking control off by default because some translation fine-tunes, including Sakura-14B-Qwen3-v1.5, return empty output when it is forced off.
- Added an automatic startup Chat Completions probe before the server is marked ready.
- Added one-time recovery to model-default thinking when forced-off mode returns only an end token, with a model/runtime-scoped compatibility cache.
- Masked endpoints, LAN addresses, API keys and repeated local paths by default, with temporary per-section reveal controls.
- Added an optional verified first-run bootstrap for private Python and an official driver-compatible llama.cpp Windows runtime.
- Kept GGUF acquisition and selection entirely user-managed; the launcher has no model-download path.
- Added managed llama.cpp configuration adoption without overwriting existing user paths, plus automatic startup of a valid user-configured active model.
- Detect missing or invalid saved llama-server paths and offer a verified replacement while preserving every model setting and a config backup.
- Keep launcher-generated Python path and JSON files BOM-free for compatibility with the Windows PowerShell 5.1 entry point.
- Separated Windows x64 setup, runtime selection and diagnostics into `platforms/windows-x64`, with a manifest-driven root launcher and compatibility for existing managed runtimes.
