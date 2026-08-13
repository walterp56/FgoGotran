import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "媒体",
  description: "FgoGotran 的演示视频、使用效果与更新动态。"
};

const mediaItems = [
  {
    platform: "Bilibili",
    title: "B站演示视频2",
    description: "",
    href: "https://www.bilibili.com/video/BV19GgW6UEGa/?share_source=copy_web&vd_source=1c2307ecc4e2bc7ffe79b038dcd58242",
    embedSrc: "https://player.bilibili.com/player.html?bvid=BV19GgW6UEGa&page=1&high_quality=1&autoplay=0"
  },
  {
    platform: "YouTube",
    title: "YouTube 演示视频2",
    description: "",
    href: "https://www.youtube.com/watch?v=K5g5sAOOT8s",
    embedSrc: "https://www.youtube.com/embed/K5g5sAOOT8s"
  },
  {
    platform: "Bilibili",
    title: "B站演示视频1",
    description: "",
    href: "https://www.bilibili.com/video/BV1EETw6WEVb/?spm_id_from=333.1391.0.0&vd_source=2387a9304704f7e1f00eebfd71517385",
    embedSrc: "https://player.bilibili.com/player.html?bvid=BV1EETw6WEVb&page=1&high_quality=1&autoplay=0"
  },
  {
    platform: "YouTube",
    title: "YouTube 演示视频1",
    description: "",
    href: "https://www.youtube.com/watch?v=ZhLBwpblhNE&t=5s",
    embedSrc: "https://www.youtube.com/embed/ZhLBwpblhNE?start=5"
  }
];

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
        <div className="media-grid">
          {mediaItems.map((item) => (
            <article className="media-card" key={item.href}>
              <div className="media-frame">
                <iframe
                  src={item.embedSrc}
                  title={item.title}
                  loading="lazy"
                  allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
                  allowFullScreen
                />
              </div>
              <div className="media-card-body">
                <p className="guide-label">{item.platform}</p>
                <h2>{item.title}</h2>
                <p>{item.description}</p>
                <a className="media-card-link" href={item.href} target="_blank" rel="noreferrer">
                  打开原视频
                </a>
              </div>
            </article>
          ))}
        </div>
      </section>
    </>
  );
}
