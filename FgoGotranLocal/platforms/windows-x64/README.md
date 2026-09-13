# Windows x64 platform

This platform package supports 64-bit editions of Windows 10 and Windows 11 on x64 processors.

The launcher prefers an official NVIDIA CUDA build of llama.cpp when `nvidia-smi` reports a compatible driver. If no complete compatible CUDA package is available, it safely falls back to the official Windows x64 CPU build.

Run `Start-FgoGotranLocal.cmd` from the project root. Do not run the platform scripts directly. This directory owns the x64 Python bootstrap, llama.cpp runtime selection, and diagnostics. Python application code, the Gradio interface, configuration handling, and documentation remain shared at the project root.

Downloaded runtimes are stored under `user_data/runtime/windows-x64`. GGUF models are always selected and managed by the user.
