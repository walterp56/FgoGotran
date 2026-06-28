import {
  Accessibility,
  BrainCircuit,
  CirclePlay,
  DatabaseZap,
  History,
  KeyRound,
  MousePointerClick,
  PackageCheck,
  PanelBottom,
  RefreshCw,
  ScanText,
  ServerCog,
  SlidersHorizontal,
  SquareDashedMousePointer,
  Workflow
} from "lucide-react";
import type { LucideIcon } from "lucide-react";

export const siteConfig = {
  name: "FgoGotran",
  description: "Fate/Grand Order 日服剧情 OCR + AI 中文翻译工具。",
  githubUrl: "https://github.com/walterp56/FgoGotran",
  cdnBaseUrl: process.env.NEXT_PUBLIC_CDN_BASE_URL ?? "https://cdn.fgogotran.com",
  apkManifestPath: "/app/android/latest/manifest.json",
  dbManifestPath: "/db/zh-Hans/latest/manifest.json",
  termsPreviewPath: "/term-preview/zh-Hans/latest/terms.preview.json",
  characterPreviewPath: "/term-preview/zh-Hans/latest/character_names.preview.json"
};

export type NavItem = {
  href: string;
  label: string;
};

export const navItems: NavItem[] = [
  { href: "/guide", label: "使用指南" },
  { href: "/api-guide", label: "API 指南" },
  { href: "/terms", label: "术语表" },
  { href: "/changelog", label: "更新记录" }
];

export type Feature = {
  title: string;
  body: string;
  icon: LucideIcon;
};

export const features: Feature[] = [
  {
    title: "FGO 剧情翻译",
    body: "面向 Fate/Grand Order 日服剧情设计，结合 OCR、术语库和 AI 翻译，尽量保留角色名、专有名词与原作语气。",
    icon: BrainCircuit
  },
  {
    title: "贴近游戏的浮层",
    body: "译文直接覆盖在 FGO 对话框附近，减少来回切换，让阅读节奏更接近原游戏体验。",
    icon: PanelBottom
  },
  {
    title: "自动识别画面",
    body: "应用会读取当前画面中的角色名、对话和选项，再交给翻译接口处理。",
    icon: ScanText
  },
  {
    title: "多种翻译模式",
    body: "手动、半自动、全自动和裁剪模式，适合不同阅读习惯和不同游戏场景。",
    icon: SlidersHorizontal
  },
  {
    title: "在线术语库",
    body: "角色名与 FGO 专有名词由在线数据库更新，减少本地旧数据带来的错误。",
    icon: DatabaseZap
  },
  {
    title: "自带 API 适配",
    body: "可接入 DeepSeek、GLM、Qwen、OpenAI、Gemini、Claude 或兼容 OpenAI 格式的服务。",
    icon: ServerCog
  }
];

export type ApiProvider = {
  id: string;
  name: string;
  shortName: string;
  recommendedModel: string;
  endpoint: string;
  consoleUrl?: string;
  docsUrl?: string;
  regionNote: string;
  bestFor: string;
  priority: "mainland-first" | "optional" | "advanced";
};

