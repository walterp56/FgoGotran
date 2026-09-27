import type { Metadata } from "next";
import Link from "next/link";
import {
  AlertTriangle,
  FileText,
  KeyRound,
  MonitorSmartphone,
  Settings,
  Smartphone,
  Volume2,
  WifiOff,
  Wrench
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import {
  logSteps,
  troubleshootingGroups,
  troubleshootingToc
} from "@/data/troubleshooting";

export const metadata: Metadata = {
  title: "故障排查",
  description: "帮助 FgoGotran 用户导出错误纪录，并按手机、模拟器、投屏场景排查常见问题。"
};

const groupIcons: Record<string, LucideIcon> = {
  phone: Smartphone,
  emulator: MonitorSmartphone,
  projection: WifiOff,
  "error-meaning": AlertTriangle
};

export default function TroubleshootingPage() {
  return (
    <div className="docs-shell trouble-guide-shell">
      <aside className="docs-sidebar" aria-label="故障排查分类">
        <div className="docs-sidebar-title">故障排查</div>
        <a href="#error-log">
          <FileText size={16} aria-hidden="true" />
          错误纪录
        </a>
        {troubleshootingGroups.map((group) => {
          const GroupIcon = groupIcons[group.id] ?? Wrench;
          return (
            <a key={group.id} href={`#${group.id}`}>
              <GroupIcon size={16} aria-hidden="true" />
              {group.label}
            </a>
          );
        })}
      </aside>

      <article className="docs-article">
        <header className="docs-hero">
          <p className="eyebrow">Troubleshooting</p>
          <h1>FgoGotran 故障排查</h1>
          <p>
            先找到你看到的现象，再按顺序检查。手机用户通常只需要检查权限、API 和语音设置；模拟器问题更多，请优先导出错误纪录 TXT。
          </p>

          <div className="trouble-jump-nav" aria-label="快速跳转">
            <a className="jump-primary" href="#error-log">
              <FileText size={15} aria-hidden="true" />
              先导出纪录
            </a>
            {troubleshootingGroups.map((group) => {
              const GroupIcon = groupIcons[group.id] ?? Wrench;
              return (
                <a key={group.id} href={`#${group.id}`}>
                  <GroupIcon size={15} aria-hidden="true" />
                  {group.label}
                </a>
              );
            })}
          </div>

          <div className="azure-hero-actions">
            <Link className="secondary-button" href="/guide">
              <Settings size={16} aria-hidden="true" />
              使用指南
            </Link>
            <Link className="secondary-button" href="/api-guide">
              <KeyRound size={16} aria-hidden="true" />
              API 指南
            </Link>
            <Link className="secondary-button" href="/speech-guide">
              <Volume2 size={16} aria-hidden="true" />
              语音指南
            </Link>
          </div>
        </header>

        <section className="docs-section" id="error-log">
          <div className="docs-section-heading">
            <FileText size={24} aria-hidden="true" />
            <div>
              <h2>先导出错误纪录</h2>
              <p>路径：设置页 → 错误纪录 → 导出 TXT。模拟器用户尤其建议先发这个文件。</p>
            </div>
          </div>

          <div className="docs-callout">
            <AlertTriangle size={20} aria-hidden="true" />
            <p>
              先让问题出现一次，再导出 TXT。否则错误纪录可能是空的，或者没有记录到真正的问题。
            </p>
          </div>

          <div className="docs-step-list">
            {logSteps.map((step, index) => (
              <section className="docs-step" key={step.title}>
                <div className="docs-step-number">{index + 1}</div>
                <div className="docs-step-body">
                  <h3>{step.title}</h3>
                  <p>{step.body}</p>
                </div>
              </section>
            ))}
          </div>
        </section>

        {troubleshootingGroups.map((group) => {
          const GroupIcon = groupIcons[group.id] ?? Wrench;
          return (
            <section className="docs-section" id={group.id} key={group.id}>
              <div className="docs-section-heading">
                <GroupIcon size={24} aria-hidden="true" />
                <div>
                  <h2>{group.label}</h2>
                  <p>{group.intro}</p>
                </div>
              </div>

              <div className="qa-list">
                {group.items.map((item) => (
                  <details key={item.title}>
                    <summary>{item.title}</summary>
                    <div className="qa-body">
                      <p className="qa-cause">{item.cause}</p>
                      <ol className="qa-steps">
                        {item.steps.map((step) => (
                          <li key={step}>{step}</li>
                        ))}
                      </ol>
                      {item.verify ? (
                        <p className="qa-verify">确认恢复：{item.verify}</p>
                      ) : null}
                      <p className="qa-escalate">
                        仍失败？先导出错误纪录 TXT，并说明 FgoGotran 版本、设备、FGO 服务器和使用模式，一起发给作者。
                      </p>
                    </div>
                  </details>
                ))}
              </div>
            </section>
          );
        })}
      </article>

      <aside className="docs-toc" aria-label="本页内容">
        <div className="docs-toc-title">本页内容</div>
        {troubleshootingToc.map((item) => (
          <a href={item.href} key={item.href}>
            {item.label}
          </a>
        ))}
      </aside>
    </div>
  );
}