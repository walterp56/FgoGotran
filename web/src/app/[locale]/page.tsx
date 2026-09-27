import { readdirSync } from "node:fs";
import path from "node:path";
import { BookOpen, Download, GitBranch } from "lucide-react";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { Link } from "@/i18n/navigation";
import { ExampleSlideshow, type ExampleImage } from "@/components/ExampleSlideshow";
import { FeatureGrid } from "@/components/FeatureGrid";
import { SectionHeader } from "@/components/SectionHeader";
import { featureDefs, modeCardDefs, siteConfig, type Feature } from "@/data/site";
import { pageMetadata } from "@/lib/seo";

const exampleImageExtensions = new Set([".jpg", ".jpeg", ".png", ".webp"]);

function getHeroExamples(): ExampleImage[] {
  const examplesDirectory = path.join(process.cwd(), "public", "examples");

  try {
    return readdirSync(examplesDirectory, { withFileTypes: true })
      .filter((entry) => entry.isFile() && exampleImageExtensions.has(path.extname(entry.name).toLowerCase()))
      .sort((a, b) => a.name.localeCompare(b.name, "en"))
      .map((entry, index) => ({
        src: `/examples/${encodeURIComponent(entry.name)}`,
        alt: `FGO translation screenshot example ${index + 1}`
      }));
  } catch {
    return [];
  }
}

const heroExamples = getHeroExamples();

type HomePageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: HomePageProps) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "home" });
  return pageMetadata(locale, "", { description: t("lede") });
}

export default async function HomePage({ params }: HomePageProps) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("home");
  const modes = await getTranslations("modes");

  const features: Feature[] = featureDefs.map((def) => ({
    ...def,
    title: t(`features.${def.id}.title`),
    body: t(`features.${def.id}.body`)
  }));

  const modeCards: Feature[] = modeCardDefs.map((def) => ({
    ...def,
    title: modes(`${def.id}.title`),
    body: modes(`${def.id}.body`)
  }));

  return (
    <>
      <section className="hero">
        <div className="hero-inner">
          <div className="hero-content">
            <p className="eyebrow">{t("eyebrow")}</p>
            <h1>{t("title")}</h1>
            <p className="hero-lede">{t("lede")}</p>
            <div className="hero-actions">
              <Link className="primary-button" href="/download">
                <Download size={19} aria-hidden="true" />
                {t("downloadApk")}
              </Link>
              <Link className="secondary-button" href="/guide">
                <BookOpen size={19} aria-hidden="true" />
                {t("guide")}
              </Link>
              <a className="icon-button" href={siteConfig.githubUrl} target="_blank" rel="noreferrer">
                <GitBranch size={19} aria-hidden="true" />
                GitHub
              </a>
            </div>
            <div className="hero-mini-status">
              <span>{t("miniStatus")}</span>
            </div>
          </div>

          <div className="hero-screen" aria-label="FgoGotran screenshot examples">
            <div className="example-showcase">
              <ExampleSlideshow examples={heroExamples} intervalMs={3000} />
            </div>
          </div>
        </div>
      </section>

      <section className="section section-band">
        <SectionHeader
          eyebrow={t("featuresEyebrow")}
          title={t("featuresTitle")}
          body={t("featuresBody")}
        />
        <FeatureGrid items={features} />
      </section>

      <section className="section section-band">
        <SectionHeader
          eyebrow={t("modesEyebrow")}
          title={t("modesTitle")}
          body={t("modesBody")}
        />
        <FeatureGrid items={modeCards} />
      </section>
    </>
  );
}