import Link from "next/link";
import { GitBranch } from "lucide-react";
import { navItems, siteConfig } from "@/data/site";

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div>
        <div className="footer-brand">{siteConfig.name}</div>
        <p>FGO 日服剧情 OCR + AI 中文翻译悬浮工具。</p>
      </div>
      <div className="footer-links">
        {navItems.map((item) => (
          <Link key={item.href} href={item.href}>
            {item.label}
          </Link>
        ))}
        <a href={siteConfig.githubUrl} target="_blank" rel="noreferrer">
          <GitBranch size={15} aria-hidden="true" />
          GitHub
        </a>
      </div>
    </footer>
  );
}
