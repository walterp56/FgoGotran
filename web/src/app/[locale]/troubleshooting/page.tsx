import {
  AlertTriangle,
  FileText,
  KeyRound,
  MonitorSmartphone,
  Settings,
  Smartphone,
  Volume2,
  WifiOff,
  Wrench
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { Link } from "@/i18n/navigation";
import {
  logStepDefs,
  troubleshootingGroupDefs,
  troubleshootingTocDefs
} from "@/data/troubleshooting";
import { pageMetadata } from "@/lib/seo";

const groupIcons: Record<string, LucideIcon> = {
  phone: Smartphone,
  emulator: MonitorSmartphone,
  projection: WifiOff,
  "error-meaning": AlertTriangle
};

type TroubleshootingPageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: TroubleshootingPageProps) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "troubleshooting" });
  return pageMetadata(locale, "troubleshooting", { title: t("title"), description: t("description") });
}

export default async function TroubleshootingPage({ params }: TroubleshootingPageProps) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("troubleshooting");
  const common = await getTranslations("common");

  const logSteps = logStepDefs.map((key) => ({
    key,
    title: t(`logSteps.${key}.title`),
    body: t(`logSteps.${key}.body`)
  }));

  const groups = troubleshootingGroupDefs.map((group) => ({
    ...group,
    label: t(`groups.${group.id}.label`),
    intro: t(`groups.${group.id}.intro`),
    items: group.items.map((itemKey) => ({
      key: itemKey,
      title: t(`groups.${group.id}.items.${itemKey}.title`),
      cause: t(`groups.${group.id}.items.${itemKey}.cause`),
      steps: t.raw(`groups.${group.id}.items.${itemKey}.steps`) as string[],
      verify: t.has(`groups.${group.id}.items.${itemKey}.verify`) ? (t.raw(`groups.${group.id}.items.${itemKey}.verify`) as string) : undefined
    }))
  }));

  return (
    <div className="docs-shell trouble-guide-shell">
      <aside className="docs-sidebar" aria-label={t("sidebarTitle")}>
        <div className="docs-sidebar-title">{t("sidebarTitle")}</div>
        <a href="#error-log">
          <FileText size={16} aria-hidden="true" />
          {t("sidebarErrorLog")}
        </a>
        {groups.map((group) => {
          const GroupIcon = groupIcons[group.id] ?? Wrench;
          return (
            <a key={group.id} href={`#${group.id}`}>
              <GroupIcon size={16} aria-hidden="true" />
              {group.label}
            </a>
          );
        })}
      </aside>

      <article className="docs-article">
        <header className="docs-hero">
          <p className="eyebrow">{t("eyebrow")}</p>
          <h1>{t("h1")}</h1>
          <p>{t("intro")}</p>

          <div className="trouble-jump-nav" aria-label={t("jumpPrimary")}>
            <a className="jump-primary" href="#error-log">
              <FileText size={15} aria-hidden="true" />
              {t("jumpPrimary")}
            </a>
            {groups.map((group) => {
              const GroupIcon = groupIcons[group.id] ?? Wrench;
              return (
                <a key={group.id} href={`#${group.id}`}>
                  <GroupIcon size={15} aria-hidden="true" />
                  {group.label}
                </a>
              );
            })}
          </div>

          <div className="azure-hero-actions">
            <Link className="secondary-button" href="/guide">
              <Settings size={16} aria-hidden="true" />
              {t("actionGuide")}
            </Link>
            <Link className="secondary-button" href="/api-guide">
              <KeyRound size={16} aria-hidden="true" />
              {t("actionApiGuide")}
            </Link>
            <Link className="secondary-button" href="/speech-guide">
              <Volume2 size={16} aria-hidden="true" />
              {t("actionSpeechGuide")}
            </Link>
          </div>
        </header>

        <section className="docs-section" id="error-log">
          <div className="docs-section-heading">
            <FileText size={24} aria-hidden="true" />
            <div>
              <h2>{t("errorLog.title")}</h2>
              <p>{t("errorLog.body")}</p>
            </div>
          </div>

          <div className="docs-callout">
            <AlertTriangle size={20} aria-hidden="true" />
            <p>{t("errorLog.callout")}</p>
          </div>

          <div className="docs-step-list">
            {logSteps.map((step, index) => (
              <section className="docs-step" key={step.key}>
                <div className="docs-step-number">{index + 1}</div>
                <div className="docs-step-body">
                  <h3>{step.title}</h3>
                  <p>{step.body}</p>
                </div>
              </section>
            ))}
          </div>
        </section>

        {groups.map((group) => {
          const GroupIcon = groupIcons[group.id] ?? Wrench;
          return (
            <section className="docs-section" id={group.id} key={group.id}>
              <div className="docs-section-heading">
                <GroupIcon size={24} aria-hidden="true" />
                <div>
                  <h2>{group.label}</h2>
                  <p>{group.intro}</p>
                </div>
              </div>

              <div className="qa-list">
                {group.items.map((item) => (
                  <details key={item.key}>
                    <summary>{item.title}</summary>
                    <div className="qa-body">
                      <p className="qa-cause">{item.cause}</p>
                      <ol className="qa-steps">
                        {item.steps.map((step) => (
                          <li key={step}>{step}</li>
                        ))}
                      </ol>
                      {item.verify ? (
                        <p className="qa-verify">
                          {t("verifyPrefix")}
                          {item.verify}
                        </p>
                      ) : null}
                      <p className="qa-escalate">{t("escalate")}</p>
                    </div>
                  </details>
                ))}
              </div>
            </section>
          );
        })}
      </article>

      <aside className="docs-toc" aria-label={common("onThisPage")}>
        <div className="docs-toc-title">{common("onThisPage")}</div>
        {troubleshootingTocDefs.map((item) => (
          <a href={item.href} key={item.href}>
            {t(`toc.${item.key}`)}
          </a>
        ))}
      </aside>
    </div>
  );
}