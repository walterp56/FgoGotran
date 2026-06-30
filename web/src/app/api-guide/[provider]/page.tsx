import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ArrowLeft, ExternalLink } from "lucide-react";
import { apiProviderGuides, getApiProviderGuide } from "@/data/apiProviderGuides";
import { apiProviders } from "@/data/site";

type ApiProviderPageProps = {
  params: Promise<{
    provider: string;
  }>;
};

export function generateStaticParams() {
  return apiProviderGuides.map((guide) => ({ provider: guide.slug }));
}

export async function generateMetadata({ params }: ApiProviderPageProps): Promise<Metadata> {
  const { provider } = await params;
  const guide = getApiProviderGuide(provider);

  if (!guide) {
    return {
      title: "API 文档"
    };
  }

  return {
    title: `${guide.shortTitle} API 文档`,
    description: guide.summary
  };
}

export default async function ApiProviderPage({ params }: ApiProviderPageProps) {
  const { provider } = await params;
  const guide = getApiProviderGuide(provider);

  if (!guide) {
    notFound();
  }

  const providerSettings = apiProviders.find((item) => item.id === guide.providerId);
  const sourceKeyLink =
    guide.sourceLinks.find(
      (source) => source.label.toLowerCase().includes("api key") || source.label.includes("控制台")
    ) ?? guide.sourceLinks[0];
  const apiKeyLink = providerSettings?.consoleUrl
    ? {
        label: `${guide.shortTitle} API Key / 控制台`,
        href: providerSettings.consoleUrl
      }
    : sourceKeyLink;
  const isTopRecommended = guide.slug === "deepseek";

  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <Link className="text-link inline" href="/api-guide">
            <ArrowLeft size={16} aria-hidden="true" />
            返回 API 文档
          </Link>
          <p className="eyebrow">API Provider</p>
          <h1>{guide.title}</h1>
          <p>{guide.summary}</p>
          <div className="docs-meta">
            <span>推荐模型：{guide.recommendedModels.join(" / ")}</span>
          </div>
          {isTopRecommended ? (
            <div className="api-top-recommend">
              <span className="api-recommend-badge">推薦</span>
              <strong>DeepSeek</strong>
              <span>首选模型：deepseek-v4-flash</span>
            </div>
          ) : null}
        </div>
      </section>

      {apiKeyLink ? (
        <section className="section compact">
          <article className="api-key-callout">
            <div>
              <p className="guide-label">API Key</p>
              <h2>打开 Key / 控制台页面</h2>
            </div>
            <a className="api-key-callout-link" href={apiKeyLink.href} target="_blank" rel="noreferrer">
              打开 {apiKeyLink.label}
            </a>
          </article>
        </section>
      ) : null}

      <section className="section">
        <div className="api-detail-grid">
          <article className="api-detail-card">
            <div className="guide-label">Network</div>
            <h2>网络提示</h2>
            <p>{guide.vpnSummary}</p>
          </article>
          <article className="api-detail-card">
            <div className="guide-label">Free / Trial</div>
            <h2>免费层与试用额度</h2>
            <p>{guide.freeQuotaSummary}</p>
          </article>
          {providerSettings ? (
            <article className="api-detail-card">
              <div className="guide-label">Endpoint</div>
              <h2>已内建App中</h2>
              <code>{providerSettings.endpoint}</code>
            </article>
          ) : null}
        </div>
      </section>

      <section className="section compact">
        <div className="api-detail-grid">
          <article className="api-detail-card">
            <div className="guide-label">Setup</div>
            <h2>在 FgoGotran 中怎么填</h2>
            <ul className="api-bullet-list">
              <li>不要把 API Key 发给别人，也不要公开截图完整 Key。</li>
              <li>测试成功后，记得点击“应用API设置”，否则新设置不会生效。</li>
              <li>模型名优先使用服务商文档或控制台显示的完整名称。</li>
              {guide.setupNotes.map((note) => (
                <li key={note}>{note}</li>
              ))}
            </ul>
          </article>
          <article className="api-detail-card">
            <div className="guide-label">Sources</div>
            <h2>官方来源</h2>
            <p className="api-source-note">免费额度和可用地区可能变化，正式使用前建议再打开官方页面确认一次。</p>
            <div className="api-source-list">
              {guide.sourceLinks.map((source) => (
                <a href={source.href} key={source.href} target="_blank" rel="noreferrer">
                  {source.label}
                  <ExternalLink size={14} aria-hidden="true" />
                </a>
              ))}
            </div>
          </article>
        </div>
      </section>
    </>
  );
}
