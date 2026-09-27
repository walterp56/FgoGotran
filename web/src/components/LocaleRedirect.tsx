"use client";

import { useEffect } from "react";

function detectLocale(): string {
  try {
    const languages = navigator.languages?.length
      ? navigator.languages
      : [navigator.language || ""];

    for (const raw of languages) {
      const lang = (raw || "").toLowerCase();
      if (lang.startsWith("zh")) {
        if (
          lang.startsWith("zh-tw") ||
          lang.startsWith("zh-hant") ||
          lang.startsWith("zh-hk") ||
          lang.startsWith("zh-mo")
        ) {
          return "zh-TW";
        }
        return "zh-CN";
      }
    }
    return "en";
  } catch {
    return "zh-CN";
  }
}

export function LocaleRedirect({ path = "" }: { path?: string }) {
  const suffix = path ? `${path}/` : "";

  useEffect(() => {
    window.location.replace(`/${detectLocale()}/${suffix}`);
  }, [suffix]);

  return (
    <main style={{ padding: 24, fontFamily: "system-ui, sans-serif" }}>
      <p>FgoGotran</p>
      <p>
        <a href={`/zh-CN/${suffix}`}>简体中文</a> ·{" "}
        <a href={`/zh-TW/${suffix}`}>繁體中文</a> ·{" "}
        <a href={`/en/${suffix}`}>English</a>
      </p>
    </main>
  );
}