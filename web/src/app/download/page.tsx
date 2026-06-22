import type { Metadata } from "next";
import type { CSSProperties } from "react";
import { siteConfig } from "@/data/site";

export const metadata: Metadata = {
  title: "下载"
};

const downloadOptions = [
  {
    title: "网盘下载 (推荐)",
    subtitle: "OneDrive",
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
    title: "本地下载",
    subtitle: "AWS S3",
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
          <p>
            感谢您下载 FgoGotran。请选择合适的下载方式；
          </p>
        </div>
      </section>

      <section className="section section-band compact">
        <div className="download-intro">
          <h2>FgoGotran Android APK</h2>
          <p>请选择一个下载源。具体更新内容可查看更新日志。</p>
          <div className="download-facts" aria-label="安装信息">
            <span>系统要求：Android 11+</span>
            <span>软件大小：发布后填写</span>
          </div>
        </div>

        <div className="download-source-grid">
          {downloadOptions.map((option) => {
            return (
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
                <span className="download-source-subtitle">({option.subtitle})</span>
              </a>
            );
          })}
        </div>
      </section>
    </>
  );
}
