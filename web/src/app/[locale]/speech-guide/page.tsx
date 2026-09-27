import {
  AlertTriangle,
  Building2,
  CheckCircle2,
  Cloud,
  ExternalLink,
  KeyRound,
  MapPin,
  PlayCircle,
  WalletCards
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { Link } from "@/i18n/navigation";
import { pageMetadata } from "@/lib/seo";

type SourceLinkDef = {
  key: string;
  href: string;
};

type StepDef = {
  id: string;
  icon: LucideIcon;
  images: string[];
};

const stepDefs: StepDef[] = [
  { id: "azure-account", icon: WalletCards, images: ["image1", "image2", "image3"] },
  { id: "create-speech", icon: Cloud, images: ["image4", "image5"] },
  { id: "select-region", icon: MapPin, images: ["image6"] },
  { id: "copy-key", icon: KeyRound, images: ["image7"] },
  { id: "app-settings", icon: CheckCircle2, images: [] },
  { id: "test-voice", icon: PlayCircle, images: [] }
];

const sourceLinkDefs: SourceLinkDef[] = [
  { key: "account", href: "https://azure.microsoft.com/en-us/pricing/purchase-options/azure-account" },
  { key: "pricing", href: "https://azure.microsoft.com/en-us/pricing/details/speech/" },
  { key: "quickstart", href: "https://learn.microsoft.com/en-us/azure/ai-services/speech-service/get-started-text-to-speech" },
  { key: "regions", href: "https://learn.microsoft.com/en-us/azure/ai-services/speech-service/regions" },
  { key: "china", href: "https://learn.microsoft.com/en-us/azure/ai-services/speech-service/sovereign-clouds" }
];

const troubleshootingKeys = ["empty", "auth", "slow", "quota"];

const tocDefs = [
  { href: "#choose-cloud", key: "chooseCloud" },
  { href: "#global-setup", key: "globalSetup" },
  { href: "#china-azure", key: "chinaAzure" },
  { href: "#troubleshooting", key: "troubleshooting" },
  { href: "#sources", key: "sources" }
];

type SpeechGuidePageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: SpeechGuidePageProps) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "speechGuide" });
  return pageMetadata(locale, "speech-guide", { title: t("title"), description: t("description") });
}

