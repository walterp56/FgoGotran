import { readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Reports which localized images exist and which pages still fall back to the
 * default zh-CN file. Informational only: a half-finished localized set is a
 * valid state, so this never fails the build.
 */
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const publicDirectory = path.join(scriptDirectory, "..", "public");
const IMAGE_EXTENSIONS = new Set([".jpg", ".jpeg", ".png", ".webp", ".avif"]);
const LOCALIZED_LOCALES = ["en"];

// Guide images that intentionally stay in the default language: FGO JP game UI
// (Japanese is expected there) and language-neutral button glyphs.
const DEFAULT_ONLY_GUIDE_IMAGES = new Set([
  "guide-04.jpeg",
  "guide-05.jpeg",
  "guide-12.jpeg",
  "guide-15.jpeg",
  "guide-16.jpeg",
  "guide-17.jpeg"
]);

function imageFiles(directory) {
  try {
    return readdirSync(directory, { withFileTypes: true })
      .filter((entry) => entry.isFile() && IMAGE_EXTENSIONS.has(path.extname(entry.name).toLowerCase()))
      .map((entry) => entry.name)
      .sort((a, b) => a.localeCompare(b, "en", { numeric: true }));
  } catch {
    return [];
  }
}

function reportGuide() {
  const baseFiles = imageFiles(path.join(publicDirectory, "guide"));
  console.log("Guide images (public/guide): " + baseFiles.length + " default image(s)");
  for (const locale of LOCALIZED_LOCALES) {
    const localized = new Set(imageFiles(path.join(publicDirectory, "guide", locale)));
    const expected = baseFiles.filter((file) => !DEFAULT_ONLY_GUIDE_IMAGES.has(file));
    const present = expected.filter((file) => localized.has(file));
    const missing = expected.filter((file) => !localized.has(file));
    console.log("  " + locale + ": " + present.length + "/" + expected.length + " localized, " + missing.length + " falling back to the default");
    for (const file of missing) {
      console.log("    - " + file);
    }
  }
}

function reportSlideshow() {
  const baseFiles = imageFiles(path.join(publicDirectory, "examples"));
  console.log("Home slideshow (public/examples): " + baseFiles.length + " default image(s)");
  for (const locale of LOCALIZED_LOCALES) {
    const localized = imageFiles(path.join(publicDirectory, "examples", locale));
    if (localized.length > 0) {
      console.log("  " + locale + ": " + localized.length + " localized image(s)");
    } else {
      console.log("  " + locale + ": none yet, the default set (" + baseFiles.length + " images) is used");
    }
  }
}

console.log("Localized image coverage:");
reportGuide();
reportSlideshow();
