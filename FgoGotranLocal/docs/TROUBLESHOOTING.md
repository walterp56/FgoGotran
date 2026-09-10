# 故障排查

## 找不到 Python

安装 64 位 Python 3.11–3.13，然后重新双击 `Start-FgoGotranLocal.cmd`。也可设置环境变量 `FGO_LOCAL_PYTHON` 指向兼容的 `python.exe`。

## Python 依赖安装失败

确认电脑可以访问 PyPI、系统时间正确，并重新运行启动 CMD。不要从未知压缩包复制别人创建的 `.venv`。

## llama-server 路径无效

- 选择名为 `llama-server.exe` 的文件。
- 保留官方压缩包的 DLL。
- 不要从压缩包内部直接运行。

## 找不到 GGUF

- 模型必须以 `.gguf` 结尾。
- 模型必须位于设置的“GGUF 模型文件夹”内。
- 点击“扫描 GGUF”重新载入列表。

## 模型加载后立即退出

查看“系统 → 运行日志”。常见原因是显存不足、llama.cpp build 与显卡不匹配，或 GGUF 损坏。先关闭其他 GPU 程序、降低 Context Size，或改用更小量化。

## llama-server 不支持关闭模型思考

更新到 llama.cpp 官方近期 Release 并完整解压，然后重新选择新的 `llama-server.exe`。如果当前模型不需要关闭思考，也可以取消勾选“关闭模型思考”。

如果启用后 API 返回 HTTP 200，但 `content` 为空且只生成一个 Token，请取消勾选并重新启动模型服务。Sakura-14B-Qwen3-v1.5 应保持此选项关闭。

## 18080 或 18081 端口已占用

停止占用端口的旧 FgoGotranLocal/llama-server。管理端口可在启动前设置 `FGO_LOCAL_CONTROL_PORT`；翻译端口可在 Profile 中修改。

## 手机无法连接

按 [手机连接](PHONE_CONNECTION.md) 的顺序检查。不要使用路由器地址，也不要把管理页面的 `18081` 当成翻译 API。