export const apiProviders: ApiProvider[] = [
  {
    id: "deepseek",
    name: "DeepSeek",
    shortName: "DeepSeek",
    recommendedModel: "deepseek-v4-flash",
    endpoint: "https://api.deepseek.com/v1/chat/completions",
    consoleUrl: "https://platform.deepseek.com/api_keys",
    docsUrl: "https://api-docs.deepseek.com/",
    regionNote: "大陆用户通常访问比较方便，适合先作为默认选择尝试。",
    bestFor: "追求响应速度、成本和日常剧情翻译平衡。",
    priority: "mainland-first"
  },
  {
    id: "zhipu",
    name: "智谱 GLM",
    shortName: "GLM",
    recommendedModel: "glm-4.5-air",
    endpoint: "https://open.bigmodel.cn/api/paas/v4/chat/completions",
    consoleUrl: "https://bigmodel.cn/usercenter/proj-mgmt/apikeys",
    docsUrl: "https://docs.bigmodel.cn/",
    regionNote: "大陆访问友好，适合作为 DeepSeek 之外的备用接口。",
    bestFor: "希望保留中文表达稳定性，同时兼顾速度的用户。",
    priority: "mainland-first"
  },
  {
    id: "qwen",
    name: "阿里云 Qwen",
    shortName: "Qwen",
    recommendedModel: "qwen-flash",
    endpoint: "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions",
    consoleUrl: "https://modelstudio.console.alibabacloud.com/",
    docsUrl: "https://help.aliyun.com/zh/model-studio/",
    regionNote: "可使用 OpenAI 兼容接口，适合已经在使用阿里云模型服务的用户。",
    bestFor: "想要一个稳定备用接口，或已经有阿里云账号的用户。",
    priority: "mainland-first"
  },
  {
    id: "openai",
    name: "OpenAI GPT",
    shortName: "GPT",
    recommendedModel: "gpt-4o",
    endpoint: "https://api.openai.com/v1/chat/completions",
    consoleUrl: "https://platform.openai.com/api-keys",
    docsUrl: "https://platform.openai.com/docs",
    regionNote: "需要可访问 OpenAI API 的网络与账号。",
    bestFor: "想要更强的理解能力和更自然的长剧情翻译。",
    priority: "optional"
  },
  {
    id: "gemini",
    name: "Google Gemini",
    shortName: "Gemini",
    recommendedModel: "gemini-3.1-flash-lite",
    endpoint: "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
    consoleUrl: "https://aistudio.google.com/apikey",
    docsUrl: "https://ai.google.dev/gemini-api/docs",
    regionNote: "需要可访问 Google AI Studio 的网络与账号。",
    bestFor: "想尝试低成本或备用模型的用户。",
    priority: "optional"
  },
  {
    id: "claude",
    name: "Anthropic Claude",
    shortName: "Claude",
    recommendedModel: "claude-haiku-4-5-20251001",
    endpoint: "https://api.anthropic.com/v1/messages",
    consoleUrl: "https://platform.claude.com/settings/keys",
    docsUrl: "https://platform.claude.com/docs",
    regionNote: "Claude 官方接口格式与 OpenAI 不完全相同，使用前请确认应用内接口类型。",
    bestFor: "更看重长文本理解和语气处理的进阶用户。",
    priority: "advanced"
  },
  {
    id: "custom",
    name: "自定义接口",
    shortName: "Custom",
    recommendedModel: "按服务商文档填写",
    endpoint: "兼容 OpenAI Chat Completions 的接口地址",
    docsUrl: "https://platform.openai.com/docs/api-reference/chat/create",
    regionNote: "适合使用第三方转发、自建网关或其他兼容接口。",
    bestFor: "已经熟悉模型 API，并希望自己管理接口的用户。",
    priority: "advanced"
  }
];

export const workflowSteps: Feature[] = [
  {
    title: "安装 APK",
    body: "下载并安装 FgoGotran，首次打开后选择要使用的中文翻译语言。",
    icon: PackageCheck
  },
  {
    title: "开启权限",
    body: "按提示开启无障碍、悬浮窗权限，并尽量关闭电池优化。",
    icon: Accessibility
  },
  {
    title: "配置 API",
    body: "选择 API 服务商，填写 API Key 和模型名，测试成功后应用设置。",
    icon: KeyRound
  },
  {
    title: "启动服务",
    body: "回到首页启动服务，再打开 FGO，就可以使用悬浮按钮翻译。",
    icon: CirclePlay
  }
];

export const modeCards: Feature[] = [
  {
    title: "手动模式",
    body: "适合想完全掌控节奏的你。点击悬浮按钮后，翻译当前画面。",
    icon: MousePointerClick
  },
  {
    title: "半自动模式",
    body: "适合想轻松看剧情、遇到选项再自己确认的你。对话自动翻译，选项出现时手动点击翻译。",
    icon: Workflow
  },
  {
    title: "全自动模式",
    body: "适合想放松阅读的你。应用会观察画面变化，并自动刷新译文。",
    icon: RefreshCw
  },
  {
    title: "裁剪模式",
    body: "适合剧情外画面，或识别不准、漏翻时，手动框选区域再翻译。",
    icon: SquareDashedMousePointer
  },
  {
    title: "翻译 LOG",
    body: "查看本次识别和翻译过的角色名、对话与选项，方便回看。",
    icon: History
  }
];
