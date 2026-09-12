# Troubleshooting

## Python was not found

Install 64-bit Python 3.11–3.13 and run `Start-FgoGotranLocal.cmd` again. If several installations exist, set `FGO_LOCAL_PYTHON` to the desired `python.exe` before launching.

## Python dependency installation failed

Confirm that the PC can reach PyPI, Windows date and time are correct, and security software is not blocking Python. Run the launcher again. Do not copy another person's `.venv` from an untrusted archive.

## The control port is already in use

The default control port is `18081`. Close the previous FgoGotran Local process, or set another port before starting:

```powershell
$env:FGO_LOCAL_CONTROL_PORT = '18082'
.\Start-FgoGotranLocal.cmd
```

## The llama-server path is invalid

- Select a file named `llama-server.exe`.
- Use an absolute path.
- Extract the full official archive.
- Keep every required DLL in the runtime directory.
- Do not run the executable from inside a compressed archive.

## No GGUF model appears

- The file name must end in `.gguf`.
- The configured model directory must exist and be an absolute path.
- The model must be inside that directory or one of its subdirectories.
- Rescan the model directory after adding files.
- A custom absolute model path inside the configured model directory may be entered if needed.

The scanner intentionally limits how many filesystem entries and results it returns.

## The model exits during loading

Open the system page and inspect the runtime log. Common causes include:

- Insufficient VRAM or system memory.
- A llama.cpp build that does not match the GPU or driver.
- Missing CUDA/runtime DLL files.
- A damaged or incomplete GGUF file.
- Unsupported model architecture or chat template.
- Incompatible Flash Attention support.

Close other GPU applications and reduce Context Size. If the failure remains, use a smaller quantization, switch Flash Attention to automatic or off, or update llama.cpp.

## The server remains in loading state

Large models can take time to load. Check whether GPU memory use is increasing and inspect the runtime log. If `/health` never becomes successful, the final llama-server log lines usually identify the problem.

## Thinking control is unsupported

Install a recent official llama.cpp release and select its new `llama-server.exe`. Alternatively, disable the thinking-control option when the model does not require it.

If forced-off mode returns HTTP 200 with empty `content` and only an end token, FgoGotran Local automatically restarts the managed server once with model-default behavior. The overview shows the requested and effective thinking state separately. A successful fallback is cached for that GGUF and llama-server file pair.

If the fallback also fails, the service remains in the error state instead of restarting repeatedly. Leave forced thinking control disabled, inspect the runtime log, and confirm that the model's chat template works with the selected llama.cpp build.

## The compatibility test fails

Startup must pass the same Chat Completions check before the service becomes ready. Verify that the active profile's Model ID matches the alias used by llama-server. Inspect the displayed compatibility result and runtime log for authentication, template, reasoning-only, or empty-output failures. The request timeout is 45 seconds.

## The phone cannot connect

Follow [Phone Connection](PHONE_CONNECTION.md) in order. Do not use the router address and do not use control port `18081` as the translation API port.

## FgoGotran reports cleartext HTTP is not permitted

Install a FgoGotran build that supports trusted-LAN local AI. Use a numeric private-LAN address such as `192.168.x.x`; host names and public HTTP endpoints are intentionally rejected.

## FgoGotran reports an API-key error

Reveal and copy the complete API key from the overview page. A rotated key invalidates the old value immediately. Avoid leading or trailing spaces.

## Changes do not take effect

Saving updates the profile, but runtime settings belong to the currently running llama-server process. Restart the service after changing the model, model ID, port, host, context, GPU, batch, thinking, cache, metrics, or slots settings.

## Reset without immediately deleting data

1. Stop llama-server and close the control window.
2. Rename `user_data` to a backup name.
3. Launch FgoGotran Local to generate a clean configuration.
4. Restore individual settings only after confirming the new configuration works.

Never publish the backup because it contains the API key.
