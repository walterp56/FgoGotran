import { getTranslations, setRequestLocale } from "next-intl/server";
import { pageMetadata } from "@/lib/seo";

const mediaItems = [
  {
    key: "bili3",
    platform: "Bilibili",
    href: "https://www.bilibili.com/video/BV1MKeE6DEgx/",
    embedSrc: "https://player.bilibili.com/player.html?bvid=BV1MKeE6DEgx&page=1&high_quality=1&autoplay=0"
  },
  {
    key: "yt3",
    platform: "YouTube",
    href: "https://www.youtube.com/watch?v=prS8kIj-zYY",
    embedSrc: "https://www.youtube.com/embed/prS8kIj-zYY"
  },
  {
    key: "bili2",
    platform: "Bilibili",
    href: "https://www.bilibili.com/video/BV19GgW6UEGa/?share_source=copy_web&vd_source=1c2307ecc4e2bc7ffe79b038dcd58242",
    embedSrc: "https://player.bilibili.com/player.html?bvid=BV19GgW6UEGa&page=1&high_quality=1&autoplay=0"
  },
  {
    key: "yt2",
    platform: "YouTube",
    href: "https://www.youtube.com/watch?v=K5g5sAOOT8s",
    embedSrc: "https://www.youtube.com/embed/K5g5sAOOT8s"
  },
  {
    key: "bili1",
    platform: "Bilibili",
    href: "https://www.bilibili.com/video/BV1EETw6WEVb/?spm_id_from=333.1391.0.0&vd_source=2387a9304704f7e1f00eebfd71517385",
    embedSrc: "https://player.bilibili.com/player.html?bvid=BV1EETw6WEVb&page=1&high_quality=1&autoplay=0"
  },
  {
    key: "yt1",
    platform: "YouTube",
    href: "https://www.youtube.com/watch?v=ZhLBwpblhNE&t=5s",
    embedSrc: "https://www.youtube.com/embed/ZhLBwpblhNE?start=5"
  }
];

type MediaPageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: MediaPageProps) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "media" });
  return pageMetadata(locale, "media", { title: t("title"), description: t("description") });
}

export default async function MediaPage({ params }: MediaPageProps) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("media");

  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">{t("eyebrow")}</p>
          <h1>{t("h1")}</h1>
          <p>{t("body")}</p>
        </div>
      </section>

      <section className="section compact">
        <div className="media-grid">
          {mediaItems.map((item) => (
            <article className="media-card" key={item.href}>
              <div className="media-frame">
                <iframe
                  src={item.embedSrc}
                  title={t(`items.${item.key}`)}
                  loading="lazy"
                  allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
                  allowFullScreen
                />
              </div>
              <div className="media-card-body">
                <p className="guide-label">{item.platform}</p>
                <h2>{t(`items.${item.key}`)}</h2>
                <p></p>
                <a className="media-card-link" href={item.href} target="_blank" rel="noreferrer">
                  {t("openVideo")}
                </a>
              </div>
            </article>
          ))}
        </div>
      </section>
    </>
  );
}