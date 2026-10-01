import {
  AlertCircle,
  BookOpen,
  Captions,
  CirclePlay,
  Gamepad2,
  ImageOff,
  MessageSquareText,
  MousePointerClick,
  PanelBottom,
  Settings,
  Smartphone
} from "lucide-react";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { modeCardDefs, type Feature } from "@/data/site";
import { localizedImage } from "@/lib/localizedImage";
import { pageMetadata } from "@/lib/seo";

type GuideImage = {
  src: string;
  alt: string;
  kind?: "phone" | "wide" | "icon";
};

type GuideImageDef = {
  src: string;
  kind?: "phone" | "wide" | "icon";
};

type GuideSectionDef = {
  id: string;
  images: GuideImageDef[];
};

const setupStepDefs: GuideSectionDef[] = [
  { id: "install", images: [{ src: "/guide/guide-01.jpeg" }, { src: "/guide/guide-02.jpeg" }] },
  {
    id: "fgo-text-speed",
    images: [{ src: "/guide/guide-04.jpeg", kind: "wide" }, { src: "/guide/guide-05.jpeg", kind: "wide" }]
  },
  {
    id: "api",
    images: [{ src: "/guide/guide-06.jpeg" }, { src: "/guide/guide-07.jpeg" }, { src: "/guide/guide-08.jpeg" }]
  },
  { id: "master-name", images: [{ src: "/guide/guide-09.jpeg" }] },
  { id: "start-service", images: [{ src: "/guide/guide-10.jpeg" }] }
];

const buttonGuideDefs: GuideSectionDef[] = [
  {
    id: "floating-menu",
    images: [{ src: "/guide/guide-11.jpeg", kind: "wide" }, { src: "/guide/guide-11_2.jpeg", kind: "wide" }]
  },
  {
    id: "mode-indicator",
    images: [
      { src: "/guide/guide-12.jpeg", kind: "icon" },
      { src: "/guide/guide-13.jpeg", kind: "icon" },
      { src: "/guide/guide-14.jpeg", kind: "icon" },
      { src: "/guide/guide-15.jpeg", kind: "icon" },
      { src: "/guide/guide-20.jpeg", kind: "icon" }
    ]
  },
  {
    id: "failure-ring",
    images: [{ src: "/guide/guide-16.jpeg", kind: "icon" }, { src: "/guide/guide-17.jpeg", kind: "icon" }]
  },
  {
    id: "crop-mode",
    images: [{ src: "/guide/guide-log-crop.jpeg", kind: "wide" }, { src: "/guide/guide-18.jpeg", kind: "wide" }]
  },
  { id: "battle-subtitles", images: [{ src: "/guide/battle.jpg", kind: "wide" }] },
  { id: "translation-log", images: [{ src: "/guide/guide-19.jpeg", kind: "wide" }] }
];

const tocDefs = [
  { href: "#usage-scope", key: "usageScope" },
  { href: "#setup", key: "setup" },
  { href: "#install", key: "install" },
  { href: "#fgo-text-speed", key: "fgoTextSpeed" },
  { href: "#api", key: "api" },
  { href: "#master-name", key: "masterName" },
  { href: "#start-service", key: "startService" },
  { href: "#floating-button", key: "floatingButton" },
  { href: "#mode-indicator", key: "modeIndicator" },
  { href: "#failure-ring", key: "failureRing" },
  { href: "#crop-mode", key: "cropMode" },
  { href: "#battle-subtitles", key: "battleSubtitles" },
  { href: "#translation-log", key: "translationLog" }
];

function buildImages(defs: GuideImageDef[], alts: string[], locale: string): GuideImage[] {
  return defs.map((image, index) => ({
    src: localizedImage(locale, image.src),
    alt: alts[index] ?? "",
    kind: image.kind
  }));
}

function ImageStrip({ images }: { images: GuideImage[] }) {
  const stripKind = images.some((image) => image.kind === "wide")
    ? "wide"
    : images.some((image) => image.kind === "icon")
      ? "icon"
      : "phone";
  return (
    <div className={`docs-image-strip ${stripKind}${images.length === 1 ? " single" : ""}`}>
      {images.map((image) => (
        <figure className={`docs-image-frame ${image.kind ?? "phone"}`} key={image.src}>
          <img src={image.src} alt={image.alt} loading="lazy" />
        </figure>
      ))}
    </div>
  );
}

type GuidePageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: GuidePageProps) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "guide" });
  return pageMetadata(locale, "guide", { title: t("title"), description: t("description") });
}

