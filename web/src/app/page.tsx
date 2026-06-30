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
              面向 FGO 日服剧情的 Android 悬浮翻译工具。通过 OCR 读取画面，结合在线术语库和 AI
              翻译，让角色名、专有名词和剧情语气更稳定。
            </p>
            <div className="hero-actions">
              <Link className="primary-button" href="/download">
                <Download size={19} aria-hidden="true" />
                下载 APK
              </Link>
              <Link className="secondary-button" href="/guide">
                <BookOpen size={19} aria-hidden="true" />
                使用指南
              </Link>
              <a className="icon-button" href={siteConfig.githubUrl} target="_blank" rel="noreferrer">
                <GitBranch size={19} aria-hidden="true" />
                GitHub
              </a>
            </div>
            <div className="hero-mini-status">
              <span>Android 11+ · 简体/繁體中文 · Bring Your Own API Key</span>
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
          title="为 FGO 剧情阅读而做"
          body="覆盖剧情对话、选项、角色名和常见专有名词，让日服剧情阅读更顺手。"
        />
        <FeatureGrid items={features} />
      </section>

      <section className="section section-band">
        <SectionHeader
          eyebrow="Modes"
          title="选择适合你的翻译节奏"
          body="从完全手动到全自动，也可以在识别不准时用裁剪模式处理特殊画面。"
        />
        <FeatureGrid items={modeCards} />
      </section>
    </>
  );
}
