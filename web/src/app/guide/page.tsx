import type { Metadata } from "next";
import {
  AlertCircle,
  BookOpen,
  CirclePlay,
  MessageSquareText,
  MousePointerClick,
  PanelBottom,
  Settings,
  Smartphone
} from "lucide-react";
import { modeCards } from "@/data/site";

export const metadata: Metadata = {
  title: "使用指南",
  description: "FgoGotran 初次设置、FGO 游戏设置、API 配置与悬浮按钮模式说明。"
};

type GuideImage = {
  src: string;
  alt: string;
  kind?: "phone" | "wide" | "icon";
};

type SetupStep = {
  id: string;
  title: string;
  body: string;
  note?: string;
  images?: GuideImage[];
};

type ButtonGuide = {
  id: string;
  title: string;
  body: string;
  images: GuideImage[];
};

const setupSteps: SetupStep[] = [
  {
    id: "install",
    title: "安装并打开 FgoGotran",
    body: "选择简体中文或繁体中文翻译。按提示开启无障碍服务权限、悬浮窗权限，并尽量关闭电池优化。",
    note: "不同手机品牌可能没有关闭电池优化的选项；没有这个选项也可以继续使用。",
    images: [
      { src: "/guide/guide-01.jpeg", alt: "FgoGotran 初次设置页面" },
      { src: "/guide/guide-02.jpeg", alt: "Android 权限设置页面" }
    ]
  },
  {
    id: "fgo-text-speed",
    title: "设置 FGO 剧情文字速度",
    body: "前往 FGO 游戏设置，把剧情文字显示相关选项调成推荐值，让 OCR 和自动模式更稳定。",
    note: "推荐：文字送り MAX，ページ送り MAX，句読点待ち時間 0。",
    images: [
      { src: "/guide/guide-04.jpeg", alt: "FGO 游戏设置路径", kind: "wide" },
      { src: "/guide/guide-05.jpeg", alt: "FGO 剧情文字速度推荐设置", kind: "wide" }
    ]
  },
  {
    id: "api",
    title: "配置 API 接口",
    body: "前往设置页选择 API 服务商，输入 API Key。模型名可以使用默认值，也可以按服务商文档填写。",
    note: "按“测试API”确认成功后，记得按“应用API设置”。",
    images: [
      { src: "/guide/guide-06.jpeg", alt: "FgoGotran 设置入口" },
      { src: "/guide/guide-07.jpeg", alt: "API 服务商选择" },
      { src: "/guide/guide-08.jpeg", alt: "API Key 和模型设置" }
    ]
  },
  {
    id: "master-name",
    title: "保存御主名称",
    body: "输入你的御主名称并保存。请确保这里的名称和 FGO 内的御主名称一致。",
    images: [{ src: "/guide/guide-09.jpeg", alt: "御主名称设置" }]
  },
  {
    id: "start-service",
    title: "启动服务并打开 FGO",
    body: "回到首页，点击“启动服务”。然后打开 FGO，就可以使用 FgoGotran 的悬浮按钮进行翻译。",
    images: [{ src: "/guide/guide-10.jpeg", alt: "启动 FgoGotran 服务" }]
  }
];

const buttonGuides: ButtonGuide[] = [
  {
    id: "floating-menu",
    title: "打开翻译菜单",
    body: "长按悬浮按钮可以打开翻译菜单。菜单里可以切换模式、查看翻译 LOG，或关闭服务。",
    images: [{ src: "/guide/guide-11.jpeg", alt: "长按悬浮按钮打开菜单", kind: "wide" }]
  },
  {
    id: "mode-indicator",
    title: "查看当前翻译模式",
    body: "悬浮按钮会显示当前模式，方便你确认现在是手动、半自动、全自动还是裁剪模式。",
    images: [
      { src: "/guide/guide-12.jpeg", alt: "手动模式悬浮按钮", kind: "icon" },
      { src: "/guide/guide-13.jpeg", alt: "半自动模式悬浮按钮", kind: "icon" },
      { src: "/guide/guide-14.jpeg", alt: "全自动模式悬浮按钮", kind: "icon" },
      { src: "/guide/guide-15.jpeg", alt: "裁剪模式悬浮按钮", kind: "icon" }
    ]
  },
  {
    id: "failure-ring",
    title: "红色外圈",
    body: "红色外圈只会在手动和半自动模式出现，表示这次点击识别或翻译没有成功。你可以再次点击重试。",
    images: [
      { src: "/guide/guide-16.jpeg", alt: "手动模式翻译失败红色外圈", kind: "icon" },
      { src: "/guide/guide-17.jpeg", alt: "半自动模式翻译失败红色外圈", kind: "icon" }
    ]
  },
  {
    id: "crop-mode",
    title: "裁剪模式",
    body: "裁剪模式用于手动框选画面区域再翻译，适合剧情外画面，或自动/手动识别不准时使用。",
    images: [
      { src: "/guide/guide-log-crop.jpeg", alt: "裁剪模式框选历史文本", kind: "wide" },
      { src: "/guide/guide-18.jpeg", alt: "裁剪模式示例", kind: "wide" }
    ]
  },
  {
    id: "translation-log",
    title: "翻译 LOG",
    body: "翻译 LOG 可以查看本次识别和翻译过的角色名、对话与选项，方便回看。",
    images: [{ src: "/guide/guide-19.jpeg", alt: "翻译 LOG 示例", kind: "wide" }]
  }
];

