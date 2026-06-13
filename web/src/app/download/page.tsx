import type { Metadata } from "next";
import { Database, Download, GitBranch, ShieldCheck } from "lucide-react";
import { ReleaseStatus } from "@/components/ReleaseStatus";
import { SectionHeader } from "@/components/SectionHeader";
import { cdnUrl } from "@/lib/cdn";
import { siteConfig } from "@/data/site";

export const metadata: Metadata = {
  title: "下载"
};

export default function DownloadPage() {
  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">Download</p>
          <h1>下载 FgoGotran</h1>
          <p>
            APK 建议通过 CDN 发布，并在 GitHub Releases 保留镜像。页面会读取最新 manifest，
            展示版本、大小和校验信息。
          </p>
        </div>
      </section>

      <section className="section">
        <ReleaseStatus />
      </section>

      <section className="section section-band compact">
        <SectionHeader
          title="发布文件结构"
          body="后续 CI/CD 生成 APK 后，把 versioned 文件先上传，再更新 latest manifest。"
        />
        <div className="download-grid">
          <article className="download-card">
            <div className="download-card-head">
              <h3>APK manifest</h3>
              <Download size={20} aria-hidden="true" />
            </div>
            <code className="code-path">{cdnUrl(siteConfig.apkManifestPath)}</code>
            <p>网站下载页读取这里来显示最新版 APK。Android App 暂时不需要读取它。</p>
          </article>
          <article className="download-card">
            <div className="download-card-head">
              <h3>DB manifest</h3>
              <Database size={20} aria-hidden="true" />
            </div>
            <code className="code-path">{cdnUrl(siteConfig.dbManifestPath)}</code>
            <p>Android App 已使用这个地址检查并更新术语数据库。</p>
          </article>
          <article className="download-card">
            <div className="download-card-head">
              <h3>GitHub 镜像</h3>
              <GitBranch size={20} aria-hidden="true" />
            </div>
            <p>建议每次正式版同时上传 GitHub Release，方便用户校验和回退。</p>
            <div className="download-actions">
              <a className="secondary-button small" href={`${siteConfig.githubUrl}/releases`} target="_blank" rel="noreferrer">
                打开 Releases
              </a>
            </div>
          </article>
          <article className="download-card">
            <div className="download-card-head">
              <h3>安装要求</h3>
              <ShieldCheck size={20} aria-hidden="true" />
            </div>
            <p>Android 11+。需要悬浮窗、无障碍服务、网络权限；建议关闭电池优化。</p>
          </article>
        </div>
      </section>
    </>
  );
}
