import type { Metadata } from "next";
import { ProviderGrid } from "@/components/ProviderGrid";
import { SectionHeader } from "@/components/SectionHeader";

export const metadata: Metadata = {
  title: "API 指南",
  description: "FgoGotran 可用翻译 API 服务商、模型名和接口设置说明。"
};

export default function ApiGuidePage() {
  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">API Guide</p>
          <h1>选择并配置翻译 API</h1>
          <p>
            FgoGotran 使用你自己的 API Key 进行翻译。选择服务商后，在 Android App 设置页填写
            API Key、模型名和接口地址，再点击“测试API”和“应用API设置”。
          </p>
        </div>
      </section>

      <section className="section">
        <SectionHeader
          title="推荐服务商"
          body="下面列出常见选择。大陆用户通常可以先试 DeepSeek、GLM 或 Qwen；如果你已经有其他兼容 OpenAI 格式的接口，也可以使用自定义接口。"
        />
        <ProviderGrid />
      </section>

      <section className="section section-band compact">
        <SectionHeader title="填写时注意" />
        <div className="guide-list">
          <article className="guide-card">
            <div className="guide-label">API Key</div>
            <div>
              <h3>只填写自己的 Key</h3>
              <p>不要把 API Key 发给别人。测试成功后，记得点击“应用API设置”，否则新设置不会生效。</p>
            </div>
          </article>
          <article className="guide-card">
            <div className="guide-label">模型名</div>
            <div>
              <h3>优先使用服务商文档中的模型名</h3>
              <p>如果不确定，可以先保留应用默认模型；确认接口可用后再尝试更换模型。</p>
            </div>
          </article>
        </div>
      </section>
    </>
  );
}
