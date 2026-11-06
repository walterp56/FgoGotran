import { ArrowUpRight, Mail } from "lucide-react";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { siteConfig } from "@/data/site";
import { pageMetadata } from "@/lib/seo";

type AboutPageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: AboutPageProps) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "about" });
  return pageMetadata(locale, "about", { title: t("title"), description: t("description") });
}

export default async function AboutPage({ params }: AboutPageProps) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("about");
  const disclaimer = t.raw("disclaimer.items") as { title: string; body: string }[];
  const emailHref = `mailto:${siteConfig.contactEmail}`;

  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">{t("eyebrow")}</p>
          <h1>{t("h1")}</h1>
        </div>
      </section>

      <section className="section compact about-content">
        <section className="about-contact" aria-labelledby="about-contact-title">
          <h2 id="about-contact-title">{t("contact.title")}</h2>
          <a className="about-email" href={emailHref}>{siteConfig.contactEmail}</a>
          <a className="primary-button small about-email-button" href={emailHref}>
            <Mail size={17} aria-hidden="true" />
            {t("contact.emailAction")}
          </a>
          <div className="about-contact-links">
            <a href={siteConfig.githubUrl} target="_blank" rel="noreferrer">
              {t("contact.github")}<ArrowUpRight size={15} aria-hidden="true" />
            </a>
            <a href={`${siteConfig.githubUrl}/issues`} target="_blank" rel="noreferrer">
              {t("contact.issues")}<ArrowUpRight size={15} aria-hidden="true" />
            </a>
          </div>
          <p className="about-contact-hint">{t("contact.hint")}</p>
        </section>

        <section className="about-disclaimer" aria-labelledby="about-disclaimer-title">
          <h2 id="about-disclaimer-title">{t("disclaimer.title")}</h2>
          <div className="about-disclaimer-copy">
            {disclaimer.map((item) => (
              <div key={item.title}>
                <h3>{item.title}</h3>
                <p>{item.body}</p>
              </div>
            ))}
          </div>
          <nav className="about-resources" aria-label={t("resources.label")}>
            <a href={`${siteConfig.githubUrl}/blob/main/PRIVACY_POLICY.md`} target="_blank" rel="noreferrer">
              {t("resources.privacy")}<ArrowUpRight size={15} aria-hidden="true" />
            </a>
          </nav>
        </section>
      </section>
    </>
  );
}
