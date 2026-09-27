import {
  AlertTriangle,
  BookOpenCheck,
  Boxes,
  CheckCircle2,
  CircleGauge,
  CloudOff,
  Cpu,
  ExternalLink,
  FileCog,
  FolderOpen,
  KeyRound,
  Laptop,
  Network,
  Play,
  RotateCcw,
  Server,
  Settings2,
  ShieldCheck,
  Smartphone,
  TestTube2,
  Wrench
} from "lucide-react";
import type { ReactNode } from "react";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { Link } from "@/i18n/navigation";
import {
  localControlTabKeys,
  localGuideTocDefs,
  localParameterKeys,
  localQuickStartKeys,
  localStartingPointKeys,
  localStatusDefs,
  localTroubleshootingKeys,
  localTuningKeys
} from "@/data/localGuide";
import { pageMetadata } from "@/lib/seo";

type LocalGuidePageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: LocalGuidePageProps) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "localGuide" });
  return pageMetadata(locale, "local-guide", { title: t("title"), description: t("description") });
}

// The <ip>…</ip> tag in the endpoint strings keeps the ASCII angle brackets out
// of ICU parsing; this handler renders them as the literal <…> placeholder.
const placeholderTag = {
  ip: (chunks: ReactNode) => (
    <>
      {"<"}
      {chunks}
      {">"}
    </>
  )
};

