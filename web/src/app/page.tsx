import Image from "next/image";
import Link from "next/link";
import { BookOpen, Download, GitBranch } from "lucide-react";
import { FeatureGrid } from "@/components/FeatureGrid";
import { PublishedBadge, ReleaseStatus } from "@/components/ReleaseStatus";
import { SectionHeader } from "@/components/SectionHeader";
import { deployNotes, features, modeCards, siteConfig } from "@/data/site";

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
              使用术语库、缓存和可配置 AI 接口，把姓名、对话和选项渲染回游戏区域。
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
              <PublishedBadge />
              <span>Android 11+ · 简体中文 · BYO API Key</span>
            </div>
          </div>

          <div className="hero-screen" aria-label="FgoGotran overlay preview">
            <div className="game-window">
              <div className="game-stage">
                <div className="name-label">玛修·基列莱特</div>
                <div className="game-dialog">
                  <div>先辈，检测到新的剧情文本。</div>
                  <div className="translated-line">FgoGotran 会把译文覆盖在原来的对话位置。</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="section section-band">
        <SectionHeader
          eyebrow="What it does"
          title="为实时剧情阅读做的翻译流程"
          body="网站、CDN 和 Android App 使用同一套发布数据：APK、术语库 manifest、TSV 预览和更新日志都能从这里进入。"
        />
        <FeatureGrid items={features} />
      </section>

      <section className="section">
        <SectionHeader
          eyebrow="Status"
          title="当前发布状态"
          body="这些卡片会从 cdn.fgogotran.com 读取 manifest。DB manifest 已经有结构，APK manifest 可以在后续发布时补上。"
        />
        <ReleaseStatus />
      </section>

      <section className="section section-band">
        <SectionHeader
          eyebrow="Modes"
          title="三种翻译模式保持分离"
          body="手动、自动和裁剪区域走不同流程，避免后续优化时互相污染行为。"
        />
        <FeatureGrid items={modeCards} />
      </section>

      <section className="section">
        <SectionHeader
          eyebrow="Hosting shape"
          title="现在静态，未来可接后端"
          body="第一版网站直接读取 CDN JSON。以后要做反馈、术语提交或后台管理时，再接 api.fgogotran.com。"
        />
        <FeatureGrid items={deployNotes} />
        <Image
          src="/brand/gotran-icon.png"
          alt="FgoGotran icon"
          width={96}
          height={96}
          className="brand-mark"
        />
      </section>
    </>
  );
}
