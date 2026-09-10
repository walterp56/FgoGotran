# FgoGotran Local 完整使用指南

[English README](README.md)

FgoGotran Local 用于在 Windows 电脑上管理用户自行下载的 `llama.cpp` 和 GGUF 翻译模型，并通过兼容 OpenAI Chat Completions 的本地接口连接 FgoGotran Android 应用。

本项目只提供轻量的 Gradio 管理页面、配置管理和 Windows 启动脚本，不包含以下大型或第三方组件：

- llama.cpp 可执行文件和 DLL
- CUDA Runtime
- GGUF 模型
- GPT-SoVITS、角色语音或其他 TTS 组件
- 云端 API Key

## 1. 工作流程

完整流程如下：

1. 在电脑安装兼容的 Python。
2. 从官方渠道下载并完整解压 llama.cpp。
3. 准备能够进行日文到中文翻译的 GGUF 模型。
4. 启动 FgoGotran Local 管理页面。
5. 建立并保存模型 Profile。
6. 启动 llama-server 并等待模型就绪。
7. 运行兼容性测试。
8. 将 Endpoint、Model ID 和 API Key 填入手机上的 FgoGotran。
9. 手机通过可信局域网调用电脑上的模型进行翻译。

## 2. 安全边界

FgoGotran Local 将管理页面和翻译接口分开：

- 管理页面固定使用 `127.0.0.1:18081`，只能由当前电脑访问。
- 翻译接口默认使用 `0.0.0.0:18080`，可供同一可信局域网中的手机访问。
- 翻译接口使用自动生成的随机 API Key。
- 不会建立 UPnP 或路由器端口转发。
- llama.cpp 以 offline 模式启动，不会主动下载模型。
- llama.cpp 自带的 Web UI 被关闭。
- 运行日志只保存在当前进程内存，并遮盖 API Key。

局域网 HTTP 没有传输加密，因此只能在可信家庭网络或可信专用网络中使用。不要在公共 Wi-Fi 使用，不要把 `18080` 或 `18081` 端口映射到公网。

## 3. 系统准备

### 3.1 基本要求

- Windows 10 或 Windows 11
- 64 位 Python 3.11、3.12 或 3.13
- 足够存放 GGUF 的磁盘空间
- 与目标模型匹配的内存或显存
- 首次安装 Python 依赖时可访问 Internet
- 手机连接时，手机与电脑位于同一个可信局域网

### 3.2 建议的目录结构

llama.cpp 和模型可以放在其他目录，不需要复制到 Git 仓库：

```text
C:\AI\llama.cpp\llama-server.exe
D:\AIModels\FgoGotran\model.gguf
C:\Projects\FgoGotran\FgoGotranLocal\
```

不要把大型文件放入 Git：

- `*.gguf`
- `llama.cpp/`
- `.venv/`
- `user_data/`

## 4. 安装 Python

从 Python 官方网站安装 64 位 Python 3.11、3.12 或 3.13。建议在安装程序中勾选 `Add Python to PATH`。

如果电脑有多个 Python，可以先在 PowerShell 指定本次启动使用的版本：

```powershell
$env:FGO_LOCAL_PYTHON = 'C:\Path\To\python.exe'
.\Start-FgoGotranLocal.cmd
```

首次启动会在 `FgoGotranLocal\.venv` 建立项目私有环境。它不会修改其他 Python 项目。只有依赖文件发生变化或环境缺失时，启动脚本才会重新安装依赖。

## 5. 安装 llama.cpp

