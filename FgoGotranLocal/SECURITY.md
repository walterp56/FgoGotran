# 安全说明

FgoGotranLocal 的管理页面只监听 `127.0.0.1`。翻译 API 可按 Profile 选择仅本机或可信局域网。

## 默认保护

- 首次生成随机 API Key，并通过 llama.cpp 的 API Key 文件传递。
- 管理 API 拒绝非本机 Host、跨来源写操作和过大的 JSON 请求。
- 不启用 llama.cpp 自带 Web UI。
- llama.cpp 使用 offline 模式，不主动下载模型。
- 配置写入 `user_data/config.json`，更新前保留一个备份。
- API Key 临时文件在 llama-server 停止后删除。
- 运行日志仅保存在当前进程内存，并会遮盖 API Key。
- 不创建 UPnP、路由器端口转发或公网监听。

## 用户责任

- 只从可信来源下载 llama.cpp 和 GGUF，并核对发布者提供的校验值。
- 只在可信家庭网络使用局域网模式。
- Windows 防火墙只允许专用网络。
- 不要分享 API Key、`user_data` 或包含个人内容的诊断信息。
- 不要把路由器端口转发到 18080 或 18081。

如果 API Key 可能泄露，先停止 llama-server，再在“总览”更换 Key，并同步更新手机。
