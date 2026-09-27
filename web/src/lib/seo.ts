import type { Metadata } from "next";

export const SITE_URL = "https://fgogotran.com";

export function pageMetadata(
  locale: string,
  path: string,
  opts: { title?: string; description?: string }
): Metadata {
  const slug = path ? `${path}/` : "";
  return {
    ...(opts.title ? { title: opts.title } : {}),
    ...(opts.description ? { description: opts.description } : {}),
    alternates: {
      canonical: `${SITE_URL}/${locale}/${slug}`,
      languages: {
        "zh-CN": `${SITE_URL}/zh-CN/${slug}`,
        "zh-TW": `${SITE_URL}/zh-TW/${slug}`,
        en: `${SITE_URL}/en/${slug}`,
        "x-default": `${SITE_URL}/zh-CN/${slug}`
      }
    }
  };
}