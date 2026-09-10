# Changelog

## 0.1.0

- Extracted the local llama.cpp translation runtime into `FgoGotranLocal`.
- Added a translation-only Gradio control interface.
- Added one user-facing Windows launcher that checks and prepares the environment before starting.
- Added model, llama.cpp, phone connection, troubleshooting and security guides.
- Excluded voice profiles, GPT-SoVITS and all TTS dependencies.
- Added opt-in thinking control with llama.cpp `--reasoning off` and a capability-checked fallback for older builds.
- Kept thinking control off by default because some translation fine-tunes, including Sakura-14B-Qwen3-v1.5, return empty output when it is forced off.
