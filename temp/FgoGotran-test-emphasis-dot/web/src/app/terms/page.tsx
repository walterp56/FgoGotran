import type { Metadata } from "next";
import { TermsExplorer } from "@/components/TermsExplorer";
import { SectionHeader } from "@/components/SectionHeader";
import { cdnUrl } from "@/lib/cdn";
import { siteConfig } from "@/data/site";

export const metadata: Metadata = {
  title: "术语库"
};

export default function TermsPage() {
  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">Terminology</p>
          <h1>查看姓名和术语 TSV</h1>
          <p>
            这里给用户检查日文到简体中文的固定翻译。当前页面会优先读取 CDN 预览 JSON；
            如果还没发布预览文件，会显示内置示例。
          </p>
        </div>
      </section>

      <section className="section">
        <SectionHeader
          title="术语搜索"
          body="搜索结果用于查看，不直接修改数据库。后续接后端后，可以在这里加用户提交修正。"
        />
        <TermsExplorer />
      </section>

      <section className="section section-band compact">
        <SectionHeader title="未来 CDN 预览文件" />
        <div className="download-grid">
          <article className="download-card">
            <h3>角色姓名预览</h3>
            <code className="code-path">{cdnUrl(siteConfig.characterPreviewPath)}</code>
            <p>从 `character_names.tsv` 生成。</p>
          </article>
          <article className="download-card">
            <h3>术语预览</h3>
            <code className="code-path">{cdnUrl(siteConfig.termsPreviewPath)}</code>
            <p>从 `term.tsv` 生成。</p>
          </article>
        </div>
      </section>
    </>
  );
}
