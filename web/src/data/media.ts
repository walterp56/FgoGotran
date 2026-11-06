export type MediaPlatform = "Bilibili" | "YouTube";

export type MediaDefinition = {
  key: string;
  title: string;
  platform: MediaPlatform;
  href: string;
  embedSrc: string;
};

export const mediaItems: MediaDefinition[] = [
  {
    key: "bili3",
    title: "【FgoGotran】FGO 日服即时翻译再升级！FgoGotran 本地 AI、语音翻译与战斗字幕演示",
    platform: "Bilibili",
    href: "https://www.bilibili.com/video/BV1MKeE6DEgx/",
    embedSrc: "https://player.bilibili.com/player.html?bvid=BV1MKeE6DEgx&page=1&high_quality=1&autoplay=0"
  },
  {
    key: "yt3",
    title: "【FgoGotran】FGO 日服即時翻譯再升級！FgoGotran 本地 AI、語音翻譯與戰鬥字幕演示",
    platform: "YouTube",
    href: "https://www.youtube.com/watch?v=prS8kIj-zYY",
    embedSrc: "https://www.youtube.com/embed/prS8kIj-zYY"
  },
  {
    key: "bili2",
    title: "【FgoGotran】FGO剧情配音朗读功能! 新功能介绍",
    platform: "Bilibili",
    href: "https://www.bilibili.com/video/BV19GgW6UEGa/?share_source=copy_web&vd_source=1c2307ecc4e2bc7ffe79b038dcd58242",
    embedSrc: "https://player.bilibili.com/player.html?bvid=BV19GgW6UEGa&page=1&high_quality=1&autoplay=0"
  },
  {
    key: "yt2",
    title: "【FgoGotran】FGO劇情配音朗讀功能! 新功能介紹",
    platform: "YouTube",
    href: "https://www.youtube.com/watch?v=K5g5sAOOT8s",
    embedSrc: "https://www.youtube.com/embed/K5g5sAOOT8s"
  },
  {
    key: "bili1",
    title: "【FgoGotran】FGO新章剧情翻译! 专为FGO日服而设的翻译器!",
    platform: "Bilibili",
    href: "https://www.bilibili.com/video/BV1EETw6WEVb/?spm_id_from=333.1391.0.0&vd_source=2387a9304704f7e1f00eebfd71517385",
    embedSrc: "https://player.bilibili.com/player.html?bvid=BV1EETw6WEVb&page=1&high_quality=1&autoplay=0"
  },
  {
    key: "yt1",
    title: "【FgoGotran】FGO新章劇情翻譯! 專為FGO日服而設的翻譯器!",
    platform: "YouTube",
    href: "https://www.youtube.com/watch?v=ZhLBwpblhNE&t=5s",
    embedSrc: "https://www.youtube.com/embed/ZhLBwpblhNE?start=5"
  }
];
