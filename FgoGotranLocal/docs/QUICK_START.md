# 快速设置

## 1. 准备 Python

安装 64 位 Python 3.11、3.12 或 3.13。安装程序中建议勾选“Add Python to PATH”。

如果电脑有多个 Python，可以在 PowerShell 中指定：

```powershell
$env:FGO_LOCAL_PYTHON = 'C:\Path\To\python.exe'
```

## 2. 下载 llama.cpp

1. 打开 [llama.cpp 官方 Releases](https://github.com/ggml-org/llama.cpp/releases)。
2. NVIDIA 显卡选择最新的 Windows x64 CUDA build；无 NVIDIA 显卡可选择 Windows x64 CPU 或 Vulkan build。
3. 如果该 Release 把 CUDA runtime DLL 分成另一个压缩包，也一并下载。
4. 将相关压缩包完整解压到同一文件夹。
5. 确认文件夹中有 `llama-server.exe`，并保留全部 DLL。

不要只复制一个 `llama-server.exe`。

## 3. 准备 GGUF 模型

选择支持日文输入、中文输出的 Instruction/Chat 模型，并下载它的 GGUF 量化版本。第一次可从 Q4_K_M 开始；模型越大，通常需要越多显存和加载时间。

把模型放入专用文件夹，例如：

```text
D:\AIModels\FgoGotran\model.gguf
```

## 4. 启动控制界面

双击根目录的 `Start-FgoGotranLocal.cmd`。第一次运行会：

1. 检查兼容的 Python。
2. 创建 `.venv` 私有环境。
3. 安装或更新轻量控制依赖。
4. 检查 GPU 和已有设置。
5. 打开 `http://127.0.0.1:18081`。

命令窗口必须保持开启。

## 5. 设置并启动模型

在“模型设置”填写：

1. 完整的 `llama-server.exe` 绝对路径。
2. GGUF 模型文件夹。
3. 扫描并选择 GGUF 模型。
4. 保持默认 Model ID，或设置一个简单稳定的英文 ID。
5. 需要手机连接时选择“可信局域网 + 本机”。

保存后回到“总览”，点击“启动服务”。首次加载完成前会显示“正在加载模型”。

## 6. 验证和连接手机

状态变成“已就绪”后：

1. 打开“连接测试”，运行兼容性测试。
2. 返回“总览”，复制 Endpoint、Model ID 和 API Key。
3. 在 FgoGotran 的本地 AI 设置中填写这三项。

更完整的网络检查见 [手机连接](PHONE_CONNECTION.md)。
