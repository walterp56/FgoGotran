import { Smartphone } from "lucide-react";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { SectionHeader } from "@/components/SectionHeader";
import { pageMetadata } from "@/lib/seo";

const changelogDefs = [
  { key: "v4_0_0", version: "v4.0.0", date: "30-9-2026" },
  { key: "v3_1_2", version: "v3.1.2", date: "23-9-2026" },
  { key: "v3_1_1", version: "v3.1.1", date: "22-9-2026" },
  { key: "v3_1_0", version: "v3.1.0", date: "17-9-2026" },
  { key: "v3_0_0", version: "v3.0.0", date: "15-9-2026" },
  { key: "v2_4_0", version: "v2.4.0", date: "26-8-2026" },
  { key: "v2_3_1", version: "v2.3.1", date: "21-8-2026" },
  { key: "v2_3_0", version: "v2.3.0", date: "20-8-2026" },
  { key: "v2_2_0", version: "v2.2.0", date: "18-8-2026" },
  { key: "v2_1_0", version: "v2.1.0", date: "15-8-2026" },
  { key: "v2_0_0", version: "v2.0.0", date: "13-8-2026" },
  { key: "v1_1_0", version: "v1.1.0", date: "12-7-2026" },
  { key: "v1_0_2", version: "v1.0.2", date: "5-7-2026" },
  { key: "v1_0_1", version: "v1.0.1", date: "1-7-2026" },
  { key: "v1_0_0", version: "v1.0.0", date: "30-6-2026" }
];

type ChangelogPageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: ChangelogPageProps) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "changelog" });
  return pageMetadata(locale, "changelog", { title: t("title") });
}

export default async function ChangelogPage({ params }: ChangelogPageProps) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("changelog");

  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">{t("eyebrow")}</p>
          <h1>{t("h1")}</h1>
          <p>{t("body")}</p>
        </div>
      </section>

      <section className="section">
        <SectionHeader title={t("recent")} />
        <div className="changelog-grid">
          {changelogDefs.map((entry) => {
            const items = t.raw(`entries.${entry.key}.items`) as string[];
            return (
              <article className="changelog-card" key={entry.key}>
                <div className="status-title">
                  <Smartphone size={20} aria-hidden="true" />
                  <h3>{entry.version}</h3>
                </div>
                <p className="muted">{entry.date}</p>
                <ul>
                  {items.map((item) => (
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