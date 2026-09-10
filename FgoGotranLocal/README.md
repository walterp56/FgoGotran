# FgoGotran Local

FgoGotranLocal 帮助 Windows 用户运行自己的 `llama.cpp` 翻译服务，并把它连接到同一可信 Wi-Fi 下的 FgoGotran Android 应用。

此文件夹只包含轻量控制界面和启动脚本。它**不包含** llama.cpp、GGUF 模型、CUDA 文件、角色语音或 TTS。

## 最快开始

1. 安装 64 位 Python 3.11、3.12 或 3.13。
2. 从 [llama.cpp 官方 Releases](https://github.com/ggml-org/llama.cpp/releases) 下载适合电脑的完整 Windows build。
3. 准备支持日文输入、中文输出的 Instruction/Chat GGUF 模型。
4. 双击 `Start-FgoGotranLocal.cmd`。
5. 浏览器打开后，在“模型设置”选择 `llama-server.exe`、模型文件夹和 GGUF。
6. 保存并启动，等待状态变成“已就绪”。
7. 将页面显示的 Endpoint、Model ID 和 API Key 填入 FgoGotran。

第一次启动会在本文件夹建立私有 `.venv` 并安装控制界面的 Python 依赖，因此需要 Internet。之后启动不需要重新安装。

详细步骤见 [快速设置](docs/QUICK_START.md)。

## 文件放在哪里

llama.cpp 和模型可以放在任何你有权限访问的位置，不必复制进本仓库。例如：

```text
C:\AI\llama.cpp\llama-server.exe
D:\AIModels\fgo-translator.gguf
```

请完整解压 llama.cpp，保留 `llama-server.exe` 同目录的 DLL。不要把 `.gguf`、llama.cpp、`.venv` 或 `user_data` 提交到 Git。

## 页面

- 总览：启动、停止并复制手机连接信息。
- 模型设置：管理 llama.cpp、GGUF 和推理参数。
- 连接测试：验证 OpenAI Chat Completions 兼容性。
- 系统：运行诊断并查看当前会话日志。

## 安全边界

- 管理页面固定监听 `127.0.0.1:18081`，手机不能访问。
- 翻译 API 默认监听 `0.0.0.0:18080`，供可信局域网中的手机连接。
- 翻译 API 使用随机 API Key。
- 不创建 UPnP 或路由器端口转发。
- 日志只保留在当前运行的内存中，不写入磁盘。

只在可信家庭网络使用，并在 Windows 防火墙提示中只允许“专用网络”。参见 [安全说明](SECURITY.md)。

## 开发验证

```powershell
$env:PYTHONPATH = "$PWD\src"
python -m pytest
```

测试不会加载 GGUF 模型。
