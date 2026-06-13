import { siteConfig } from "@/data/site";

export type DbManifest = {
  manifestVersion: number;
  contentVersion: string;
  schemaVersion: number;
  locale: string;
  generatedAt: string;
  minimumAppVersion: string;
  releaseNotes: string;
  dbUrl: string;
  dbSha256: string;
  dbSize: number;
  characterNameCount: number;
  termCount: number;
  totalCount: number;
};

export type AppManifest = {
  manifestVersion: number;
  versionName: string;
  versionCode: number;
  releaseDate: string;
  minimumAndroid: string;
  apkUrl: string;
  apkSha256: string;
  apkSize: number;
  changelog: string[];
};

export function cdnUrl(path: string) {
  return `${siteConfig.cdnBaseUrl.replace(/\/$/, "")}${path}`;
}

export function formatBytes(bytes?: number) {
  if (!bytes || bytes <= 0) return "待发布";
  const units = ["B", "KB", "MB", "GB"];
  let value = bytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value.toFixed(value >= 10 || unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
}

export function formatDateTime(value?: string) {
  if (!value) return "待发布";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  }).format(date);
}