export default async function GuidePage({ params }: GuidePageProps) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("guide");
  const common = await getTranslations("common");
  const modes = await getTranslations("modes");

  const modeCards: Feature[] = modeCardDefs.map((def) => ({
    ...def,
    title: modes(`${def.id}.title`),
    body: modes(`${def.id}.body`)
  }));

  const setupSteps = setupStepDefs.map((def) => ({
    ...def,
    title: t(`setupSteps.${def.id}.title`),
    body: t(`setupSteps.${def.id}.body`),
    note: t.has(`setupSteps.${def.id}.note`) ? t.raw(`setupSteps.${def.id}.note`) : undefined,
    images: buildImages(def.images, t.raw(`setupSteps.${def.id}.images`), locale)
  }));

  const buttonGuides = buttonGuideDefs.map((def) => ({
    ...def,
    title: t(`buttonGuides.${def.id}.title`),
    body: t(`buttonGuides.${def.id}.body`),
    images: buildImages(def.images, t.raw(`buttonGuides.${def.id}.images`), locale)
  }));

  return (
    <div className="docs-shell">
      <aside className="docs-sidebar" aria-label={t("sidebarAria")}>
        <div className="docs-sidebar-title">{t("sidebarTitle")}</div>
        <a href="#setup">
          <BookOpen size={16} aria-hidden="true" />
          {t("sidebar.setup")}
        </a>
        <a href="#usage-scope">
          <Gamepad2 size={16} aria-hidden="true" />
          {t("sidebar.usageScope")}
        </a>
        <a href="#floating-button">
          <PanelBottom size={16} aria-hidden="true" />
          {t("sidebar.floatingButton")}
        </a>
      </aside>

      <article className="docs-article">
        <header className="docs-hero">
          <p className="eyebrow">{t("eyebrow")}</p>
          <h1>{t("h1")}</h1>
        </header>

        <section className="docs-scope-panel" id="usage-scope">
          <div className="docs-scope-heading">
            <Gamepad2 size={22} aria-hidden="true" />
            <div>
              <h2>{t("scope.title")}</h2>
              <p>{t("scope.body")}</p>
            </div>
          </div>

          <div className="docs-scope-grid">
            <article className="docs-scope-card supported">
              <Smartphone size={20} aria-hidden="true" />
              <div>
                <h3>{t("scope.supported.title")}</h3>
                <p>{t("scope.supported.body")}</p>
              </div>
            </article>
            <article className="docs-scope-card unsupported">
              <ImageOff size={20} aria-hidden="true" />
              <div>
                <h3>{t("scope.unsupported.title")}</h3>
                <p>{t("scope.unsupported.body")}</p>
              </div>
            </article>
          </div>
        </section>

        <section className="docs-section" id="setup">
          <div className="docs-section-heading">
            <Smartphone size={24} aria-hidden="true" />
            <div>
              <h2>{t("setupSection")}</h2>
            </div>
          </div>

          <div className="docs-step-list">
            {setupSteps.map((step, index) => (
              <section className="docs-step" id={step.id} key={step.id}>
                <div className="docs-step-number">{index + 1}</div>
                <div className="docs-step-body">
                  <h3>{step.title}</h3>
                  <p>{step.body}</p>
                  {step.note ? <p className="docs-note">{step.note}</p> : null}
                  {step.images ? <ImageStrip images={step.images} /> : null}
                </div>
              </section>
            ))}
          </div>
        </section>

        <section className="docs-section" id="floating-button">
          <div className="docs-section-heading">
            <MousePointerClick size={24} aria-hidden="true" />
            <div>
              <h2>{t("floatingSection")}</h2>
              <p>{t("floatingIntro")}</p>
            </div>
          </div>

          <div className="docs-callout">
            <AlertCircle size={20} aria-hidden="true" />
            <p>{t("callout")}</p>
          </div>

          <div className="docs-mode-grid">
            {modeCards.map((mode) => {
              const Icon = mode.icon;
              return (
                <article className="docs-mode-card" key={mode.id}>
                  <Icon size={20} aria-hidden="true" />
                  <h3>{mode.title}</h3>
                  <p>{mode.body}</p>
                </article>
              );
            })}
          </div>

          <div className="docs-step-list">
            {buttonGuides.map((guide) => (
              <section className="docs-step" id={guide.id} key={guide.id}>
                <div className="docs-step-number">
                  {guide.id === "floating-menu" ? (
                    <Settings size={18} aria-hidden="true" />
                  ) : guide.id === "translation-log" ? (
                    <MessageSquareText size={18} aria-hidden="true" />
                  ) : guide.id === "battle-subtitles" ? (
                    <Captions size={18} aria-hidden="true" />
                  ) : (
                    <CirclePlay size={18} aria-hidden="true" />
                  )}
                </div>
                <div className="docs-step-body">
                  <h3>{guide.title}</h3>
                  <p>{guide.body}</p>
                  <ImageStrip images={guide.images} />
                </div>
              </section>
            ))}
          </div>
        </section>
      </article>

      <aside className="docs-toc" aria-label={common("onThisPage")}>
        <div className="docs-toc-title">{common("onThisPage")}</div>
        {tocDefs.map((item) => (
          <a href={item.href} key={item.href}>
            {t(`toc.${item.key}`)}
          </a>
        ))}
      </aside>
    </div>
  );
}