export type LocalGuideStep = {
  title: string;
  body: string;
  note?: string;
};

export type LocalGuideStatus = {
  code: string;
  label: string;
  meaning: string;
  tone: "neutral" | "working" | "ready" | "error";
};

export type LocalGuideParameter = {
  name: string;
  defaultValue: string;
  guidance: string;
};

export const localQuickStartSteps: LocalGuideStep[] = [
  {
    title: "启动并检查 Python",
    body: "双击 Start-FgoGotranLocal.cmd。程序会复用 64 位 Python 3.11–3.13；没有兼容版本时，可确认下载经签名和 SHA-256 校验的私有 Python 3.13.15。",
    note: "私有 Python 位于 user_data，不修改系统 PATH 或文件关联。"
  },
  {
    title: "准备完整的 llama.cpp",
    body: "没有已有配置时，可确认自动下载经过大小和 SHA-256 校验的官方 Windows x64 构建；NVIDIA 会按驱动选择 CUDA 包，否则使用 CPU 构建。",
    note: "也可按 n 跳过并手动选择；已有非空路径不会被覆盖。"
  },
  {
    title: "准备 GGUF 翻译模型",
    body: "从可信发布者自行下载能够理解日文、稳定输出中文的 Instruction 或 Chat GGUF，再在管理页面选择模型目录和文件。",
    note: "启动器不会下载、更新、替换或删除 GGUF 模型。"
  },
  {
    title: "启动管理页面",
    body: "环境准备完成后，管理页面会在浏览器打开；使用期间保留命令窗口。完整的当前 Profile 会自动启动模型。",
    note: "管理页面默认地址是 http://127.0.0.1:18081，只能由当前电脑访问。"
  },
  {
    title: "填写模型设置",
    body: "自动设置成功时会写入 llama-server.exe 路径；GGUF 始终由用户设置模型文件夹，再扫描并选择模型。"
  },
  {
    title: "保存并启动服务",
    body: "确认“可信局域网 + 本机”和 Model ID。自动启动失败或被关闭时，在总览手动启动服务并等待状态变成“已就绪”。",
    note: "运行参数保存后不会热更新；模型已经运行时，需要重新启动服务才能应用新设置。"
  },
  {
    title: "确认连接测试",
    body: "启动时会自动检查 OpenAI Chat Completions 兼容性。也可以在“连接测试”中点击“开始测试”复测。",
    note: "通过只代表连接、认证、格式和非空输出正常，不代表所有 FGO 对白的翻译质量。"
  },
  {
    title: "连接 Android 应用",
    body: "在总览分别点击“显示 Endpoint”和“显示 API Key”，把两项完整值及可见的 Model ID 填入 FgoGotran 的“自定义 / 本地 AI”，再点击“测试 API”和“应用API设置”。"
  }
];

export const localControlTabs = [
  {
    title: "总览",
    body: "启动、重新启动或停止 llama-server；查看运行状态和当前 Profile 摘要。Endpoint 与 API Key 默认隐藏，需要时可分别临时显示。"
  },
  {
    title: "模型设置",
    body: "管理最多 12 个 Profile，选择 llama.cpp 与 GGUF，并设置端口、网络范围和推理参数。"
  },
  {
    title: "连接测试",
    body: "复测 API Key、Model ID、请求格式和非空中文输出。启动服务时也会自动执行一次兼容性测试。"
  },
  {
    title: "系统",
    body: "查看 Python、端口、GPU、内存诊断和运行日志；局域网地址与本机路径默认隐藏，可按需临时显示。"
  }
];

export const localRuntimeStatuses: LocalGuideStatus[] = [
  {
    code: "STOPPED",
    label: "已停止",
    meaning: "服务尚未启动，或者已经正常停止。",
    tone: "neutral"
  },
  {
    code: "STARTING",
    label: "正在启动",
    meaning: "正在检查配置并建立 llama-server 进程。",
    tone: "working"
  },
  {
    code: "LOADING",
    label: "正在加载模型",
    meaning: "llama-server 已运行，正在把 GGUF 和运行缓冲区载入内存或显存。",
    tone: "working"
  },
  {
    code: "VERIFYING",
    label: "正在检查兼容性",
    meaning: "健康检查已通过，正在验证 Chat Completions 能否返回可用文本。",
    tone: "working"
  },
  {
    code: "RECOVERING",
    label: "正在应用兼容回退",
    meaning: "强制关闭思考与当前模型不兼容，正在改用模型默认行为重新启动一次。",
    tone: "working"
  },
  {
    code: "READY",
    label: "已就绪",
    meaning: "模型已经通过健康与兼容性检查，可以让手机连接。",
    tone: "ready"
  },
  {
    code: "BUSY",
    label: "正在翻译",
    meaning: "llama-server 正在处理请求，短时间排队属于正常情况。",
    tone: "working"
  },
  {
    code: "ERROR",
    label: "错误",
    meaning: "服务启动、模型加载或兼容性检查失败；请查看状态详情和运行日志。",
    tone: "error"
  }
];

