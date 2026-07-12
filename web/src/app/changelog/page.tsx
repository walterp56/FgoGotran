import type { Metadata } from "next";
import { Smartphone } from "lucide-react";
import { SectionHeader } from "@/components/SectionHeader";

export const metadata: Metadata = {
  title: "更新记录"
};

const changelog = [
  {
    title: "v1.1.0",
    date: "12-7-2026",
    icon: Smartphone,
    items: ["新增 PaddleOCR，提升文字識別", "新增 x86_64 ABI 支持，改善模拟器兼容性", "修复裁剪模式翻译显示", "优化 AI 翻译提示词结构", "更新UI"]
  },
  {
    title: "v1.0.2",
    date: "5-7-2026",
    icon: Smartphone,
    items: ["修复译文偶尔显示 JSON 格式的问题", "增加显示日文原文", "增加更新弹窗显示APP更新内容", "適配所有螢幕尺寸", "改进区域翻译模式的文本区域适配", "优化历史面板、悬浮按钮交互", "优化 AI 翻译提示词结构", "更新UI"]
  },
  {
    title: "v1.0.1",
    date: "1-7-2026",
    icon: Smartphone,
    items: ["优化 AI 翻译提示词结构"]
  },
  {
    title: "v1.0.0",
    date: "30-6-2026",
    icon: Smartphone,
    items: ["支持手动、半自动、全自动和裁剪模式", "支持自定义翻译 API"]
  }
];

export default function ChangelogPage() {
  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">Changelog</p>
          <h1>更新记录</h1>
          <p>记录 FgoGotran Android App 的主要更新。</p>
        </div>
      </section>

      <section className="section">
        <SectionHeader title="最近更新" />
        <div className="changelog-grid">
          {changelog.map((entry) => {
            const Icon = entry.icon;
            return (
              <article className="changelog-card" key={`${entry.title}-${entry.date}`}>
                <div className="status-title">
                  <Icon size={20} aria-hidden="true" />
                  <h3>{entry.title}</h3>
                </div>
                <p className="muted">{entry.date}</p>
                <ul>
                  {entry.items.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              </article>
            );
          })}
        </div>
      </section>
    </>
  );
}
