import type { CSSProperties } from "react";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { Link } from "@/i18n/navigation";
import { siteConfig } from "@/data/site";
import { pageMetadata } from "@/lib/seo";

const currentAppVersion = "v4.0.0";

const downloadOptions = [
  {
    key: "onedrive",
    href: "https://1drv.ms/f/c/9e1fdb13e6d5d039/IgDO_uKE7yZWT6cPSy-v_LlnAW4zG9Wv51mwltpuVAVI9HI?e=uEzfCt",
    iconSrc: "/download-icons/onedrive.svg",
    accentColor: "#0078d4"
  },
  {
    key: "github",
    href: siteConfig.githubReleasesUrl,
    iconSrc: "/download-icons/github.svg",
    accentColor: "#181717"
  },
  {
    key: "baidu",
    href: "https://pan.baidu.com/s/1mbPdcGRHErRIxHcY0ujH5A?pwd=y978",
    iconSrc: "/download-icons/baidu-netdisk.svg",
    accentColor: "#06A7FF"
  }
];

type DownloadPageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: DownloadPageProps) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "download" });
  return pageMetadata(locale, "download", { title: t("title") });
}

export default async function DownloadPage({ params }: DownloadPageProps) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("download");

  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">{t("eyebrow")}</p>
          <h1>{t("h1")}</h1>
          <p>{t("body")}</p>
        </div>
      </section>

      <section className="section section-band compact">
        <div className="download-intro">
          <h2>
            FgoGotran Android APK {currentAppVersion}
          </h2>
          <p>
            {t("introBefore")}{" "}
            <Link className="download-update-link" href="/changelog">
              {t("changelogLink")}
            </Link>
            {t("introAfter")}
          </p>
          <div className="download-facts" aria-label={t("h1")}>
            <span>{t("facts.version", { version: currentAppVersion })}</span>
            <span>{t("facts.android")}</span>
            <span>{t("facts.size")}</span>
            <span>{t("facts.apiKey")}</span>
          </div>
          <div className="download-reminder" role="note">
            <strong>{t("installReminderTitle")}</strong>
            {t("installReminderBefore")}{" "}
            <a href="https://play.google.com/store/apps/details?id=com.apkmirror.helper.prod" target="_blank" rel="noreferrer">
              {t("installer")}
            </a>{" "}
            {t("installReminderAfter")}
          </div>
          <div className="download-reminder" role="note">
            <strong>{t("localReminderTitle")}</strong>
            {t("localReminderBefore")}{" "}
            <Link href="/local-guide">{t("localGuide")}</Link>
            {t("localReminderAfter")}
          </div>
        </div>

        <div className="download-source-grid">
          {downloadOptions.map((option) => (
            <a
              className="download-source-card"
              href={option.href}
              key={option.key}
              target="_blank"
              rel="noreferrer"
              style={{ "--download-accent": option.accentColor } as CSSProperties}
            >
              <span className="download-source-icon">
                <img src={option.iconSrc} alt={t(`options.${option.key}.title`)} />
              </span>
              <span className="download-source-title">{t(`options.${option.key}.title`)}</span>
              <span className="download-source-subtitle">{t(`options.${option.key}.subtitle`)}</span>
            </a>
          ))}
        </div>
      </section>
    </>
  );
}