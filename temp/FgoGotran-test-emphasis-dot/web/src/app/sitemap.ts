import type { MetadataRoute } from "next";

const routes = ["", "/download", "/guide", "/api-guide", "/terms", "/changelog"];

export default function sitemap(): MetadataRoute.Sitemap {
  return routes.map((route) => ({
    url: `https://fgogotran.com${route}`,
    lastModified: new Date()
  }));
}
