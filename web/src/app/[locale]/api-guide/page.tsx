import { ArrowRight, Laptop } from "lucide-react";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { Link } from "@/i18n/navigation";
import { apiProviderGuides } from "@/data/apiProviderGuides";
import { pageMetadata } from "@/lib/seo";

type ApiGuidePageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: ApiGuidePageProps) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "apiGuide" });
  return pageMetadata(locale, "api-guide", { title: t("title"), description: t("description") });
}

export default async function ApiGuidePage({ params }: ApiGuidePageProps) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("apiGuide");
  const common = await getTranslations("common");

  return (
    <>
      <section className="page-hero api-guide-hero">
        <div className="page-hero-inner api-guide-hero-inner">
          <div>
            <p className="eyebrow">{t("eyebrow")}</p>
            <h1>{t("h1")}</h1>
          </div>
        </div>
      </section>

      <section className="section">
        <Link className="api-hero-recommendation local-api-entry" href="/local-guide">
          <span className="api-hero-icon">
            <Laptop size={21} aria-hidden="true" />
          </span>
          <span>
            <span className="api-hero-kicker">{t("localKicker")}</span>
            <strong>{t("localTitle")}</strong>
          </span>
          <ArrowRight size={18} aria-hidden="true" />
        </Link>

        <div className="section-header">
          <p className="eyebrow">{t("providersEyebrow")}</p>
          <h2>{t("providersTitle")}</h2>
        </div>
        <div className="api-provider-grid">
          {apiProviderGuides.map((guide) => {
            const models = t.raw(`providers.${guide.slug}.recommendedModels`) as string[];
            return (
              <article className="api-provider-card" key={guide.slug}>
                <div className="api-provider-card-main">
                  <div>
                    <div className="api-provider-title-row">
                      <h3>{t(`providers.${guide.slug}.shortTitle`)}</h3>
                      {guide.slug === "deepseek" ? (
                        <span className="api-recommend-badge">{common("recommended")}</span>
                      ) : null}
                    </div>
                    <p>
                      {t(`providers.${guide.slug}.listNote`) ?? t("providerFallbackNote")}
                    </p>
                  </div>
                </div>
                <div className="api-provider-card-footer">
                  <span>
                    {common("recommendedModel")}
                    {guide.slug === "deepseek" ? (
                      <strong className="api-recommend-model">deepseek-v4-flash</strong>
                    ) : (
                      models[0]
                    )}
                  </span>
                  <Link className="api-route-primary" href={`/api-guide/${guide.slug}`}>
                    {common("viewDocs")}
                    <ArrowRight size={15} aria-hidden="true" />
                  </Link>
                </div>
              </article>
            );
          })}
        </div>
      </section>
    </>
  );
}