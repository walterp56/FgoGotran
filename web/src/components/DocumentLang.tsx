"use client";

import { useLocale } from "next-intl";
import { useLayoutEffect } from "react";

export function DocumentLang() {
  const locale = useLocale();

  useLayoutEffect(() => {
    document.documentElement.lang =
      locale === "zh-TW" ? "zh-Hant" : locale === "en" ? "en" : "zh-Hans";
  }, [locale]);

  return null;
}