"use client";

import Image from "next/image";
import { useEffect, useRef, useState } from "react";
import { ChevronDown, Download, Menu, X } from "lucide-react";
import { useTranslations } from "next-intl";
import { Link, usePathname } from "@/i18n/navigation";
import { LocaleSwitcher } from "@/components/LocaleSwitcher";
import { navItems, siteConfig } from "@/data/site";

export function SiteHeader() {
  const t = useTranslations("nav");
  const common = useTranslations("common");
  const pathname = usePathname();
  const [mobileOpen, setMobileOpen] = useState(false);
  const [moreOpen, setMoreOpen] = useState(false);
  const moreRef = useRef<HTMLDivElement>(null);

  const primaryItems = navItems.filter((item) => item.primary);
  const moreItems = navItems.filter((item) => !item.primary);

  useEffect(() => {
    setMobileOpen(false);
    setMoreOpen(false);
  }, [pathname]);

  useEffect(() => {
    if (!moreOpen) return;
    function onPointerDown(event: MouseEvent | TouchEvent) {
      if (moreRef.current && !moreRef.current.contains(event.target as Node)) {
        setMoreOpen(false);
      }
    }
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("touchstart", onPointerDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("touchstart", onPointerDown);
    };
  }, [moreOpen]);

  return (
    <header className="site-header">
      <Link href="/" className="brand-link" aria-label="FgoGotran home">
        <Image
          src="/brand/gotran-icon.png"
          alt=""
          width={38}
          height={38}
          priority
          className="brand-mark"
        />
        <span>{siteConfig.name}</span>
      </Link>

      <nav className="site-nav" aria-label="Main navigation">
        {primaryItems.map((item) => (
          <Link key={item.href} href={item.href}>
            {t(item.key)}
          </Link>
        ))}
        <div className="nav-dropdown" ref={moreRef}>
          <button
            type="button"
            className="nav-dropdown-trigger"
            aria-expanded={moreOpen}
            aria-haspopup="menu"
            onClick={() => setMoreOpen((value) => !value)}
          >
            {common("more")}
            <ChevronDown size={15} aria-hidden="true" />
          </button>
          {moreOpen ? (
            <div className="nav-dropdown-panel" role="menu">
              {moreItems.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  role="menuitem"
                  onClick={() => setMoreOpen(false)}
                >
                  {t(item.key)}
                </Link>
              ))}
            </div>
          ) : null}
        </div>
      </nav>

      <div className="header-actions">
        <a
          className="icon-button header-github"
          href={siteConfig.githubUrl}
          target="_blank"
          rel="noreferrer"
          aria-label={common("github")}
        >
          <Image src="/download-icons/github.svg" alt="" width={18} height={18} aria-hidden="true" />
          <span>{common("github")}</span>
        </a>
        <Link className="primary-button small header-download" href="/download">
          <Download size={17} aria-hidden="true" />
          <span>{common("downloadApk")}</span>
        </Link>
        <LocaleSwitcher />
        <button
          type="button"
          className="menu-toggle"
          aria-label={common("menu")}
          aria-expanded={mobileOpen}
          onClick={() => setMobileOpen((value) => !value)}
        >
          {mobileOpen ? <X size={20} aria-hidden="true" /> : <Menu size={20} aria-hidden="true" />}
        </button>
      </div>

      {mobileOpen ? (
        <div className="mobile-nav-panel">
          <nav className="mobile-nav-links" aria-label="Main navigation">
            {navItems.map((item) => (
              <Link key={item.href} href={item.href}>
                {t(item.key)}
              </Link>
            ))}
          </nav>
          <div className="mobile-nav-actions">
            <Link className="primary-button" href="/download">
              <Download size={17} aria-hidden="true" />
              {common("downloadApk")}
            </Link>
            <a
              className="secondary-button"
              href={siteConfig.githubUrl}
              target="_blank"
              rel="noreferrer"
            >
              <Image src="/download-icons/github.svg" alt="" width={17} height={17} aria-hidden="true" />
              {common("github")}
            </a>
          </div>
        </div>
      ) : null}
    </header>
  );
}
