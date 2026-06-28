import type { Metadata } from "next";
import type { CSSProperties } from "react";
import { siteConfig } from "@/data/site";

export const metadata: Metadata = {
  title: "下载"
};

const downloadOptions = [
  {
    title: "OneDrive 下载",
    subtitle: "准备中",
    href: "#",
    iconSrc: "/download-icons/onedrive.svg",
    iconAlt: "OneDrive",
    accentColor: "#0078d4",
    disabled: true
  },
  {
    title: "GitHub 下载",
    subtitle: "Release",
    href: `${siteConfig.githubUrl}/releases`,
    iconSrc: "/download-icons/github.svg",
    iconAlt: "GitHub",
    accentColor: "#181717",
    disabled: false
  },
  {
    title: "AWS S3 下载",
    subtitle: "准备中",
    href: "#",
    iconSrc: "/download-icons/aws.svg",
    iconAlt: "AWS",
    accentColor: "#ff9900",
    disabled: true
  }
];

export default function DownloadPage() {
  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">Download</p>
          <h1>下载 FgoGotran</h1>
          <p>下载安装 Android APK 后，按使用指南开启权限并配置翻译 API。</p>
        </div>
      </section>

      <section className="section section-band compact">
        <div className="download-intro">
          <h2>FgoGotran Android APK</h2>
          <p>请选择一个可用下载源。当前推荐从 GitHub Release 获取最新 APK。</p>
          <div className="download-facts" aria-label="下载说明">
            <span>支持 Android 11+</span>
            <span>需要自备翻译 API Key</span>
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
