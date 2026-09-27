"use client";

import { Check, ChevronDown, Globe } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useParams } from "next/navigation";
import { useEffect, useRef, useState, useTransition } from "react";
import { usePathname, useRouter } from "@/i18n/navigation";
import { routing } from "@/i18n/routing";

const localeLabels: Record<string, string> = {
  "zh-CN": "简体中文",
  "zh-TW": "繁體中文",
  en: "English"
};

const localeShortLabels: Record<string, string> = {
  "zh-CN": "简体",
  "zh-TW": "繁體",
  en: "EN"
};

export function LocaleSwitcher() {
  const locale = useLocale();
  const common = useTranslations("common");
  const router = useRouter();
  const pathname = usePathname();
  const params = useParams();
  const [open, setOpen] = useState(false);
  const [isPending, startTransition] = useTransition();
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setOpen(false);
  }, [pathname, locale]);

  useEffect(() => {
    if (!open) return;
    function onPointerDown(event: MouseEvent | TouchEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("touchstart", onPointerDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("touchstart", onPointerDown);
    };
  }, [open]);

  function selectLocale(nextLocale: string) {
    setOpen(false);
    if (nextLocale === locale) return;
    startTransition(() => {
      router.replace(
        // @ts-expect-error -- `pathname` and `params` always match the current
        // route, so we can skip runtime checks here.
        { pathname, params },
        { locale: nextLocale }
      );
    });
  }

  return (
    <div className="nav-dropdown locale-switcher" ref={containerRef}>
      <button
        type="button"
        className="nav-dropdown-trigger locale-trigger"
        aria-label={common("language")}
        aria-expanded={open}
        aria-haspopup="menu"
        disabled={isPending}
        onClick={() => setOpen((value) => !value)}
      >
        <Globe size={16} aria-hidden="true" />
        <span>{localeShortLabels[locale] ?? locale}</span>
        <ChevronDown size={14} aria-hidden="true" />
      </button>
      {open ? (
        <div className="nav-dropdown-panel locale-panel" role="menu">
          {routing.locales.map((item) => (
            <button
              key={item}
              type="button"
              role="menuitemradio"
              aria-checked={item === locale}
              className={item === locale ? "is-active" : undefined}
              onClick={() => selectLocale(item)}
            >
              <span>{localeLabels[item] ?? item}</span>
              {item === locale ? <Check size={15} aria-hidden="true" /> : null}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}