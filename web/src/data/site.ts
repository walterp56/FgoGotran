import {
  BrainCircuit,
  Captions,
  DatabaseZap,
  History,
  MousePointerClick,
  PanelBottom,
  RefreshCw,
  ScanText,
  ServerCog,
  SlidersHorizontal,
  SquareDashedMousePointer,
  Workflow
} from "lucide-react";
import type { LucideIcon } from "lucide-react";

export const siteConfig = {
  name: "FgoGotran",
  githubUrl: "https://github.com/walterp56/FgoGotran",
  githubReleasesUrl: "https://github.com/walterp56/FgoGotran/releases",
  cdnBaseUrl: process.env.NEXT_PUBLIC_CDN_BASE_URL ?? "https://cdn.fgogotran.com",
  apkManifestPath: "/app/android/latest/manifest.json",
  dbManifestPath: "/db/zh-Hans/latest/manifest.json",
  termsPreviewPath: "/term-preview/zh-Hans/latest/terms.preview.json",
  characterPreviewPath: "/term-preview/zh-Hans/latest/character_names.preview.json"
};

export type NavItem = {
  href: string;
  key: string;
  primary?: boolean;
};

export const navItems: NavItem[] = [
  { href: "/guide", key: "guide", primary: true },
  { href: "/troubleshooting", key: "troubleshooting", primary: true },
  { href: "/api-guide", key: "apiGuide", primary: true },
  { href: "/local-guide", key: "localGuide", primary: true },
  { href: "/speech-guide", key: "speechGuide" },
  { href: "/terms", key: "terms" },
  { href: "/media", key: "media" },
  { href: "/changelog", key: "changelog" }
];

export type Feature = {
  id: string;
  title: string;
  body: string;
  icon: LucideIcon;
};

export type FeatureDef = {
  id: string;
  icon: LucideIcon;
};

export const featureDefs: FeatureDef[] = [
  { id: "story", icon: BrainCircuit },
  { id: "overlay", icon: PanelBottom },
  { id: "auto-detect", icon: ScanText },
  { id: "modes", icon: SlidersHorizontal },
  { id: "terms", icon: DatabaseZap },
  { id: "api", icon: ServerCog }
];

export const modeCardDefs: FeatureDef[] = [
  { id: "manual", icon: MousePointerClick },
  { id: "semi-auto", icon: Workflow },
  { id: "auto", icon: RefreshCw },
  { id: "crop", icon: SquareDashedMousePointer },
  { id: "translation-log", icon: History },
  { id: "battle-subtitles", icon: Captions }
];