1. 打开 [llama.cpp 官方 Releases](https://github.com/ggml-org/llama.cpp/releases)。
2. NVIDIA 显卡通常选择 Windows x64 CUDA build。
3. 没有 NVIDIA 显卡时，选择适合硬件的 CPU 或 Vulkan build。
4. 如果 Release 把 CUDA DLL 放在另一个压缩包，也要下载。
5. 将同一 Release 所需的压缩包完整解压到同一个目录。
6. 确认目录中存在 `llama-server.exe`，并保留全部 DLL。

不要只复制一个 `llama-server.exe`。缺少 DLL 时，程序可能完全无法启动，或者在加载模型时立即退出。

更新 llama.cpp 时，应解压到新目录并先完成测试，不要覆盖正在运行的旧目录。

## 6. 选择 GGUF 模型

优先选择：

- Instruction 或 Chat 模型，而不是未经指令微调的 Base 模型
- 能理解日文并稳定输出目标中文的模型
- 能遵循简短格式要求，不额外解释的模型
- llama.cpp 当前版本支持其架构和 Chat Template 的模型
- 来源可信且说明、校验值完整的 GGUF

第一次测试可以从 `Q4_K_M` 开始：

| 可用显存 | 建议起点 |
| --- | --- |
| 6–8 GB | 7B/8B Q4 |
| 10–12 GB | 8B–14B Q4 |
| 16 GB | 约 14B Q4，或较小模型的更高量化 |

这只是显存起点，不代表翻译质量排名。模型架构、Context Size、Batch、其他 GPU 程序都会改变实际占用。

模型评估不要只测试一句话，应检查：

- 日文语义和动作方向
- 简体或繁体中文输出稳定性
- 角色名称和术语
- 换行和响应格式
- 连续多次请求的失败率
- 首次请求和后续请求的速度

## 7. 启动管理页面

进入 `FgoGotranLocal` 文件夹，双击：

```text
Start-FgoGotranLocal.cmd
```

第一次启动会依次：

1. 寻找兼容 Python。
2. 创建 `.venv`。
3. 安装 Gradio、FastAPI、Uvicorn 等轻量依赖。
4. 检查当前环境和已有配置。
5. 启动本地管理页面。
6. 打开 `http://127.0.0.1:18081`。

使用期间必须保留命令窗口。需要退出时，应先在总览停止 llama-server，再关闭命令窗口。

如果浏览器没有自动打开，可以手动访问：

```text
http://127.0.0.1:18081
```

## 8. 管理页面说明

管理页面包含四个主页面：`总览`、`模型设置`、`连接测试` 和 `系统`。

### 8.1 总览

总览显示：

- llama-server 当前状态
- 当前 Profile
- GPU 和显存状态
- 启动、重新启动和停止按钮
- 手机需要填写的 Endpoint
- Model ID
- 已遮盖的 API Key
- 显示完整 API Key 和更换 Key 的控制

常见状态：

- `已停止`：服务没有运行。
- `正在加载模型`：进程已启动，但模型还未通过健康检查。
- `已就绪`：可以接受翻译请求。
- `正在翻译`：模型正在处理请求。
- `错误`：llama-server 异常退出或无法启动。

更换 API Key 前必须先停止 llama-server。新 Key 生成后，手机内的旧 Key 会立即失效。

### 8.2 模型设置

#### Profile 管理

每个 Profile 保存一组模型和推理参数。最多可建立 12 个 Profile。

- `当前 Profile`：选择要编辑的 Profile。
- `新建`：从当前配置建立新 Profile，并清空模型路径。
- `复制`：复制当前 Profile，适合比较参数。
- `删除 Profile`：必须勾选确认；至少保留一个 Profile。
- `保存并设为当前 Profile`：验证并保存，同时将其设为活动 Profile。

切换下拉列表只是在页面中切换草稿。未保存的修改不会永久写入配置。

#### 运行文件

- `llama-server.exe`：完整绝对路径，例如 `C:\AI\llama.cpp\llama-server.exe`。
- `GGUF 模型文件夹`：用于扫描模型的根目录。
- `GGUF 模型`：实际加载的模型文件。
- `扫描 GGUF`：重新扫描模型文件夹及其子目录。

扫描只接受位于所设模型文件夹内的 `.gguf` 文件，并设置数量和遍历上限，避免误选巨大目录后长时间卡住。手动输入路径时，模型也必须位于该文件夹内。

#### 服务身份与网络

- `Profile 名称`：只用于管理页面显示。
- `Model ID`：llama-server 对外报告的模型标识，必须与 Android 设置完全一致。
- `API Port`：翻译接口端口，默认 `18080`。
- `网络访问`：
  - `可信局域网 + 本机` 对应 `0.0.0.0`，手机可以连接。
  - `仅限本机` 对应 `127.0.0.1`，手机不能连接。

Model ID 长度为 1–80，只能使用英文字母、数字、`.`、`_`、`:` 和 `-`。

### 8.3 高级推理参数

第一次运行建议保留默认值：

| 参数 | 默认值 | 作用与建议 |
| --- | ---: | --- |
| Context Size | 8192 | 可处理的上下文容量；显存不足时优先降到 4096 |
| GPU Layers | 999 | 请求尽可能多地放入 GPU；0 表示不用 GPU Offload |
| Batch Size | 512 | Prompt 处理批量；越大可能越快，但占用更多内存 |
| UBatch Size | 256 | 实际计算微批量；不能大于 Batch Size |
| CPU Threads | 0 | 由 llama.cpp 自动选择 |
| Flash Attention | on | 通常节省资源并提高速度；不兼容时改为 Auto 或关闭 |
| 关闭模型思考 | 关闭 | 默认跟随模型行为，不代表强制关闭思考 |
| Prompt Cache | 开启 | 重用相同 Prompt 前缀，通常有利于连续翻译 |
| Metrics | 开启 | 让管理页面读取 Token 和运行状态 |
| Slots | 开启 | 让管理页面判断是否有请求正在处理 |

当前服务固定使用一个并行 Slot，优先保证单个 FgoGotran 翻译流程的稳定性。

任何运行参数改变后，都需要保存并重新启动 llama-server 才会生效。

### 8.4 关闭模型思考

`关闭模型思考` 默认不勾选，也就是跟随模型自己的默认行为。

只有同时满足以下条件时才建议开启：

1. 模型官方说明支持关闭 Reasoning/Thinking。
2. 当前 llama.cpp 提供对应参数。
3. 实际测试发现模型会输出无用思考内容。
4. 开启后仍能稳定返回非空译文。

启用后，FgoGotran Local 会先检查 `llama-server --help`：

- 新版优先使用 `--reasoning off`。
- 旧版支持时使用 `--chat-template-kwargs` 兼容参数。
- 两种参数都不支持时拒绝启动，并提示更新 llama.cpp。

部分翻译模型在强制关闭思考后会立即输出结束符。例如 Sakura-14B-Qwen3-v1.5 可能返回 HTTP 200，但 `content` 为空且只生成一个 Token。这时应取消勾选、保存并重新启动。Android 请求不需要另外添加 `/no_think`。

### 8.5 连接测试

模型状态变成 `已就绪` 后，点击 `开始测试`。

测试会发送一个短的 OpenAI Chat Completions 请求，检查：

- API Key 是否有效
- Model ID 是否正确
- 请求和响应 JSON 格式是否兼容
- 是否返回非空文本
- 请求耗时

测试超时为 45 秒。通过只代表连接和格式正常，不代表 FGO 翻译质量已经达到要求。

### 8.6 系统

`环境诊断` 检查：

- FgoGotran Local 版本
- Python 版本
- 数据目录
- llama-server 路径
- 模型目录
- 运行文件验证
- 翻译端口状态
- 局域网地址
- GPU、显存和系统内存

`运行日志` 显示当前进程内存中的 INFO、WARN 和 ERROR。可以筛选级别、刷新、复制或清除显示。日志不会故意保存翻译 Prompt 或游戏对白，但可能包含本机文件路径，分享前仍需检查。

## 9. 连接 Android 手机

### 9.1 电脑端条件

1. 手机和电脑连接同一个可信 Wi-Fi。
2. Windows 当前网络类型设置为 `专用网络`。
3. 活动 Profile 的网络访问选择 `可信局域网 + 本机`。
4. 保存后重新启动 llama-server。
5. 等待状态变成 `已就绪`。

Windows 首次询问防火墙时，只允许专用网络。不要允许公共网络。

### 9.2 找到正确地址

管理页面地址：

```text
http://127.0.0.1:18081
```

它只能在电脑打开，不能填入 Android。

手机应使用总览显示的翻译 Endpoint，例如：

```text
http://<PC-LAN-IP>:18080/v1/chat/completions
```

健康检查地址：

```text
http://<PC-LAN-IP>:18080/health
```

路由器地址不是电脑地址。不要猜测 IP，以总览当前显示为准。

### 9.3 手机浏览器测试

先在手机浏览器访问 Health 地址。如果能立即看到健康状态响应，说明基本网络已经连通。

如果一直加载：

- 检查是否误用了 `18081`。
- 检查 Profile 是否仍为 `127.0.0.1`。
- 检查模型是否仍在加载。
- 检查 Windows 防火墙的专用网络规则。
- 检查 VPN 是否禁止局域网访问。
- 检查路由器是否启用了访客隔离或 AP Isolation。
- 检查电脑 IP 是否已经变化。

不要通过关闭整个 Windows 防火墙或建立公网端口转发来解决问题。

### 9.4 FgoGotran 设置

在 Android 应用的 API 设置中：

1. 选择 `自定义 / 本地 AI`。
2. 将总览显示的完整 Endpoint 填入 API 地址。
3. 填入完全相同的 Model ID。
4. 点击显示完整 API Key，并复制到 Android。
5. 保存并运行连接测试。

Android 对本地 HTTP 有额外保护：

- 只允许数字形式的私有局域网 IP。
- 不接受 `localhost` 或电脑主机名。
- 不接受公网 HTTP 地址。
- Endpoint 必须指向 `/v1/chat/completions`。

## 10. 性能调节顺序

先保证稳定返回，再调速度和质量。

### 10.1 显存不足或加载失败

按以下顺序处理：

1. 关闭其他占用 GPU 的程序。
2. 将 Context Size 从 8192 降到 4096。
3. 使用更小的 GGUF 量化或更小参数模型。
4. 降低 Batch Size 和 UBatch Size。
5. 将 Flash Attention 改为 Auto 或关闭。
6. 检查 llama.cpp、显卡驱动和模型架构是否兼容。

### 10.2 翻译速度慢

- 确认 GPU Layers 为 999，并观察模型是否实际加载到 GPU。
- 保持 Prompt Cache 开启。
- 避免同时运行大型 GPU 程序。
- 不要设置远高于实际需要的 Context Size。
- 使用适合显存的模型，而不是让部分模型频繁在内存和显存之间交换。
- 首次请求通常比后续请求慢，应分别测试。

### 10.3 翻译质量不稳定

- 确认下载的是 Instruction/Chat 模型。
- 检查模型是否适合日译中，而不只是普通聊天。
- 连续测试多段 FGO 对白，不要只看兼容性测试。
- 在 Android 端检查 Temperature 和 Top-p 设置。
- 确认模型没有输出解释、代码块或错误格式。
- 比较不同 Profile 时，每次只改变一个主要变量。

## 11. Profile 使用建议

可以为不同模型或配置建立 Profile，例如：

- 稳定翻译模型
- 更高质量但较慢的模型
- 低显存配置
- 仅本机测试配置

Profile 的显示名称只供人阅读；Model ID 是 Android 请求使用的标识。保存某个 Profile 后，它会成为当前 Profile。运行中的 llama-server 不会自动热切换模型，必须重新启动。

## 12. 配置、备份与更新

### 12.1 配置位置

默认用户数据位于：

```text
FgoGotranLocal\user_data\
```

主要文件：

- `config.json`：当前配置和 API Key
- `config.json.bak`：更新配置前保留的备份
- `state\`：临时运行状态，例如 API Key 临时文件

不要将 `user_data` 提交到 Git，也不要直接把它发给其他人。

### 12.2 更新 FgoGotran Local

1. 停止 llama-server。
2. 关闭启动命令窗口。
3. 备份 `user_data`。
4. 更新代码文件。
5. 保留或恢复原来的 `user_data`。
6. 重新运行 `Start-FgoGotranLocal.cmd`。
7. 启动模型并运行兼容性测试。

依赖文件改变时，启动器会自动更新私有 Python 环境。

### 12.3 安全重置

遇到无法恢复的配置错误时，不建议直接删除：

1. 停止服务并关闭窗口。
2. 将 `user_data` 重命名为备份目录。
3. 重新启动以生成干净配置。
4. 确认新配置正常后，再手动恢复必要设置。

备份目录仍包含 API Key，不能公开。

### 12.4 卸载

停止服务后，可以删除 FgoGotran Local 文件夹。llama.cpp 和 GGUF 位于独立目录时不会受影响。如果不再使用它们，可另外处理对应目录。

## 13. 高级启动选项

### 13.1 修改管理页面端口

默认端口 `18081` 被占用时：

```powershell
$env:FGO_LOCAL_CONTROL_PORT = '18082'
.\Start-FgoGotranLocal.cmd
```

允许范围为 1024–65535。管理页面仍然只监听 `127.0.0.1`。

### 13.2 禁止自动打开浏览器

```powershell
$env:FGO_LOCAL_OPEN_BROWSER = '0'
.\Start-FgoGotranLocal.cmd
```

然后手动访问管理页面。

### 13.3 指定其他数据目录

高级用户可以直接运行 PowerShell 启动脚本：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\windows\start.ps1" `
  -ProjectRoot "." `
  -DataDirectory "D:\FgoGotranLocalData"
```

数据目录必须是可解析的有效路径。不要将它放在会自动公开同步的目录中。

## 14. 常见问题

### 为什么手机不能打开 18081？

这是安全设计。`18081` 是本机管理页面，只监听 `127.0.0.1`。手机使用的是 `18080` 翻译接口。

### 为什么保存参数后没有变化？

保存只更新配置。模型路径、端口和推理参数属于运行中的 llama-server 进程，需要重新启动才会生效。

### 为什么模型测试通过，但 FGO 翻译仍然不好？

兼容性测试只检查连接、认证、格式和非空输出。最终质量取决于模型、量化、Android Prompt、术语和采样参数。

### 为什么第一次启动较慢？

第一次需要创建 `.venv` 并下载 Python 依赖。第一次加载 GGUF 和第一次推理也通常比后续请求慢。

### 是否可以使用云端模型？

FgoGotran Local 本身用于控制本机 llama.cpp。FgoGotran Android 应用可以另外直接配置其支持的云端 API。

### 是否可以从公网连接？

不建议，也不属于本项目的设计范围。不要设置路由器端口转发。远程使用应通过经过安全设计、带加密和访问控制的独立方案实现。

### 电脑 IP 经常变化怎么办？

在路由器中为电脑建立 DHCP 地址保留，然后继续保持端口只在可信局域网中使用。DHCP 保留不是公网端口转发。

## 15. 最终检查清单

开始使用前确认：

- [ ] Python 为 64 位 3.11–3.13
- [ ] llama.cpp 来自可信来源并完整解压
- [ ] `llama-server.exe` 同目录保留全部 DLL
- [ ] GGUF 是 Instruction/Chat 模型
- [ ] 模型路径和 Model ID 正确
- [ ] 手机连接时使用 `0.0.0.0` 网络模式
- [ ] Windows 网络类型为专用网络
- [ ] 防火墙只允许专用网络
- [ ] llama-server 状态为已就绪
- [ ] 内置兼容性测试通过
- [ ] 手机可以访问 Health 地址
- [ ] Android Endpoint 包含 `/v1/chat/completions`
- [ ] Android Model ID 与页面完全一致
- [ ] Android 使用完整的新 API Key
- [ ] 没有建立公网端口转发
- [ ] `user_data`、`.venv`、GGUF 和 llama.cpp 未提交到 Git

如仍有问题，先查看管理页面的 `系统 → 环境诊断` 和 `系统 → 运行日志`，再参考英文 [Troubleshooting](docs/TROUBLESHOOTING.md)。
