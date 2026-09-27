import { getTranslations, setRequestLocale } from "next-intl/server";
import { TermsExplorer } from "@/components/TermsExplorer";
import { pageMetadata } from "@/lib/seo";

type TermsPageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: TermsPageProps) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "terms" });
  return pageMetadata(locale, "terms", { title: t("title") });
}

export default async function TermsPage({ params }: TermsPageProps) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("terms");

  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">{t("eyebrow")}</p>
          <h1>{t("h1")}</h1>
          <p>{t("body")}</p>
          <p className="muted">{t("dbNote")}</p>
        </div>
      </section>

      <section className="section">
        <TermsExplorer />
      </section>
    </>
  );
}