export default async function LocalGuidePage({ params }: LocalGuidePageProps) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("localGuide");
  const common = await getTranslations("common");

  const heroMeta = t.raw("meta") as string[];
  const phoneSteps = t.raw("phone.steps") as string[];
  const checklistItems = t.raw("checklist.items") as string[];
  const llamaItems = t.raw("chooseFiles.llamaItems") as string[];
  const ggufItems = t.raw("chooseFiles.ggufItems") as string[];

  const quickStartSteps = localQuickStartKeys.map((key) => ({
    key,
    title: t(`quickStart.steps.${key}.title`),
    body: t(`quickStart.steps.${key}.body`),
    note: t.has(`quickStart.steps.${key}.note`) ? (t.raw(`quickStart.steps.${key}.note`) as string) : undefined
  }));

  const controlTabs = localControlTabKeys.map((key) => ({
    key,
    title: t(`controlPanel.tabs.${key}.title`),
    body: t(`controlPanel.tabs.${key}.body`)
  }));

  const parameters = localParameterKeys.map((key) => ({
    key,
    name: t(`controlPanel.parameters.${key}.name`),
    defaultValue: t(`controlPanel.parameters.${key}.defaultValue`),
    guidance: t(`controlPanel.parameters.${key}.guidance`)
  }));

  const statuses = localStatusDefs.map((status) => ({
    ...status,
    label: t(`status.items.${status.code}.label`),
    meaning: t(`status.items.${status.code}.meaning`)
  }));

  const tuningSteps = localTuningKeys.map((key) => ({
    key,
    title: t(`performance.steps.${key}.title`),
    body: t(`performance.steps.${key}.body`)
  }));

  const troubleshooting = localTroubleshootingKeys.map((key) => ({
    key,
    title: t(`troubleshooting.items.${key}.title`),
    body: t(`troubleshooting.items.${key}.body`)
  }));

  const startingPoints = localStartingPointKeys.map((key) => ({
    key,
    memory: t(`chooseFiles.startingPoints.${key}.memory`),
    model: t(`chooseFiles.startingPoints.${key}.model`),
    note: t(`chooseFiles.startingPoints.${key}.note`)
  }));

  return (
    <div className="docs-shell local-guide-shell">
      <aside className="docs-sidebar" aria-label={t("sidebarTitle")}>
        <div className="docs-sidebar-title">{t("sidebarTitle")}</div>
        <a href="#quick-start">
          <Play size={16} aria-hidden="true" />
          {t("sidebar.quickStart")}
        </a>
        <a href="#control-panel">
          <Settings2 size={16} aria-hidden="true" />
          {t("sidebar.controlPanel")}
        </a>
        <a href="#phone">
          <Smartphone size={16} aria-hidden="true" />
          {t("sidebar.phone")}
        </a>
        <a href="#troubleshooting">
          <Wrench size={16} aria-hidden="true" />
          {t("sidebar.troubleshooting")}
        </a>
      </aside>

      <article className="docs-article">
        <header className="docs-hero local-docs-hero">
          <p className="eyebrow">{t("eyebrow")}</p>
          <h1>{t("h1")}</h1>
          <p>{t("intro")}</p>
          <div className="docs-meta" aria-label={t("intro")}>
            {heroMeta.map((item) => (
              <span key={item}>{item}</span>
            ))}
          </div>
          <div className="local-hero-actions">
            <a className="primary-button" href="#quick-start">
              <Play size={17} aria-hidden="true" />
              {t("heroStart")}
            </a>
            <Link className="secondary-button" href="/api-guide">
              {t("heroCloudGuide")}
            </Link>
          </div>
        </header>

        <section className="docs-scope-panel" id="scope">
          <div className="docs-scope-heading">
            <BookOpenCheck size={22} aria-hidden="true" />
            <div>
              <h2>{t("scope.title")}</h2>
              <p>{t("scope.body")}</p>
            </div>
          </div>
          <div className="docs-scope-grid">
            <article className="docs-scope-card supported">
              <CloudOff size={20} aria-hidden="true" />
              <div>
                <h3>{t("scope.supported.title")}</h3>
                <p>{t("scope.supported.body")}</p>
              </div>
            </article>
            <article className="docs-scope-card unsupported">
              <Boxes size={20} aria-hidden="true" />
              <div>
                <h3>{t("scope.unsupported.title")}</h3>
                <p>{t("scope.unsupported.body")}</p>
              </div>
            </article>
          </div>
          <div className="docs-callout local-inline-callout">
            <AlertTriangle size={20} aria-hidden="true" />
            <p>{t("scope.callout")}</p>
          </div>
        </section>

        <section className="docs-section" id="architecture">
          <div className="docs-section-heading">
            <Network size={24} aria-hidden="true" />
            <div>
              <h2>{t("architecture.title")}</h2>
              <p>{t("architecture.body")}</p>
            </div>
          </div>

          <div className="local-network-diagram" aria-label={t("architecture.title")}>
            <div className="local-network-node">
              <Smartphone size={25} aria-hidden="true" />
              <strong>{t("architecture.phone")}</strong>
              <span>{t("architecture.phoneApp")}</span>
            </div>
            <div className="local-network-link">
              <span>{t("architecture.trustedLan")}</span>
              <span aria-hidden="true">→</span>
            </div>
            <div className="local-network-node primary">
              <Server size={25} aria-hidden="true" />
              <strong>{t("architecture.translationEndpoint")}</strong>
              <code>{t("architecture.translationEndpointValue")}</code>
            </div>
            <div className="local-network-link short" aria-hidden="true">→</div>
            <div className="local-network-node">
              <Cpu size={25} aria-hidden="true" />
              <strong>{t("architecture.llamaServer")}</strong>
              <span>{t("architecture.ggufModel")}</span>
            </div>
          </div>

          <div className="local-control-address">
            <Laptop size={21} aria-hidden="true" />
            <div>
              <strong>
                {t("architecture.controlAddress")}
                <code>http://127.0.0.1:18081</code>
              </strong>
              <p>{t("architecture.controlAddressBody")}</p>
            </div>
          </div>
        </section>

        <section className="docs-section" id="quick-start">
          <div className="docs-section-heading">
            <Play size={24} aria-hidden="true" />
            <div>
              <h2>{t("quickStart.title")}</h2>
              <p>{t("quickStart.body")}</p>
            </div>
          </div>

          <div className="local-source-links" aria-label={t("quickStart.title")}>
            <a href="https://www.python.org/downloads/windows/" target="_blank" rel="noreferrer">
              {t("quickStart.pythonLink")}
              <ExternalLink size={15} aria-hidden="true" />
            </a>
            <a href="https://github.com/ggml-org/llama.cpp/releases" target="_blank" rel="noreferrer">
              {t("quickStart.llamaLink")}
              <ExternalLink size={15} aria-hidden="true" />
            </a>
          </div>

          <div className="docs-step-list">
            {quickStartSteps.map((step, index) => (
              <section className="docs-step" key={step.key}>
                <div className="docs-step-number">{index + 1}</div>
                <div className="docs-step-body">
                  <h3>{step.title}</h3>
                  <p>{step.body}</p>
                  {step.note ? <p className="docs-note">{step.note}</p> : null}
                </div>
              </section>
            ))}
          </div>
        </section>

        <section className="docs-section" id="choose-files">
          <div className="docs-section-heading">
            <FolderOpen size={24} aria-hidden="true" />
            <div>
              <h2>{t("chooseFiles.title")}</h2>
              <p>{t("chooseFiles.body")}</p>
            </div>
          </div>

          <div className="local-choice-grid">
            <article className="local-choice-card">
              <Server size={21} aria-hidden="true" />
              <h3>{t("chooseFiles.llamaTitle")}</h3>
              <ul>
                {llamaItems.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </article>
            <article className="local-choice-card">
              <Boxes size={21} aria-hidden="true" />
              <h3>{t("chooseFiles.ggufTitle")}</h3>
              <ul>
                {ggufItems.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </article>
          </div>

          <div className="local-table-wrap">
            <table className="local-guide-table">
              <caption>{t("chooseFiles.tableCaption")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("chooseFiles.tableVram")}</th>
                  <th scope="col">{t("chooseFiles.tableStart")}</th>
                  <th scope="col">{t("chooseFiles.tableNoteCol")}</th>
                </tr>
              </thead>
              <tbody>
                {startingPoints.map((item) => (
                  <tr key={item.key}>
                    <td>{item.memory}</td>
                    <td>{item.model}</td>
                    <td>{item.note}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="local-table-note">{t("chooseFiles.tableNote")}</p>
        </section>

        <section className="docs-section" id="control-panel">
          <div className="docs-section-heading">
            <Settings2 size={24} aria-hidden="true" />
            <div>
              <h2>{t("controlPanel.title")}</h2>
              <p>
                {t.rich("controlPanel.body", {
                  code: (chunks) => <code>{chunks}</code>
                })}
              </p>
            </div>
          </div>

          <div className="local-panel-grid">
            {controlTabs.map((tab) => (
              <article className="local-panel-card" key={tab.key}>
                <h3>{tab.title}</h3>
                <p>{tab.body}</p>
              </article>
            ))}
          </div>

          <h3 className="local-subheading">{t("controlPanel.subheading")}</h3>
          <div className="local-table-wrap">
            <table className="local-guide-table">
              <thead>
                <tr>
                  <th scope="col">{t("controlPanel.tableParam")}</th>
                  <th scope="col">{t("controlPanel.tableDefault")}</th>
                  <th scope="col">{t("controlPanel.tableGuidance")}</th>
                </tr>
              </thead>
              <tbody>
                {parameters.map((parameter) => (
                  <tr key={parameter.key}>
                    <td>
                      <code>{parameter.name}</code>
                    </td>
                    <td>{parameter.defaultValue}</td>
                    <td>{parameter.guidance}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <section className="docs-section" id="phone">
          <div className="docs-section-heading">
            <Smartphone size={24} aria-hidden="true" />
            <div>
              <h2>{t("phone.title")}</h2>
              <p>{t("phone.body")}</p>
            </div>
          </div>

          <div className="docs-callout">
            <AlertTriangle size={20} aria-hidden="true" />
            <p>
              {t.rich("phone.callout", {
                code: (chunks) => <code>{chunks}</code>
              })}
            </p>
          </div>

          <ol className="local-numbered-list">
            {phoneSteps.map((step) => (
              <li key={step}>{step}</li>
            ))}
          </ol>

          <div className="local-endpoint-grid">
            <article>
              <span>{t("phone.apiAddress")}</span>
              <code>{t.rich("phone.apiAddressValue", placeholderTag)}</code>
            </article>
            <article>
              <span>{t("phone.browserTest")}</span>
              <code>{t.rich("phone.healthValue", placeholderTag)}</code>
            </article>
          </div>

          <div className="local-field-map">
            <div>
              <span>{t("phone.fieldComputer")}</span>
              <strong>{t("phone.fieldShowEndpoint")}</strong>
              <span aria-hidden="true">→</span>
              <strong>{t("phone.fieldApiAddress")}</strong>
            </div>
            <div>
              <span>{t("phone.fieldComputer")}</span>
              <strong>{t("phone.fieldModelId")}</strong>
              <span aria-hidden="true">→</span>
              <strong>{t("phone.fieldModel")}</strong>
            </div>
            <div>
              <span>{t("phone.fieldComputer")}</span>
              <strong>{t("phone.fieldShowApiKey")}</strong>
              <span aria-hidden="true">→</span>
              <strong>{t("phone.fieldApiKey")}</strong>
            </div>
          </div>
        </section>

        <section className="docs-section" id="status">
          <div className="docs-section-heading">
            <CircleGauge size={24} aria-hidden="true" />
            <div>
              <h2>{t("status.title")}</h2>
              <p>{t("status.body")}</p>
            </div>
          </div>

          <div className="local-status-grid">
            {statuses.map((status) => (
              <article className="local-status-card" key={status.code}>
                <div>
                  <code>{status.code}</code>
                  <span className={`local-status-pill ${status.tone}`}>{status.label}</span>
                </div>
                <p>{status.meaning}</p>
              </article>
            ))}
          </div>
        </section>

        <section className="docs-section" id="performance">
          <div className="docs-section-heading">
            <Cpu size={24} aria-hidden="true" />
            <div>
              <h2>{t("performance.title")}</h2>
              <p>{t("performance.body")}</p>
            </div>
          </div>
          <div className="docs-step-list">
            {tuningSteps.map((step, index) => (
              <section className="docs-step" key={step.key}>
                <div className="docs-step-number">{index + 1}</div>
                <div className="docs-step-body">
                  <h3>{step.title}</h3>
                  <p>{step.body}</p>
                </div>
              </section>
            ))}
          </div>
          <div className="docs-callout local-inline-callout">
            <TestTube2 size={20} aria-hidden="true" />
            <p>{t("performance.callout")}</p>
          </div>
        </section>

        <section className="docs-section" id="troubleshooting">
          <div className="docs-section-heading">
            <Wrench size={24} aria-hidden="true" />
            <div>
              <h2>{t("troubleshooting.title")}</h2>
              <p>{t("troubleshooting.body")}</p>
            </div>
          </div>
          <div className="local-trouble-list">
            {troubleshooting.map((item) => (
              <details key={item.key}>
                <summary>{item.title}</summary>
                <p>{item.body}</p>
              </details>
            ))}
          </div>
        </section>

        <section className="docs-section" id="security">
          <div className="docs-section-heading">
            <ShieldCheck size={24} aria-hidden="true" />
            <div>
              <h2>{t("security.title")}</h2>
              <p>{t("security.body")}</p>
            </div>
          </div>

          <div className="local-security-grid">
            <article>
              <ShieldCheck size={20} aria-hidden="true" />
              <h3>{t("security.network.title")}</h3>
              <p>{t("security.network.body")}</p>
            </article>
            <article>
              <KeyRound size={20} aria-hidden="true" />
              <h3>{t("security.apiKey.title")}</h3>
              <p>{t("security.apiKey.body")}</p>
            </article>
            <article>
              <FileCog size={20} aria-hidden="true" />
              <h3>{t("security.backup.title")}</h3>
              <p>{t("security.backup.body")}</p>
            </article>
            <article>
              <RotateCcw size={20} aria-hidden="true" />
              <h3>{t("security.update.title")}</h3>
              <p>{t("security.update.body")}</p>
            </article>
          </div>
        </section>

        <section className="docs-section" id="checklist">
          <div className="docs-section-heading">
            <CheckCircle2 size={24} aria-hidden="true" />
            <div>
              <h2>{t("checklist.title")}</h2>
              <p>{t("checklist.body")}</p>
            </div>
          </div>
          <ul className="local-checklist">
            {checklistItems.map((item) => (
              <li key={item}>
                <CheckCircle2 size={18} aria-hidden="true" />
                <span>{item}</span>
              </li>
            ))}
          </ul>
        </section>
      </article>

      <aside className="docs-toc" aria-label={common("onThisPage")}>
        <div className="docs-toc-title">{common("onThisPage")}</div>
        {localGuideTocDefs.map((item) => (
          <a href={item.href} key={item.href}>
            {t(`toc.${item.key}`)}
          </a>
        ))}
      </aside>
    </div>
  );
}