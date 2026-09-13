# llama.cpp Setup

FgoGotran Local does not bundle llama.cpp. On first start, the `platforms/windows-x64` package can download and install a verified official Windows x64 release into `user_data/runtime/windows-x64/llama.cpp`; manual runtimes remain fully supported. Existing valid runtimes from the former `user_data/runtime/llama.cpp` location continue to work.

## Automatic setup

The launcher queries official `ggml-org/llama.cpp` GitHub releases and accepts only complete Windows assets carrying SHA-256 metadata from GitHub. When `nvidia-smi` is available, it chooses the newest CUDA asset that is not newer than the CUDA compatibility level reported by the driver and downloads its matching CUDA runtime archive. Otherwise it uses the official CPU x64 build.

Archives are downloaded resumably, checked for exact size and SHA-256, extracted through a staging directory, and adopted only after `llama-server.exe` is present. Existing non-empty configured paths are never replaced. The launcher does not install or update an NVIDIA driver.

Set `FGO_LOCAL_AUTO_SETUP=0` before launch to keep setup manual.

## Manual setup

## Installation

1. Download only from the official [ggml-org/llama.cpp releases](https://github.com/ggml-org/llama.cpp/releases), unless you have independently verified another build publisher.
2. Windows systems with NVIDIA GPUs should normally use a Windows x64 CUDA build.
3. CPU-only systems and other GPUs should use a backend compatible with their hardware.
4. Extract the complete package. Some releases provide the CUDA runtime DLLs in a separate archive; extract those files into the same runtime directory.
5. Verify that `llama-server.exe` starts from its extracted directory and that its companion DLL files remain beside it.
6. Select the actual `llama-server.exe` file in the FgoGotran Local model settings page.

Example layout:

```text
C:\AI\llama.cpp\
  llama-server.exe
  ggml*.dll
  llama*.dll
  cudart*.dll
  cublas*.dll
```

The exact DLL list varies by release. Preserve all files supplied by the selected build.

## Updating llama.cpp

1. Stop the managed llama-server.
2. Extract the new release into a new directory.
3. Update the executable path in FgoGotran Local.
4. Save the profile and start the service.
5. Run the compatibility test.
6. Keep the old runtime until the new build has passed loading and translation tests.

Do not overwrite a runtime directory while llama-server is running.

## Thinking-control compatibility

When the optional thinking-disable control is enabled, FgoGotran Local checks `llama-server --help` before startup. It prefers `--reasoning off` and uses `--chat-template-kwargs` only as a compatibility fallback. If neither option is supported, startup stops with an actionable error instead of silently ignoring the setting.

After `/health` becomes ready, FgoGotran Local sends a short Chat Completions probe before reporting the model as ready. If forced-off mode returns HTTP 200 but only an end token and empty content, the managed server is restarted once with the model's default thinking behavior and tested again. Other empty, invalid, or failed responses are reported as compatibility errors and do not trigger a restart.

The result for forced-off mode is cached using a fingerprint of the GGUF and llama-server files. A known-incompatible pair uses model-default behavior on later starts. Replacing or updating either file automatically causes a fresh check. The cache contains hashes and results only; it does not contain the API key or file paths.

Update llama.cpp if the selected model requires a control option that the current runtime does not provide.
