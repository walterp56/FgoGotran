import type { Metadata } from "next";
import { ProviderGrid } from "@/components/ProviderGrid";
import { SectionHeader } from "@/components/SectionHeader";

export const metadata: Metadata = {
  title: "翻译接口指南"
};

export default function ApiGuidePage() {
  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">API Guide</p>
          <h1>选择并配置翻译接口</h1>
          <p>
            FgoGotran 使用用户自己的 API Key。大陆用户优先看 DeepSeek、智谱 GLM、阿里云百炼 Qwen；
            OpenAI、Google Gemini、Anthropic Claude 适合作为可选或进阶接口。
          </p>
        </div>
      </section>

      <section className="section">
        <SectionHeader
          title="已支持的服务商"
          body="这些名称和 Android App 设置页保持一致。自定义接口可以填兼容 OpenAI Chat Completions 的服务。"
        />
        <ProviderGrid />
      </section>

      <section className="section section-band compact">
        <SectionHeader title="填写位置" />
        <div className="guide-list">
          <article className="guide-card">
            <div className="guide-label">应用内路径</div>
            <div>
              <h3>设置 → 管理翻译接口</h3>
              <p>选择服务商后填写 API 地址、模型和 API Key。不同服务商的 Key 会分开保存。</p>
            </div>
          </article>
          <article className="guide-card">
            <div className="guide-label">调试建议</div>
            <div>
              <h3>先用便宜模型测试</h3>
              <p>确认 Key、地址和网络都可用后，再切换到更高质量模型。频繁报错时优先换模型或服务商。</p>
            </div>
          </article>
        </div>
      </section>
    </>
  );
}
