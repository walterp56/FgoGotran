import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { ArrowLeft, ExternalLink } from "lucide-react";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { Link } from "@/i18n/navigation";
import { apiProviderGuides, getApiProviderGuide } from "@/data/apiProviderGuides";
import { pageMetadata } from "@/lib/seo";

type ApiProviderPageProps = {
  params: Promise<{
    locale: string;
    provider: string;
  }>;
};

export function generateStaticParams() {
  return apiProviderGuides.map((guide) => ({ provider: guide.slug }));
}

export async function generateMetadata({ params }: ApiProviderPageProps): Promise<Metadata> {
  const { locale, provider } = await params;
  const guide = getApiProviderGuide(provider);

  if (!guide) {
    const t = await getTranslations({ locale, namespace: "apiGuide" });
    return pageMetadata(locale, `api-guide/${provider}`, { title: t("title") });
  }

  const t = await getTranslations({ locale, namespace: "apiGuide" });
  return pageMetadata(locale, `api-guide/${guide.slug}`, {
    title: t("detail.apiDocTitle", { name: t(`providers.${guide.slug}.shortTitle`) }),
    description: t(`providers.${guide.slug}.summary`)
  });
}

export default async function ApiProviderPage({ params }: ApiProviderPageProps) {
  const { locale, provider } = await params;
  setRequestLocale(locale);
  const guide = getApiProviderGuide(provider);

  if (!guide) {
    notFound();
  }

  const t = await getTranslations("apiGuide");
  const common = await getTranslations("common");

  const models = t.raw(`providers.${guide.slug}.recommendedModels`) as string[];
  const setupNotes = t.raw(`providers.${guide.slug}.setupNotes`) as string[];
  const sourceLabels = t.raw(`providers.${guide.slug}.sourceLinks`) as Record<string, string>;

  const keySource =
    guide.sourceLinks.find((source) => source.kind === "key") ?? guide.sourceLinks[0];
  const isTopRecommended = guide.slug === "deepseek";

  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <Link className="text-link inline" href="/api-guide">
            <ArrowLeft size={16} aria-hidden="true" />
            {t("detail.back")}
          </Link>
          <p className="eyebrow">{t("detail.eyebrow")}</p>
          <h1>{t(`providers.${guide.slug}.title`)}</h1>
          <p>{t(`providers.${guide.slug}.summary`)}</p>
          <div className="docs-meta">
            <span>
              {common("recommendedModel")}
              {models.join(" / ")}
            </span>
          </div>
          {isTopRecommended ? (
            <div className="api-top-recommend">
              <span className="api-recommend-badge">{common("recommended")}</span>
              <strong>DeepSeek</strong>
              <span>{t("detail.topRecommendText")}</span>
            </div>
          ) : null}
        </div>
      </section>

      {keySource ? (
        <section className="section compact">
          <article className="api-key-callout">
            <div>
              <p className="guide-label">{t("detail.apiKeyLabel")}</p>
              <h2>{t("detail.apiKeyHeading")}</h2>
            </div>
            <a className="api-key-callout-link" href={keySource.href} target="_blank" rel="noreferrer">
              {t("detail.open")} {sourceLabels[keySource.key]}
            </a>
          </article>
        </section>
      ) : null}

      <section className="section">
        <div className="api-detail-grid">
          <article className="api-detail-card">
            <div className="guide-label">{t("detail.networkLabel")}</div>
            <h2>{t("detail.networkHeading")}</h2>
            <p>{t(`providers.${guide.slug}.vpnSummary`)}</p>
          </article>
          <article className="api-detail-card">
            <div className="guide-label">{t("detail.freeTrialLabel")}</div>
            <h2>{t("detail.freeTrialHeading")}</h2>
            <p>{t(`providers.${guide.slug}.freeQuotaSummary`)}</p>
          </article>
          <article className="api-detail-card">
            <div className="guide-label">{t("detail.endpointLabel")}</div>
            <h2>{t("detail.endpointHeading")}</h2>
            <code>{t(`providers.${guide.slug}.endpoint`)}</code>
          </article>
        </div>
      </section>

      <section className="section compact">
        <div className="api-detail-grid">
          <article className="api-detail-card">
            <div className="guide-label">{t("detail.setupLabel")}</div>
            <h2>{t("detail.setupHeading")}</h2>
            <ul className="api-bullet-list">
              {(t.raw("detail.setupCommon") as string[]).map((note) => (
                <li key={note}>{note}</li>
              ))}
              {setupNotes.map((note) => (
                <li key={note}>{note}</li>
              ))}
            </ul>
          </article>
          <article className="api-detail-card">
            <div className="guide-label">{t("detail.sourcesLabel")}</div>
            <h2>{t("detail.sourcesHeading")}</h2>
            <p className="api-source-note">{t("detail.sourcesNote")}</p>
            <div className="api-source-list">
              {guide.sourceLinks.map((source) => (
                <a href={source.href} key={source.href} target="_blank" rel="noreferrer">
                  {sourceLabels[source.key]}
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