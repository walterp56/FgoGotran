import type { MetadataRoute } from "next";
import { apiProviderGuides } from "@/data/apiProviderGuides";

export const dynamic = "force-static";

const routes = [
  "",
  "/download",
  "/guide",
  "/api-guide",
  ...apiProviderGuides.map((guide) => `/api-guide/${guide.slug}`),
  "/terms",
  "/media",
  "/changelog"
];

export default function sitemap(): MetadataRoute.Sitemap {
  return routes.map((route) => ({
    url: `https://fgogotran.com${route}`,
    lastModified: new Date()
  }));
}
