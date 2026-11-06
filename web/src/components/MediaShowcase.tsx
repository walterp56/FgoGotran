"use client";

import { ArrowUpRight, Check, Film, Play } from "lucide-react";
import { useTranslations } from "next-intl";
import { useRef, useState } from "react";
import type { MediaDefinition, MediaPlatform } from "@/data/media";

type PlatformFilter = "all" | MediaPlatform;

const platforms: PlatformFilter[] = ["all", "Bilibili", "YouTube"];

export function MediaShowcase({ videos }: { videos: MediaDefinition[] }) {
  const t = useTranslations("media");
  const [selection, setSelection] = useState({
    platform: "all" as PlatformFilter,
    videoKey: videos[0]?.key ?? ""
  });
  const player = useRef<HTMLElement>(null);
  const visible = videos.filter((video) => selection.platform === "all" || video.platform === selection.platform);
  const active = videos.find((video) => video.key === selection.videoKey) ?? videos[0];

  if (!active) return null;

  function filterBy(platform: PlatformFilter) {
    const matches = videos.filter((video) => platform === "all" || video.platform === platform);
    setSelection((current) => ({
      platform,
      videoKey: matches.some((video) => video.key === current.videoKey)
        ? current.videoKey
        : matches[0]?.key ?? ""
    }));
  }

  function choose(videoKey: string) {
    if (videoKey === selection.videoKey) return;
    setSelection((current) => ({ ...current, videoKey }));
    if (window.matchMedia("(max-width: 900px)").matches) {
      player.current?.scrollIntoView({
        block: "start",
        behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth"
      });
    }
  }

  return (
    <div className="media-showcase">
      <div className="media-toolbar">
        <div className="media-filters" role="group" aria-label={t("filterLabel")}>
          {platforms.map((platform) => (
            <button
              type="button"
              key={platform}
              aria-pressed={selection.platform === platform}
              onClick={() => filterBy(platform)}
            >
              {platform === "all" ? t("allPlatforms") : platform}
            </button>
          ))}
        </div>
        <p className="media-count" role="status">{t("videoCount", { count: visible.length })}</p>
      </div>

      <div className="media-layout">
        <article className="media-player-card" id="media-player" ref={player} aria-labelledby="media-video-title">
          <div className="media-frame">
            <iframe
              key={active.key}
              src={active.embedSrc}
              title={active.title}
              loading="lazy"
              allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
              referrerPolicy="strict-origin-when-cross-origin"
              allowFullScreen
            />
          </div>
          <div className="media-player-details">
            <div className="media-player-caption">
              <div aria-live="polite" aria-atomic="true">
                <p className="media-selected-label">{t("selectedVideo")}<span aria-hidden="true"> / </span>{active.platform}</p>
                <h2 id="media-video-title">{active.title}</h2>
              </div>
              <span className="media-position">{t("position", { current: visible.findIndex((video) => video.key === active.key) + 1, total: visible.length })}</span>
            </div>
            <div className="media-player-footer">
              <p>{t("playerHelp")}</p>
              <a className="media-original-link" href={active.href} target="_blank" rel="noreferrer">
                {t("openVideo")}<ArrowUpRight size={16} aria-hidden="true" />
              </a>
            </div>
          </div>
        </article>

        <aside className="media-library" aria-labelledby="media-library-title">
          <div className="media-library-heading">
            <Film size={18} aria-hidden="true" />
            <h2 id="media-library-title">{t("playlist")}</h2>
          </div>
          <ul className="media-playlist">
            {visible.map((video) => {
              const selected = video.key === active.key;
              return (
                <li key={video.key}>
                  <button
                    type="button"
                    className="media-playlist-item"
                    aria-current={selected ? "true" : undefined}
                    aria-controls="media-player"
                    onClick={() => choose(video.key)}
                  >
                    <span className="media-video-icon" aria-hidden="true"><Play size={18} /></span>
                    <span className="media-video-copy">
                      <span className="media-platform" data-platform={video.platform}>{video.platform}</span>
                      <span className="media-video-title">{video.title}</span>
                    </span>
                    {selected ? <Check className="media-selected-check" size={17} aria-hidden="true" /> : null}
                  </button>
                </li>
              );
            })}
          </ul>
        </aside>
      </div>
    </div>
  );
}