export default async function SpeechGuidePage({ params }: SpeechGuidePageProps) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("speechGuide");
  const common = await getTranslations("common");

  const steps = stepDefs.map((def) => ({
    ...def,
    title: t(`steps.${def.id}.title`),
    body: t(`steps.${def.id}.body`),
    note: t.has(`steps.${def.id}.note`) ? (t.raw(`steps.${def.id}.note`) as string) : undefined,
    value: t.has(`steps.${def.id}.value`) ? (t.raw(`steps.${def.id}.value`) as string) : undefined
  }));

  const troubleshooting = troubleshootingKeys.map((key) => ({
    key,
    title: t(`troubleshooting.items.${key}.title`),
    body: t(`troubleshooting.items.${key}.body`)
  }));

  const sourceLinks = sourceLinkDefs.map((def) => ({
    ...def,
    label: t(`sources.links.${def.key}`)
  }));

  return (
    <div className="docs-shell azure-guide-shell">
      <aside className="docs-sidebar" aria-label={t("sidebarTitle")}>
        <div className="docs-sidebar-title">{t("sidebarTitle")}</div>
        <a href="#choose-cloud">
          <Cloud size={16} aria-hidden="true" />
          {t("sidebar.chooseCloud")}
        </a>
        <a href="#global-setup">
          <KeyRound size={16} aria-hidden="true" />
          {t("sidebar.applyKey")}
        </a>
        <a href="#china-azure">
          <Building2 size={16} aria-hidden="true" />
          {t("sidebar.chinaAzure")}
        </a>
      </aside>

      <article className="docs-article">
        <header className="docs-hero azure-docs-hero">
          <p className="eyebrow">{t("eyebrow")}</p>
          <h1>{t("h1")}</h1>
          <div className="azure-hero-actions">
            <a className="primary-button" href="https://portal.azure.com/" target="_blank" rel="noreferrer">
              {t("openGlobalAzure")}
              <ExternalLink size={16} aria-hidden="true" />
            </a>
            <Link className="secondary-button" href="/guide">
              {t("backToGuide")}
            </Link>
          </div>
        </header>

        <section className="docs-scope-panel azure-choice-panel" id="choose-cloud">
          <div className="azure-choice-grid">
            <article className="azure-cloud-card recommended">
              <div className="azure-cloud-card-head">
                <Cloud size={22} aria-hidden="true" />
                <span className="api-recommend-badge">{common("recommended")}</span>
              </div>
              <h3>{t("globalStepsCard")}</h3>
              <code className="azure-code-value">southeastasia</code>
              <a href="https://portal.azure.com/" target="_blank" rel="noreferrer">
                portal.azure.com
                <ExternalLink size={14} aria-hidden="true" />
              </a>
            </article>

            <article className="azure-cloud-card">
              <div className="azure-cloud-card-head">
                <Building2 size={22} aria-hidden="true" />
              </div>
              <h3>Azure China</h3>
              <code className="azure-code-value">chinanorth3</code>
              <a href="https://portal.azure.cn/" target="_blank" rel="noreferrer">
                portal.azure.cn
                <ExternalLink size={14} aria-hidden="true" />
              </a>
            </article>
          </div>
        </section>

        <section className="docs-section" id="global-setup">
          <div className="docs-section-heading">
            <KeyRound size={24} aria-hidden="true" />
            <div>
              <h2>{t("globalSetup")}</h2>
            </div>
          </div>

          <div className="docs-step-list">
            {steps.map((step, index) => {
              const Icon = step.icon;
              return (
                <section className="docs-step" id={step.id} key={step.id}>
                  <div className="docs-step-number">{index + 1}</div>
                  <div className="docs-step-body azure-step-body">
                    <div className="azure-step-title">
                      <Icon size={19} aria-hidden="true" />
                      <h3>{step.title}</h3>
                    </div>
                    <p>{step.body}</p>
                    {step.value ? <code className="azure-code-value">{step.value}</code> : null}
                    {step.note ? <p className="docs-note">{step.note}</p> : null}
                    {step.images.map((image) => (
                      <figure className="azure-step-image-frame" key={image}>
                        <img
                          src={`/speech-guide/${image}.png`}
                          alt={`Azure Speech guide screenshot ${image}`}
                          loading="lazy"
                        />
                      </figure>
                    ))}
                  </div>
                </section>
              );
            })}
          </div>
        </section>

        <section className="docs-section" id="china-azure">
          <div className="docs-section-heading">
            <Building2 size={24} aria-hidden="true" />
            <div>
              <h2>{t("chinaAzure.title")}</h2>
              <p>{t("chinaAzure.body")}</p>
            </div>
          </div>

          <div className="docs-callout azure-warning-callout">
            <AlertTriangle size={20} aria-hidden="true" />
            <p>{t("chinaAzure.callout")}</p>
          </div>

          <div className="api-detail-grid">
            <article className="api-detail-card">
              <div className="guide-label">Portal</div>
              <h2>{t("chinaAzure.portalTitle")}</h2>
              <p>{t("chinaAzure.portalBody")}</p>
              <code>{t("chinaAzure.portalValue")}</code>
            </article>
            <article className="api-detail-card">
              <div className="guide-label">Region</div>
              <h2>{t("chinaAzure.regionTitle")}</h2>
              <p>{t("chinaAzure.regionBody")}</p>
              <code>{t("chinaAzure.regionValue")}</code>
            </article>
          </div>
        </section>

        <section className="docs-section" id="troubleshooting">
          <div className="docs-section-heading">
            <AlertTriangle size={24} aria-hidden="true" />
            <div>
              <h2>{t("troubleshooting.title")}</h2>
              <p>{t("troubleshooting.body")}</p>
            </div>
          </div>

          <div className="api-detail-grid">
            {troubleshooting.map((item) => (
              <article className="api-detail-card" key={item.key}>
                <div className="guide-label">Check</div>
                <h2>{item.title}</h2>
                <p>{item.body}</p>
              </article>
            ))}
          </div>
        </section>

        <section className="docs-section" id="sources">
          <div className="docs-section-heading">
            <ExternalLink size={24} aria-hidden="true" />
            <div>
              <h2>{t("sources.title")}</h2>
              <p>{t("sources.body")}</p>
            </div>
          </div>

          <div className="api-source-list azure-source-list">
            {sourceLinks.map((source) => (
              <a href={source.href} key={source.href} target="_blank" rel="noreferrer">
                {source.label}
                <ExternalLink size={14} aria-hidden="true" />
              </a>
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