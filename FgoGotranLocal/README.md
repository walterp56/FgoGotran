# FgoGotran Local

[简体中文完整使用指南](简体中文使用指南.md)

FgoGotran Local is a Windows control interface for running a local `llama.cpp` translation server and connecting it to the FgoGotran Android app over a trusted local network.

The repository contains only the lightweight launcher, Gradio control interface, configuration logic, and documentation. Python, llama.cpp, and CUDA runtime files can be downloaded to ignored local directories only after first-run confirmation; they are never committed or redistributed in this folder. GGUF models are always obtained and selected by the user.

## Quick start

Double-click `Start-FgoGotranLocal.cmd`. On a new PC, the launcher can:

1. Use an existing 64-bit Python 3.11–3.13, or offer to install an isolated Python 3.13.15 runtime under `user_data`.
2. Create `.venv` and install the control-interface dependencies.
3. Load the `platforms/windows-x64` package, detect the NVIDIA driver, and download a compatible official llama.cpp CUDA build with a CPU fallback.
4. Verify the Python publisher signature and the official size/SHA-256 metadata before using downloads.
5. Create the local configuration and open `http://127.0.0.1:18081`.
6. Start an existing valid active model automatically, or wait for the user to configure one.

Press Enter at the setup questions to accept the default. Existing valid llama.cpp and model paths are never replaced. If a saved llama-server path no longer exists, the launcher asks before installing a replacement and updates only that stale path; model settings remain unchanged. If automatic llama.cpp setup fails, the control interface still opens when Python is ready, allowing manual configuration. The launcher never downloads, updates, replaces, or deletes a GGUF model.

## Managed files

Automatic setup stores its files in locations already excluded by `.gitignore`:

```text
.venv\
user_data\downloads\
user_data\runtime\windows-x64\python-3.13.15\
user_data\runtime\windows-x64\llama.cpp\
user_data\config.json
```

It does not modify the system `PATH` or file associations, install a GPU driver, change router settings, open firewall rules, or expose the control interface to the LAN. The official Python installer may create its normal per-user uninstall entry. User-managed models may remain elsewhere on the computer, or in an ignored directory chosen by the user.

The overview, diagnostics, startup report, and runtime logs do not display hardware inventory or system details. Logs permanently redact API keys, IP addresses, and local paths. Endpoint and API Key fields remain masked by default; their existing copy icons copy the real value without revealing it on screen. User-selected runtime and model paths remain visible only in the model settings form where they are required for configuration.

## Phone connection

- Control UI: `http://127.0.0.1:18081` — PC only.
- Translation API: `http://<PC-LAN-IP>:18080/v1/chat/completions` — phone and PC when the active profile uses trusted-LAN mode.
- Health check: `http://<PC-LAN-IP>:18080/health`.

Use LAN mode only on a trusted private network. Allow the inference process only on the Windows private-network profile and never forward ports `18080` or `18081` on the router.

## Advanced launcher controls

Set these variables in PowerShell before launching when needed:

```powershell
# Accept or decline the optional Python/llama.cpp setup questions.
$env:FGO_LOCAL_AUTO_SETUP = '1'       # use '0' to disable automatic downloads

# Keep the control UI running but do not start the active model automatically.
$env:FGO_LOCAL_AUTO_START_MODEL = '0'

# Select an existing compatible Python explicitly.
$env:FGO_LOCAL_PYTHON = 'C:\Path\To\python.exe'

.\Start-FgoGotranLocal.cmd
```

The automatic runtime selector uses the newest complete official llama.cpp release whose Windows asset includes GitHub SHA-256 metadata. For NVIDIA, it never selects a CUDA package newer than the maximum CUDA version reported by `nvidia-smi`; otherwise it uses the official CPU x64 build.

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

The tests do not download Python, llama.cpp, or CUDA files. The launcher has no GGUF download path.
