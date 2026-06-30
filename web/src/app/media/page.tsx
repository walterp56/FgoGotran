import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "媒体",
  description: "FgoGotran 的演示视频、使用效果与更新动态。"
};

const plannedMedia = ["Bilibili", "YouTube", "其他媒体"];

export default function MediaPage() {
  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">Media</p>
          <h1>媒体</h1>
          <p>FgoGotran 的演示视频、使用效果与更新动态。</p>
        </div>
      </section>

      <section className="section compact">
        <article className="media-empty-panel">
          <p className="guide-label">Coming Soon</p>
          <h2>展示内容准备中</h2>
          <p>这里之后会放 Bilibili、YouTube 和其他展示内容。</p>
          <div className="media-platform-list">
            {plannedMedia.map((name) => (
              <span key={name}>{name}</span>
            ))}
          </div>
        </article>
      </section>
    </>
  );
}
