import { existsSync, readdirSync } from "node:fs";
import path from "node:path";

/**
 * Multi-language images live in a locale folder next to the default files:
 *
 *   public/guide/guide-09.jpeg        default (zh-CN) and fallback for everyone
 *   public/guide/en/guide-09.jpeg     English
 *   public/guide/zh-TW/guide-09.jpeg  Traditional Chinese (when added)
 *
 * A localized file that does not exist yet falls back to the default image, so
 * translations can land one image at a time without breaking a page.
 */
const PUBLIC_DIRECTORY = path.join(process.cwd(), "public");
const DEFAULT_LOCALE = "zh-CN";
const IMAGE_EXTENSIONS = new Set([".jpg", ".jpeg", ".png", ".webp", ".avif"]);

function imageFiles(directory: string): string[] {
  try {
    return readdirSync(directory, { withFileTypes: true })
      .filter((entry) => entry.isFile() && IMAGE_EXTENSIONS.has(path.extname(entry.name).toLowerCase()))
      .map((entry) => entry.name)
      .sort((a, b) => a.localeCompare(b, "en", { numeric: true }));
  } catch {
    return [];
  }
}

/** Resolve a public image path for one locale, falling back to the default file. */
export function localizedImage(locale: string, src: string): string {
  if (!src.startsWith("/") || locale === DEFAULT_LOCALE) {
    return src;
  }

  const pathname = src.split("?")[0].split("#")[0];
  const segments = pathname.split("/").filter(Boolean);
  const file = segments.pop();
  if (!file) {
    return src;
  }

  const directory = segments.join("/");
  const localizedUrl = "/" + directory + "/" + locale + "/" + file;
  const localizedFile = path.join(PUBLIC_DIRECTORY, directory, locale, file);

  return existsSync(localizedFile) ? localizedUrl : src;
}

/** List the images of a public folder for one locale, falling back to the default set. */
export function listLocalizedImages(locale: string, directory: string): string[] {
  const localized =
    locale === DEFAULT_LOCALE ? [] : imageFiles(path.join(PUBLIC_DIRECTORY, directory, locale));
  const files = localized.length > 0 ? localized : imageFiles(path.join(PUBLIC_DIRECTORY, directory));
  const prefix = localized.length > 0 ? "/" + directory + "/" + locale : "/" + directory;

  return files.map((file) => prefix + "/" + encodeURIComponent(file));
}
