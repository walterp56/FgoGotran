# Quick Start

## Automatic first start

Double-click `Start-FgoGotranLocal.cmd` and keep the command window open.

When required, the launcher asks before downloading external components. Press Enter to accept the displayed default or enter `n` to keep that component manual.

1. A compatible installed 64-bit Python 3.11–3.13 is reused. If none exists, the launcher can download the signed Python 3.13.15 installer from `python.org` and install it privately under `user_data/runtime/windows-x64` without changing `PATH` or file associations. An existing valid runtime in the former shared location remains usable.
2. `.venv` is created and the Python UI dependencies are installed.
3. If llama.cpp is not configured, the launcher can obtain an official Windows x64 release. An NVIDIA CUDA build compatible with the driver is preferred; the official CPU build is the fallback.
4. Download a compatible GGUF model yourself from a trusted publisher and select it in the control interface.
5. Downloaded Python and llama.cpp files are checked against their official signature or size/SHA-256 metadata before use.
6. The browser opens `http://127.0.0.1:18081`. An already complete active profile starts automatically.

Automatic setup never downloads or manages GGUF models and never overwrites a valid user-configured runtime or model path. If a saved llama-server path points to a missing file, the launcher asks before installing a replacement and updates only that stale setting. A failed llama.cpp download does not prevent the control interface from opening.

## Manual setup remains supported

You can decline automatic llama.cpp setup and configure your own runtime on the **Model settings** page. Model setup is always manual:

1. Select the complete `llama-server.exe` from a trusted llama.cpp build.
2. Select the directory containing your GGUF models.
3. Scan and choose a Japanese-to-Chinese Instruction/Chat GGUF.
4. Confirm the Model ID and network mode.
5. Save the active profile and start the model.

Keep all DLLs supplied with `llama-server.exe`; copying only the executable is insufficient.

## Connect FgoGotran

After the status becomes **Ready**:

1. Reveal the endpoint and API key on the overview page.
2. In FgoGotran, select the custom/local API provider.
3. Copy the endpoint, Model ID, and API key exactly.
4. Use `http://<PC-LAN-IP>:18080/health` in the phone browser to test basic LAN access.

The PC and phone must be on the same trusted private network. Never expose the inference or control ports through router port forwarding.

## Non-interactive choices

```powershell
$env:FGO_LOCAL_AUTO_SETUP = '1'                  # accept automatic component setup
$env:FGO_LOCAL_AUTO_START_MODEL = '0'            # keep model startup manual
.\Start-FgoGotranLocal.cmd
```

Use `FGO_LOCAL_AUTO_SETUP=0` to disable the optional Python/llama.cpp downloads. It never enables model downloading. See [Troubleshooting](TROUBLESHOOTING.md) if setup stops or the server does not become ready.
