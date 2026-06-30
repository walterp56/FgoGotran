import type { Metadata } from "next";
import type { CSSProperties } from "react";
import { siteConfig } from "@/data/site";

export const metadata: Metadata = {
  title: "下载"
};

const downloadOptions = [
  {
    title: "OneDrive 下载",
    subtitle: "APK 下载",
    href: "https://1drv.ms/u/c/9e1fdb13e6d5d039/IQAnTVAWTRNNTovyxyX-fVEVAWTCIBP_CxRWVjI8nJBnMBg?e=BK4FhP",
    iconSrc: "/download-icons/onedrive.svg",
    iconAlt: "OneDrive",
    accentColor: "#0078d4",
    disabled: false
  },
  {
    title: "GitHub Releases",
    subtitle: "APK 下载",
    href: siteConfig.githubReleasesUrl,
    iconSrc: "/download-icons/github.svg",
    iconAlt: "GitHub",
    accentColor: "#181717",
    disabled: false
  }
];

export default function DownloadPage() {
  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">Download</p>
          <h1>下载链接</h1>
          <p>感谢下载FgoGotran。</p>
        </div>
      </section>

      <section className="section section-band compact">
        <div className="download-intro">
          <h2>FgoGotran Android APK</h2>
          <p>请选择一个可用下载源。OneDrive 与 GitHub Releases 会提供相同 APK。</p>
          <div className="download-facts" aria-label="下载说明">
            <span>支持 Android 11+</span>
            <span>APK 大小：58.60 MB</span>
            <span>需要自备翻译 API Key</span>
          </div>
          <div className="download-reminder" role="note">
            <strong>安装提醒：</strong>
            若手机提示因为Google Play Protect而无法安装，或下载包需要分包安装，可先安装{" "}
            <a
              href="https://play.google.com/store/apps/details?id=com.apkmirror.helper.prod"
              target="_blank"
              rel="noreferrer"
            >
              APKMirror Installer
            </a>{" "}
            后再安装 FgoGotran。
          </div>
        </div>

        <div className="download-source-grid">
          {downloadOptions.map((option) => (
            <a
              className={`download-source-card${option.disabled ? " is-disabled" : ""}`}
              href={option.disabled ? undefined : option.href}
              key={option.title}
              target={option.disabled ? undefined : "_blank"}
              rel={option.disabled ? undefined : "noreferrer"}
              aria-disabled={option.disabled}
              style={{ "--download-accent": option.accentColor } as CSSProperties}
            >
              <span className="download-source-icon">
                <img src={option.iconSrc} alt={option.iconAlt} />
              </span>
              <span className="download-source-title">{option.title}</span>
              <span className="download-source-subtitle">{option.subtitle}</span>
            </a>
          ))}
        </div>
      </section>
    </>
  );
}
