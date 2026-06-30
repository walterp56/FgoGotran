import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { apiProviderGuides } from "@/data/apiProviderGuides";

export const metadata: Metadata = {
  title: "API 指南",
  description: "FgoGotran 可用翻译 API 服务商、模型名和接口设置说明。"
};

const providerListNotes: Record<string, string> = {
  deepseek: "低成本、响应快，质量稳定，剧情翻译首选。",
  zhipu: "中文表达稳定，适合作为备用服务商。",
  "qwen-cn": "使用阿里云百炼中国站账号、Key 和额度。",
  "qwen-intl": "使用 Alibaba Cloud Model Studio 国际站账号。",
  openai: "质量强，使用前确认账号、地区和余额。",
  gemini: "有免费层，适合测试 Gemini API 可用性。",
  claude: "语气和长文本处理强，需要 Claude 接口类型。",
  custom: "适合第三方网关、自建接口或兼容接口。"
};

export default function ApiGuidePage() {
  return (
    <>
      <section className="page-hero api-guide-hero">
        <div className="page-hero-inner api-guide-hero-inner">
          <div>
            <p className="eyebrow">API Guide</p>
            <h1>API 接入文档</h1>
          </div>
        </div>
      </section>

      <section className="section">
        <div className="section-header">
          <p className="eyebrow">Providers</p>
          <h2>服务商文档</h2>
        </div>
        <div className="api-provider-grid">
          {apiProviderGuides.map((guide) => (
            <article className="api-provider-card" key={guide.slug}>
              <div className="api-provider-card-main">
                <div>
                  <div className="api-provider-title-row">
                    <h3>{guide.shortTitle}</h3>
                    {guide.slug === "deepseek" ? <span className="api-recommend-badge">推薦</span> : null}
                  </div>
                  <p>{providerListNotes[guide.slug] ?? "查看详情后再填写 API 设置。"}</p>
                </div>
              </div>
              <div className="api-provider-card-footer">
                <span>
                  推荐模型：
                  {guide.slug === "deepseek" ? (
                    <strong className="api-recommend-model">deepseek-v4-flash</strong>
                  ) : (
                    guide.recommendedModels[0]
                  )}
                </span>
                <Link className="api-route-primary" href={`/api-guide/${guide.slug}`}>
                  查看文档
                  <ArrowRight size={15} aria-hidden="true" />
                </Link>
              </div>
            </article>
          ))}
        </div>
      </section>
    </>
  );
}
