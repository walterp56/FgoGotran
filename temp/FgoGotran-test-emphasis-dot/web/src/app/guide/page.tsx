import type { Metadata } from "next";
import { FeatureGrid } from "@/components/FeatureGrid";
import { SectionHeader } from "@/components/SectionHeader";
import { modeCards, workflowSteps } from "@/data/site";

export const metadata: Metadata = {
  title: "使用指南"
};

const permissionGuides = [
  {
    label: "悬浮窗权限",
    title: "允许显示在其他应用上层",
    body: "FgoGotran 需要在 FGO 画面上显示悬浮按钮和翻译覆盖层。"
  },
  {
    label: "无障碍服务",
    title: "开启 FgoGotran 无障碍服务",
    body: "用于检测点击、截取当前画面并启动 OCR 流程。"
  },
  {
    label: "电池优化",
    title: "建议关闭电池优化",
    body: "避免系统长时间剧情阅读时杀掉悬浮服务。不同手机厂商入口可能不同。"
  },
  {
    label: "FGO 设置",
    title: "建议提高文字速度",
    body: "把游戏内文字速度调快，可以减少 OCR 读到半句文本的概率。"
  }
];

export default function GuidePage() {
  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">Guide</p>
          <h1>从安装到开始翻译</h1>
          <p>按顺序完成权限、接口和服务启动。遇到无译文时，优先检查 API Key、网络、权限和当前模式。</p>
        </div>
      </section>

      <section className="section">
        <SectionHeader title="快速流程" body="这是新用户最短路径。" />
        <FeatureGrid items={workflowSteps} />
      </section>

      <section className="section section-band">
        <SectionHeader title="权限说明" body="这些权限都和悬浮 OCR 翻译流程直接相关。" />
        <div className="guide-list">
          {permissionGuides.map((item) => (
            <article className="guide-card" key={item.title}>
              <div className="guide-label">{item.label}</div>
              <div>
                <h3>{item.title}</h3>
                <p>{item.body}</p>
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className="section">
        <SectionHeader
          title="翻译模式"
          body="手动模式适合检查单句，自动模式适合连续剧情，裁剪模式适合非标准画面。"
        />
        <FeatureGrid items={modeCards} />
      </section>
    </>
  );
}
