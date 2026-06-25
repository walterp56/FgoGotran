import {
  Accessibility,
  BrainCircuit,
  CloudDownload,
  CirclePlay,
  DatabaseZap,
  Globe,
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
  Webhook,
  Workflow
} from "lucide-react";
import type { LucideIcon } from "lucide-react";

export const siteConfig = {
  name: "FgoGotran",
  description: "Fate/Grand Order 日服剧情 OCR + AI 中文翻译悬浮工具。",
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
  { href: "/api-guide", label: "接口指南" },
  { href: "/terms", label: "术语库" },
  { href: "/changelog", label: "更新日志" }
];

export type Feature = {
  title: string;
  body: string;
  icon: LucideIcon;
};

export const features: Feature[] = [
  {
    title: "FGO 专用提示词",
    body: "针对 FGO 原文风格调校 Prompt，并结合术语库 RAG，让角色名、设定词和台词语气更稳定。",
    icon: BrainCircuit
  },
  {
    title: "贴近原生 UI",
    body: "译文按 FGO 原本的姓名框、对话框和选项框位置渲染，阅读时更像游戏内文本。",
    icon: PanelBottom
  },
  {
    title: "实时悬浮翻译",
    body: "检测到新的剧情文本后，实时 OCR 并翻译，中文译文直接显示在 FGO 画面上。",
    icon: ScanText
  },
  {
    title: "四种模式",
    body: "手动、半自动、你全自动、裁剪区域翻译分开处理。",
    icon: SlidersHorizontal
  },
  {
    title: "术语库更新",
    body: "从 CDN 拉取最新 SQLite 术语库，校验，角色名、宝具、地点、组织名等固定翻译。",
    icon: DatabaseZap
  },
  {
    title: "多接口支持",
    body: "支持 DeepSeek、智谱 GLM、阿里云百炼 Qwen、OpenAI GPT、Google Gemini、Anthropic Claude 和自定义接口。",
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
    regionNote: "大陆用户通常更容易访问，适合作为默认推荐之一。",
    bestFor: "低成本、中文能力、FGO 长文本翻译。",
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
    regionNote: "大陆访问友好，新用户资源包适合测试。",
    bestFor: "国内模型、中文响应、替代 DeepSeek。",
    priority: "mainland-first"
  },
  {
    id: "qwen",
    name: "阿里云百炼 Qwen",
    shortName: "Qwen",
    recommendedModel: "qwen-flash",
    endpoint: "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions",
    consoleUrl: "https://modelstudio.console.alibabacloud.com/",
    docsUrl: "https://help.aliyun.com/zh/model-studio/",
    regionNote: "阿里云账号体系适合大陆用户，注意选择对应区域和百炼控制台。",
    bestFor: "国内可用、速度优先、免费额度测试。",
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
    regionNote: "部分地区访问和付款受限，适合海外用户或自备网络环境。",
    bestFor: "质量稳定、英文/日文理解强。",
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
    regionNote: "大陆网络环境通常不适合作为默认推荐。",
    bestFor: "可用免费额度测试、兼容 OpenAI 风格接口。",
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
    regionNote: "不建议面向大陆用户作为默认服务，需要确认地区支持和付款。",
    bestFor: "高级用户、质量验证、长上下文备用。",
    priority: "advanced"
  },
  {
    id: "custom",
    name: "自定义接口",
    shortName: "Custom",
    recommendedModel: "按服务商填写",
    endpoint: "兼容 OpenAI Chat Completions 的完整接口地址",
    docsUrl: "https://platform.openai.com/docs/api-reference/chat/create",
    regionNote: "适合用户已有第三方中转、私有部署或其他兼容接口。",
    bestFor: "高级用户、自备服务、临时替代官方接口。",
    priority: "advanced"
  }
];

export const workflowSteps = [
  {
    title: "安装 APK",
    body: "从下载页获取最新版 APK，安装后打开 FgoGotran。",
    icon: PackageCheck
  },
  {
    title: "开启权限",
    body: "按应用首页提示开启悬浮窗、无障碍服务，并建议关闭电池优化。",
    icon: Accessibility
  },
  {
    title: "配置接口",
    body: "在设置里选择翻译服务商，填入 API Key、模型和接口地址。",
    icon: KeyRound
  },
  {
    title: "启动服务",
    body: "进入 FGO 后点悬浮按钮，选择手动、自动或裁剪翻译。",
    icon: CirclePlay
  }
];

export const modeCards = [
  {
    title: "手动模式",
    body: "适合想完全掌控节奏的你。每次点击悬浮按钮后识别当前画面并翻译。",
    icon: MousePointerClick
  },
  {
    title: "半自动模式",
    body: "适合想轻松阅读、又想保留选择权的你。贴合FGO中对话佔多、选项佔少的特点。保留手动点击翻译选项，更快自动刷新对话译文。",
    icon: Workflow
  },
  {
    title: "全自动模式",
    body: "适合想放松连续阅读的你。应用会观察对话/选项变化并自动刷新译文。",
    icon: RefreshCw
  },
  {
    title: "裁剪模式",
    body: "可翻译FGO中非劇情部分，同時适用于自动/手动模式识别不准或漏翻时，手动框选画面区域进行翻译。",
    icon: SquareDashedMousePointer
  },
  {
    title: "历史记录",
    body: "保存本次识别过的角色名、对话和选项，方便回看。",
    icon: History
  }
];
