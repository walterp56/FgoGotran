import type { MetadataRoute } from "next";
import { apiProviderGuides } from "@/data/apiProviderGuides";
import { routing } from "@/i18n/routing";

export const dynamic = "force-static";

const routes = [
  "",
  "/download",
  "/guide",
  "/troubleshooting",
  "/api-guide",
  "/local-guide",
  "/speech-guide",
  ...apiProviderGuides.map((guide) => `/api-guide/${guide.slug}`),
  "/terms",
  "/media",
  "/changelog"
];

export default function sitemap(): MetadataRoute.Sitemap {
  return routing.locales.flatMap((locale) =>
    routes.map((route) => ({
      url: `https://fgogotran.com/${locale}${route}/`,
      lastModified: new Date()
    }))
  );
}