const toc = [
  { href: "#setup", label: "初次设置" },
  { href: "#install", label: "安装与权限" },
  { href: "#fgo-text-speed", label: "FGO 文字速度" },
  { href: "#api", label: "API 接口" },
  { href: "#master-name", label: "御主名称" },
  { href: "#start-service", label: "启动服务" },
  { href: "#floating-button", label: "悬浮按钮" },
  { href: "#mode-indicator", label: "模式显示" },
  { href: "#failure-ring", label: "红色外圈" },
  { href: "#crop-mode", label: "裁剪模式" },
  { href: "#translation-log", label: "翻译 LOG" }
];

function ImageStrip({ images }: { images: GuideImage[] }) {
  const stripKind = images.some((image) => image.kind === "wide")
    ? "wide"
    : images.some((image) => image.kind === "icon")
      ? "icon"
      : "phone";
  return (
    <div className={`docs-image-strip ${stripKind}${images.length === 1 ? " single" : ""}`}>
      {images.map((image) => (
        <figure className={`docs-image-frame ${image.kind ?? "phone"}`} key={image.src}>
          <img src={image.src} alt={image.alt} loading="lazy" />
        </figure>
      ))}
    </div>
  );
}

export default function GuidePage() {
  return (
    <div className="docs-shell">
      <aside className="docs-sidebar" aria-label="指南分类">
        <div className="docs-sidebar-title">FgoGotran 指南</div>
        <a href="#setup">
          <BookOpen size={16} aria-hidden="true" />
          初次设置
        </a>
        <a href="#floating-button">
          <PanelBottom size={16} aria-hidden="true" />
          悬浮按钮与模式
        </a>
      </aside>

      <article className="docs-article">
        <header className="docs-hero">
          <p className="eyebrow">Guide</p>
          <h1>FgoGotran 使用指南</h1>
          <p>
            按照下面两部分完成设置：先准备好 Android 权限、FGO 剧情文字速度和 API；
            再了解悬浮按钮与翻译模式的使用方式。
          </p>
        </header>

        <section className="docs-section" id="setup">
          <div className="docs-section-heading">
            <Smartphone size={24} aria-hidden="true" />
            <div>
              <h2>一、初次设置</h2>
              <p>这一部分只需要按顺序做一次。之后通常只需要启动服务并打开 FGO。</p>
            </div>
          </div>

          <div className="docs-step-list">
            {setupSteps.map((step, index) => (
              <section className="docs-step" id={step.id} key={step.id}>
                <div className="docs-step-number">{index + 1}</div>
                <div className="docs-step-body">
                  <h3>{step.title}</h3>
                  <p>{step.body}</p>
                  {step.note ? <p className="docs-note">{step.note}</p> : null}
                  {step.images ? <ImageStrip images={step.images} /> : null}
                </div>
              </section>
            ))}
          </div>
        </section>

        <section className="docs-section" id="floating-button">
          <div className="docs-section-heading">
            <MousePointerClick size={24} aria-hidden="true" />
            <div>
              <h2>二、悬浮按钮与翻译模式</h2>
              <p>悬浮按钮是 FgoGotran 在游戏内的主要入口。不同模式适合不同阅读节奏。</p>
            </div>
          </div>

          <div className="docs-callout">
            <AlertCircle size={20} aria-hidden="true" />
            <p>
              红色外圈只表示本次点击失败，不代表服务永久停止。手动或半自动模式下可以再次点击重试。
            </p>
          </div>

          <div className="docs-mode-grid">
            {modeCards.map((mode) => {
              const Icon = mode.icon;
              return (
                <article className="docs-mode-card" key={mode.title}>
                  <Icon size={20} aria-hidden="true" />
                  <h3>{mode.title}</h3>
                  <p>{mode.body}</p>
                </article>
              );
            })}
          </div>

          <div className="docs-step-list">
            {buttonGuides.map((guide) => (
              <section className="docs-step" id={guide.id} key={guide.id}>
                <div className="docs-step-number">
                  {guide.id === "floating-menu" ? (
                    <Settings size={18} aria-hidden="true" />
                  ) : guide.id === "translation-log" ? (
                    <MessageSquareText size={18} aria-hidden="true" />
                  ) : (
                    <CirclePlay size={18} aria-hidden="true" />
                  )}
                </div>
                <div className="docs-step-body">
                  <h3>{guide.title}</h3>
                  <p>{guide.body}</p>
                  <ImageStrip images={guide.images} />
                </div>
              </section>
            ))}
          </div>
        </section>
      </article>

      <aside className="docs-toc" aria-label="本页内容">
        <div className="docs-toc-title">本页内容</div>
        {toc.map((item) => (
          <a href={item.href} key={item.href}>
            {item.label}
          </a>
        ))}
      </aside>
    </div>
  );
}
