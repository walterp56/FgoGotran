import type { Metadata } from "next";
import { DatabaseZap, PanelsTopLeft, Smartphone } from "lucide-react";
import { SectionHeader } from "@/components/SectionHeader";

export const metadata: Metadata = {
  title: "更新日志"
};

const changelog = [
  {
    title: "网站初版",
    date: "2026-06-14",
    icon: PanelsTopLeft,
    items: ["新增下载页、使用指南、接口指南、术语库查看页。", "采用静态优先结构，后续可接 API 后端。"]
  },
  {
    title: "术语库 CDN",
    date: "2026-06-10",
    icon: DatabaseZap,
    items: ["发布 zh-Hans DB manifest。", "Android App 支持检查、下载、校验并安装最新 DB。"]
  },
  {
    title: "Android App",
    date: "1.0.0",
    icon: Smartphone,
    items: ["支持手动、自动、裁剪翻译模式。", "支持多个 AI 翻译接口和本地玩家名术语。"]
  }
];

export default function ChangelogPage() {
  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">Changelog</p>
          <h1>更新日志</h1>
          <p>记录网站、APK 和术语库的公开变化。正式发布后可以由 CI 自动生成。</p>
        </div>
      </section>

      <section className="section">
        <SectionHeader title="最近变化" />
        <div className="changelog-grid">
          {changelog.map((entry) => {
            const Icon = entry.icon;
            return (
              <article className="changelog-card" key={entry.title}>
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