export const localRuntimeDefaults: LocalGuideParameter[] = [
  {
    name: "Context Size",
    defaultValue: "8192",
    guidance: "显存不足时优先减小；过小可能限制较长提示词。"
  },
  {
    name: "GPU Layers",
    defaultValue: "999",
    guidance: "请求尽可能多地卸载到 GPU；无可用 GPU 时可设为 0。"
  },
  {
    name: "Batch Size",
    defaultValue: "512",
    guidance: "内存或显存压力仍然过高时再逐步减小。"
  },
  {
    name: "UBatch Size",
    defaultValue: "256",
    guidance: "必须小于或等于 Batch Size。"
  },
  {
    name: "CPU Threads",
    defaultValue: "0（自动）",
    guidance: "首次使用让 llama.cpp 自动选择。"
  },
  {
    name: "Flash Attention",
    defaultValue: "开启",
    guidance: "通常更节省资源；遇到不兼容或启动错误时尝试 Auto 或关闭。"
  },
  {
    name: "强制关闭模型思考",
    defaultValue: "关闭",
    guidance: "默认跟随模型。只有模型文档明确支持且测试需要时才启用。"
  },
  {
    name: "Prompt Cache / Metrics / Slots",
    defaultValue: "开启",
    guidance: "首次测试保持默认值，便于缓存、性能观察和请求状态检查。"
  }
];

export const localTuningSteps: LocalGuideStep[] = [
  {
    title: "先关闭其他 GPU 程序",
    body: "浏览器、游戏录制和其他 AI 工具可能占用显存。释放资源后重新启动模型。"
  },
  {
    title: "先减小 Context Size",
    body: "这是显存不足或加载失败时的第一项调整。每次只改一项，并重新启动后观察。"
  },
  {
    title: "改用更小的模型或量化",
    body: "如果仍无法加载，选择参数更小或量化更低的 GGUF，通常比盲目修改多个底层参数更可靠。"
  },
  {
    title: "再减小 Batch 与 UBatch",
    body: "逐步减小 Batch Size 和 UBatch Size，同时确保 UBatch 不大于 Batch。"
  },
  {
    title: "最后检查运行时兼容性",
    body: "检查 llama.cpp 构建、显卡驱动、DLL、模型架构、Chat Template 和 Flash Attention 是否兼容。"
  }
];

export const localTroubleshooting = [
  {
    title: "找不到 Python",
    body: "安装 64 位 Python 3.11–3.13。存在多个 Python 时，可用 FGO_LOCAL_PYTHON 指向需要的 python.exe。"
  },
  {
    title: "管理页面打不开",
    body: "确认启动命令窗口仍在运行，并打开 http://127.0.0.1:18081。端口冲突时再使用 FGO_LOCAL_CONTROL_PORT 更换管理端口。"
  },
  {
    title: "llama-server 路径无效",
    body: "选择名为 llama-server.exe 的实际文件，使用绝对路径，并保留同一发行包中的全部 DLL。"
  },
  {
    title: "扫描不到 GGUF",
    body: "确认文件扩展名为 .gguf、模型目录是绝对路径，并且模型位于该目录或它的子目录内，然后重新扫描。"
  },
  {
    title: "加载时退出或一直加载",
    body: "查看“系统 → 运行日志”。常见原因是显存或内存不足、DLL 缺失、运行时与驱动不匹配，或模型架构不受支持。"
  },
  {
    title: "兼容性测试失败",
    body: "确认 Model ID 与 llama-server 别名一致，并查看认证、Chat Template、只有思考内容或空输出等错误。测试超时为 45 秒。"
  },
  {
    title: "手机无法连接",
    body: "确认手机和电脑位于同一可信网络、Profile 使用“可信局域网 + 本机”、服务已经就绪，并检查 Windows 专用网络防火墙与 VPN。"
  },
  {
    title: "设置保存后没有变化",
    body: "模型、Model ID、端口、网络和推理参数属于运行时设置。保存后需要重新启动 llama-server。"
  }
];

export const localFinalChecklist = [
  "使用 64 位 Python 3.11、3.12 或 3.13",
  "llama.cpp 来自可信来源并已完整解压；自动下载已通过大小和 SHA-256 校验",
  "llama-server.exe 同目录保留所需 DLL",
  "GGUF 是从可信发布者自行取得、支持日文输入和中文输出的 Instruction 或 Chat 模型",
  "手机连接时选择“可信局域网 + 本机”",
  "Windows 防火墙只允许专用网络",
  "运行状态已经变为“已就绪”",
  "启动兼容性测试或手动连接测试已经通过",
  "手机 API 地址来自总览，并包含 /v1/chat/completions",
  "手机中的模型名称与电脑 Model ID 完全一致",
  "手机使用完整的新 API Key",
  "没有把 18080 或 18081 转发到公网"
];

export const localGuideToc = [
  { href: "#scope", label: "用途与边界" },
  { href: "#architecture", label: "连接方式" },
  { href: "#quick-start", label: "快速开始" },
  { href: "#choose-files", label: "选择运行文件" },
  { href: "#control-panel", label: "控制面板" },
  { href: "#phone", label: "连接手机" },
  { href: "#status", label: "状态说明" },
  { href: "#performance", label: "性能调节" },
  { href: "#troubleshooting", label: "故障排查" },
  { href: "#security", label: "安全与维护" },
  { href: "#checklist", label: "最终检查" }
];
