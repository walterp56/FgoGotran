import { readFileSync } from "node:fs";

const messagesDir = new URL("../messages/", import.meta.url);
const reference = "zh-CN.json";
const targets = ["zh-TW.json", "en.json"];

const TAG = /<(\/?[^<>]*)>/g;
const VALID_TAG_NAME = /^\/?[a-zA-Z][a-zA-Z0-9-]*$/;

/**
 * Flattens a catalog into a list of shapes so that arrays are compared by
 * length as well as by key:
 *   nav.guide                          -> string value
 *   guide.setupSteps.x.images [array:2] -> array length
 *   guide.setupSteps.x.images[1]       -> array item
 */
function shapes(value, prefix = "") {
  if (Array.isArray(value)) {
    return [
      prefix + " [array:" + value.length + "]",
      ...value.flatMap((item, index) => shapes(item, prefix + "[" + index + "]"))
    ];
  }
  if (value !== null && typeof value === "object") {
    return Object.entries(value).flatMap(([key, child]) =>
      shapes(child, prefix ? prefix + "." + key : key)
    );
  }
  return [prefix];
}

/**
 * next-intl parses <tag> markup in every message, even for plain t() calls, and
 * throws INVALID_TAG at render time for anything that is not a well-formed tag
 * (for example "http://<PC LAN IP>:18080" — the space makes it invalid).
 * Catch that at build time instead of in the browser.
 */
function lintTags(value, path, problems) {
  if (typeof value === "string") {
    const tags = [...value.matchAll(TAG)].map((match) => match[1]);
    if (tags.length === 0) return;

    for (const inner of tags) {
      if (!VALID_TAG_NAME.test(inner)) {
        problems.push(path + ": <" + inner + "> is not a valid rich-text tag");
      }
    }

    const opened = tags.filter((tag) => !tag.startsWith("/")).sort().join(",");
    const closed = tags
      .filter((tag) => tag.startsWith("/"))
      .map((tag) => tag.slice(1))
      .sort()
      .join(",");
    if (opened !== closed) {
      problems.push(path + ": unbalanced rich-text tags (" + tags.join(" ") + ")");
    }
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) => lintTags(item, path + "[" + index + "]", problems));
    return;
  }
  if (value !== null && typeof value === "object") {
    for (const [key, child] of Object.entries(value)) {
      lintTags(child, path ? path + "." + key : key, problems);
    }
  }
}

const base = JSON.parse(readFileSync(new URL(reference, messagesDir), "utf8"));
const baseShapes = shapes(base);
let failed = false;

const referenceProblems = [];
lintTags(base, "", referenceProblems);
if (referenceProblems.length) {
  failed = true;
  console.error("\u2717 " + reference + ": " + referenceProblems.length + " message problem(s)");
  referenceProblems.slice(0, 25).forEach((problem) => console.error("   " + problem));
}

for (const file of targets) {
  let locale;
  try {
    locale = JSON.parse(readFileSync(new URL(file, messagesDir), "utf8"));
  } catch (error) {
    console.error("\u2717 " + file + ": cannot be read (" + error.message + ")");
    failed = true;
    continue;
  }

  const known = new Set(shapes(locale));
  const missing = baseShapes.filter((shape) => !known.has(shape));
  const extra = [...known].filter((shape) => !baseShapes.includes(shape));

  const problems = [];
  lintTags(locale, "", problems);

  if (missing.length || extra.length || problems.length) {
    failed = true;
    console.error(
      "\u2717 " + file + ": " + missing.length + " missing, " + extra.length + " extra, " +
        problems.length + " message problem(s)"
    );
    missing.slice(0, 25).forEach((shape) => console.error("   missing: " + shape));
    extra.slice(0, 25).forEach((shape) => console.error("   extra:   " + shape));
    problems.slice(0, 25).forEach((problem) => console.error("   " + problem));
  } else {
    console.log("\u2713 " + file + ": " + baseShapes.length + " shapes match " + reference);
  }
}

if (failed) {
  console.error("\nLocale catalogs are out of sync with " + reference + ".");
  process.exit(1);
}
console.log("\nAll locale catalogs are in sync with " + reference + ".");