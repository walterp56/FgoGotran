# Platform packages

Platform packages contain only the bootstrap, runtime-selection and diagnostic logic that depends on an operating system or processor architecture. The Python service, Gradio interface, configuration schema and documentation remain shared at the project root.

Each platform directory must provide a versioned `platform.json` manifest and every script named by that manifest. The root launcher is responsible for detecting the native platform and refusing an incompatible package with a clear error.

Currently supported:

- `windows-x64`: Windows 10/11 x64, with NVIDIA CUDA preferred and CPU fallback.

Downloaded runtimes belong under `user_data/runtime/<platform-id>`. Models remain user-managed and are not part of a platform package.
