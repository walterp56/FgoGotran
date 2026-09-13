# Quick Start

## 1. Install Python

Install 64-bit Python 3.11, 3.12, or 3.13. Enabling **Add Python to PATH** during installation is recommended.

If more than one Python installation exists, select one for the current PowerShell session before launching:

```powershell
$env:FGO_LOCAL_PYTHON = 'C:\Path\To\python.exe'
```

## 2. Download llama.cpp

1. Open the official [llama.cpp releases](https://github.com/ggml-org/llama.cpp/releases).
2. For an NVIDIA GPU, choose a current Windows x64 CUDA build compatible with the installed driver.
3. Without an NVIDIA GPU, choose an appropriate Windows x64 CPU or Vulkan build.
4. If the release provides CUDA runtime DLLs in another archive, download that archive too.
5. Extract every required archive into the same directory.
6. Confirm that `llama-server.exe` and its DLL files are present.

Do not copy or run only `llama-server.exe` by itself.

## 3. Prepare a GGUF model

Choose an Instruction or Chat model that supports Japanese input and Chinese output, then download a GGUF quantization. `Q4_K_M` is a practical first test because it balances memory usage and quality.

Store models in a dedicated directory, for example:

```text
D:\AIModels\FgoGotran\model.gguf
```

## 4. Start the control interface

Double-click `Start-FgoGotranLocal.cmd` in the project root. On first launch it will:

1. Find a supported Python installation.
2. Create the private `.venv` environment.
3. Install or update the lightweight control dependencies.
4. Run an environment check.
5. Open `http://127.0.0.1:18081`.

Keep the command window open while using local translation.

## 5. Configure and start the model

In the model settings page:

1. Enter the absolute path to `llama-server.exe`.
2. Enter the directory containing the GGUF files.
3. Scan and select the desired GGUF model.
4. Keep the generated Model ID or enter a short, stable ASCII identifier.
5. Select trusted-LAN access when a phone must connect; otherwise select loopback-only access.
6. Keep the inference defaults for the first test.
7. Save the profile and return to the overview page.
8. Start the service and wait for loading and the automatic compatibility check to finish.

The initial model load may take some time. The service is marked ready only after a short Chat Completions probe returns usable text. If forced thinking control is incompatible, FgoGotran Local may restart the managed model once with its default behavior. Saving a changed runtime profile does not modify an already running llama-server; restart it to apply the changes.

## 6. Test the API

Startup performs this check automatically. You can repeat it from the connection test page. It checks that:

- llama-server is ready.
- The API key and Model ID are accepted.
- The OpenAI Chat Completions response shape is usable.
- The returned text is non-empty.

This test validates connectivity and format, not final translation quality.

## 7. Connect FgoGotran

From the overview page, click the reveal controls and copy:

- Complete Endpoint
- Model ID
- Complete API Key

The reveal state is temporary and resets when the control page is reloaded.

In the Android app, select the custom/local OpenAI-compatible backend and enter the same three values. The Endpoint must include `/v1/chat/completions`.

For phone and firewall checks, continue with [Phone Connection](PHONE_CONNECTION.md).
