# llama.cpp 设置

FgoGotranLocal 不下载、修改或更新 llama.cpp。

1. 只从 [ggml-org/llama.cpp 官方 Releases](https://github.com/ggml-org/llama.cpp/releases) 下载。
2. Windows + NVIDIA 用户选择 Windows x64 CUDA build。
3. CPU 或其他显卡用户选择与硬件相符的 build。
4. 完整解压所有文件；某些 Release 会把 CUDA runtime DLL 放在单独压缩包中，需要解压到同一个目录。
5. 在“模型设置”选择真正的 `llama-server.exe`。

升级时建议解压到新的版本文件夹，测试正常后再删除旧版本。不要覆盖正在运行的目录。
