import type { MetadataRoute } from "next";

export const dynamic = "force-static";

const routes = ["", "/download", "/guide", "/api-guide", "/terms", "/changelog"];

export default function sitemap(): MetadataRoute.Sitemap {
  return routes.map((route) => ({
    url: `https://fgogotran.com${route}`,
    lastModified: new Date()
  }));
}
