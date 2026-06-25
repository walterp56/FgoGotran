import Image from "next/image";
import Link from "next/link";
import { Download, GitBranch } from "lucide-react";
import { navItems, siteConfig } from "@/data/site";

export function SiteHeader() {
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
        {navItems.map((item) => (
          <Link key={item.href} href={item.href}>
            {item.label}
          </Link>
        ))}
      </nav>
      <div className="header-actions">
        <a className="icon-button" href={siteConfig.githubUrl} target="_blank" rel="noreferrer">
          <GitBranch size={18} aria-hidden="true" />
          <span>GitHub</span>
        </a>
        <Link className="primary-button small" href="/download">
          <Download size={17} aria-hidden="true" />
          <span>下载 APK</span>
        </Link>
      </div>
    </header>
  );
}
