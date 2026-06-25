import { readdirSync } from "node:fs";
import path from "node:path";
import Link from "next/link";
import { BookOpen, Download, GitBranch } from "lucide-react";
import { ExampleSlideshow, type ExampleImage } from "@/components/ExampleSlideshow";
import { FeatureGrid } from "@/components/FeatureGrid";
import { SectionHeader } from "@/components/SectionHeader";
import { features, modeCards, siteConfig } from "@/data/site";

const exampleImageExtensions = new Set([".jpg", ".jpeg", ".png", ".webp"]);

function getHeroExamples(): ExampleImage[] {
  const examplesDirectory = path.join(process.cwd(), "public", "examples");

  try {
    return readdirSync(examplesDirectory, { withFileTypes: true })
      .filter((entry) => entry.isFile() && exampleImageExtensions.has(path.extname(entry.name).toLowerCase()))
      .sort((a, b) => a.name.localeCompare(b.name, "en"))
      .map((entry, index) => ({
        src: `/examples/${encodeURIComponent(entry.name)}`,
        alt: `FGO translation screenshot example ${index + 1}`
      }));
  } catch {
    return [];
  }
}

const heroExamples = getHeroExamples();

export default function HomePage() {
  return (
    <>
      <section className="hero">
        <div className="hero-inner">
          <div className="hero-content">
            <p className="eyebrow">Fate/Grand Order JP story translator</p>
            <h1>FgoGotran</h1>
            <p className="hero-lede">
              面向 FGO 日服剧情阅读的 Android 悬浮翻译工具。通过 OCR 读取当前画面，
              使用术语库、缓存和可配置 AI 接口，把角色名、对话和选项渲染回游戏区域。
            </p>
            <div className="hero-actions">
              <Link className="primary-button" href="/download">
                <Download size={19} aria-hidden="true" />
                下载 APK
              </Link>
              <Link className="secondary-button" href="/guide">
                <BookOpen size={19} aria-hidden="true" />
                查看使用指南
              </Link>
              <a className="icon-button" href={siteConfig.githubUrl} target="_blank" rel="noreferrer">
                <GitBranch size={19} aria-hidden="true" />
                GitHub
              </a>
            </div>
            <div className="hero-mini-status">
              <span>Android 11+ · 简体/繁體中文 · Bring Your Own (BYO) API Key</span>
            </div>
          </div>

          <div className="hero-screen" aria-label="FgoGotran screenshot examples">
            <div className="example-showcase">
              <ExampleSlideshow examples={heroExamples} intervalMs={3000} />
            </div>
          </div>
        </div>
      </section>

      <section className="section section-band">
        <SectionHeader
          eyebrow="What it does"
          title="实时剧情翻译"
          body="APK下载、术语库预览和更新日志都能从这里进入。"
        />
        <FeatureGrid items={features} />
      </section>

      <section className="section section-band">
        <SectionHeader
          eyebrow="Modes"
          title="四种翻译模式保持分离"
          body="手动、半自动、全自动和裁剪模式走不同流程"
        />
        <FeatureGrid items={modeCards} />
      </section>
    </>
  );
}
