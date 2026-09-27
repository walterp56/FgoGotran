/**
 * Regenerates messages/zh-TW.json from messages/zh-CN.json.
 *
 * Simplified -> Traditional with Taiwan phrasing (OpenCC cn -> twp), followed by
 * the site's Taiwan term list, because OpenCC does not apply every term we want
 * (for example 記錄 -> 紀錄 for logs).
 *
 * Usage: npm run gen:zh-tw
 * Always review the diff before committing: a few strings are hand-tuned and
 * are pinned through `overrides` below.
 */
import { readFileSync, writeFileSync } from "node:fs";
import * as OpenCC from "opencc-js";

const messagesDir = new URL("../messages/", import.meta.url);
const source = JSON.parse(readFileSync(new URL("zh-CN.json", messagesDir), "utf8"));

const convert = OpenCC.Converter({ from: "cn", to: "twp" });

const replacements = [
  ["許可權", "權限"],
  ["區域網", "區域網路"],
  ["引數", "參數"],
  ["視訊記憶體", "顯示記憶體"],
  ["簡體中文指南", "繁體中文指南"],
  ["更新記錄", "更新紀錄"],
  ["看最上面的記錄", "看最上面的紀錄"]
];

const overrides = {
  "terms.dbNote": "此預覽來自應用程式目前使用的日文 → 中文與英文術語庫。"
};

function toTaiwan(value) {
  if (typeof value === "string") {
    let out = convert(value);
    for (const [from, to] of replacements) {
      out = out.split(from).join(to);
    }
    return out;
  }
  if (Array.isArray(value)) {
    return value.map(toTaiwan);
  }
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, toTaiwan(child)]));
  }
  return value;
}

const target = toTaiwan(source);

for (const [path, value] of Object.entries(overrides)) {
  const parts = path.split(".");
  let cursor = target;
  for (const part of parts.slice(0, -1)) {
    cursor = cursor[part];
  }
  cursor[parts[parts.length - 1]] = value;
}

writeFileSync(
  new URL("zh-TW.json", messagesDir),
  JSON.stringify(target, null, 2) + "\n",
  "utf8"
);
console.log("Regenerated messages/zh-TW.json from messages/zh-CN.json.");