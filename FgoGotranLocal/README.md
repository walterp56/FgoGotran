# FgoGotran Local

[Complete Simplified Chinese guide](README.zh-CN.md)

FgoGotran Local is a Windows control interface for running a user-provided `llama.cpp` translation server and connecting it to the FgoGotran Android app over a trusted local network.

This directory contains only the lightweight Gradio control interface, configuration logic, and Windows launcher. It does **not** include llama.cpp, CUDA runtime files, GGUF models, character voices, or TTS components.

## Quick start

1. Install 64-bit Python 3.11, 3.12, or 3.13.
2. Download a complete Windows build from the official [llama.cpp releases](https://github.com/ggml-org/llama.cpp/releases).
3. Download an Instruction or Chat GGUF model that accepts Japanese and produces Chinese.
4. Double-click `Start-FgoGotranLocal.cmd`.
5. In the model settings page, select `llama-server.exe`, the model directory, and the GGUF file.
6. Save the profile, start the service, and wait for the status to become ready.
7. Copy the displayed Endpoint, Model ID, and API Key into FgoGotran.

The first launch creates a private `.venv` and installs the Python dependencies, so Internet access is required once. Later launches reuse the installed environment unless the dependency files change.

See [Quick Start](docs/QUICK_START.md) for the complete setup sequence.

## File placement

llama.cpp and models may remain anywhere the current Windows user can access. They do not need to be copied into this repository. For example:

```text
C:\AI\llama.cpp\llama-server.exe
D:\AIModels\fgo-translator.gguf
```

Extract the complete llama.cpp package and keep all required DLL files beside `llama-server.exe`. Never commit `.gguf` files, llama.cpp binaries, `.venv`, or `user_data`.

## Control interface

The browser interface provides:

- Runtime overview, start/stop controls, and phone connection values.
- Model profiles, llama.cpp paths, network access, and inference parameters.
- An automatic OpenAI Chat Completions startup check plus a manual retest.
- Safe one-time fallback to model-default thinking when forced-off mode returns only an end token.
- Environment diagnostics and in-memory runtime logs.

The current control interface uses Simplified Chinese. The Chinese guide identifies every control by its displayed label.

## Security boundary

- The control interface listens only on `127.0.0.1:18081`; phones cannot open it.
- The translation API normally listens on `0.0.0.0:18080` when trusted-LAN access is selected.
- A random API key protects the translation API.
- The project does not create UPnP rules or router port forwarding.
- Runtime logs stay in memory and redact the API key.

Use LAN mode only on a trusted private network. If Windows Firewall asks for permission, allow only private networks. Read [Security](SECURITY.md) before exposing the inference port to another device.

## Documentation

- [Quick Start](docs/QUICK_START.md)
- [llama.cpp Setup](docs/LLAMA_CPP_SETUP.md)
- [GGUF Model Guide](docs/MODEL_GUIDE.md)
- [Phone Connection](docs/PHONE_CONNECTION.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Security](SECURITY.md)
- [Third-party Components](THIRD_PARTY.md)

## Development verification

```powershell
$env:PYTHONPATH = "$PWD\src"
python -m pytest
```

The test suite does not load a GGUF model.
