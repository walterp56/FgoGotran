import type { Metadata } from "next";
import Link from "next/link";
import {
  AlertTriangle,
  ClipboardList,
  FileText,
  KeyRound,
  MonitorSmartphone,
  ScanText,
  Settings,
  ShieldAlert,
  Smartphone,
  Volume2,
  WifiOff,
  Wrench
} from "lucide-react";
import type { LucideIcon } from "lucide-react";

export const metadata: Metadata = {
  title: "故障排查",
  description: "帮助 FgoGotran 用户导出错误纪录，并按手机、模拟器、投屏场景排查常见问题。"
};

type GuideCard = {
  title: string;
  body: string;
  icon: LucideIcon;
};

type Step = {
  title: string;
  body: string;
};

type DiagnosticHelp = {
  title: string;
  means: string;
  action: string;
};

const quickCards: GuideCard[] = [
  {
    title: "手机用户先看权限",
    body: "大多数真机问题来自悬浮窗、无障碍、API Key 或语音 Key。按下面手机检查走一遍通常就够了。",
    icon: Smartphone
  },
  {
    title: "模拟器用户先导出错误纪录",
    body: "模拟器问题更常见，尤其是截图、图形渲染、32 位实例、Android 版本和渠道包名。请优先导出 TXT。",
    icon: MonitorSmartphone
  },
  {
    title: "投屏用户保持手机亮屏",
    body: "FgoGotran 读取的是手机 Android 当前画面，不是电脑窗口。手机黑屏时通常无法翻译。",
    icon: WifiOff
  },
  {
    title: "先重现，再导出",
    body: "让问题出现一次后再打开错误纪录，这样 TXT 里才有真正有用的错误。",
    icon: FileText
  }
];

const logSteps: Step[] = [
  {
    title: "先让问题出现一次",
    body: "例如启动服务失败、点 GO 不翻译、没有语音、测试 API 失败，或模拟器没有截图。"
  },
  {
    title: "打开错误纪录",
    body: "回到 FgoGotran，进入设置页，点击错误纪录。"
  },
  {
    title: "看最上面的记录",
    body: "最新问题会在上方。重点看标题、code、detail、server、mode、speaker。"
  },
  {
    title: "导出 TXT",
    body: "点击导出 TXT，然后把这个文件发给作者。不要只发一张截图。"
  },
  {
    title: "一起说明使用环境",
    body: "告诉作者你用的是手机还是模拟器、FGO 服务器、翻译模式，以及 FgoGotran 版本。"
  }
];

const phoneChecks: GuideCard[] = [
  {
    title: "启动服务前",
    body: "确认悬浮窗权限、FgoGotran 无障碍服务都已开启。首页如果显示未启用，先处理权限。",
    icon: ShieldAlert
  },
  {
    title: "不翻译时",
    body: "日服才需要翻译 API。先到 API 设置页点测试 API；简中服、繁中服主要是读取中文文本并朗读。",
    icon: KeyRound
  },
  {
    title: "没有语音时",
    body: "进入语音设置，确认 AI 语音开启、Speech Key 已填写、区域正确，然后点测试语音。",
    icon: Volume2
  },
  {
    title: "OCR 不准时",
    body: "等文字完全显示后再点 GO。裁剪模式请框住完整文字区域，避免把太多无关 UI 放进去。",
    icon: ScanText
  }
];

const emulatorChecks: GuideCard[] = [
  {
    title: "推荐 64 位 + Android 11 以上",
    body: "旧版、32 位或测试版内核更容易遇到截图、OCR、原生库和无障碍兼容问题。MuMu、雷电、BlueStacks 都优先新建 64 位实例。",
    icon: MonitorSmartphone
  },
  {
    title: "先测手动 GO",
    body: "手动 GO 能翻译，说明 API 和 OCR 基本可用；问题多半在半自动/全自动的画面变化检测或模拟器事件。",
    icon: ScanText
  },
  {
    title: "没有返回截图",
    body: "如果错误纪录出现没有返回截图、当前显示器无效或 bitmap_null，优先切换 OpenGL、DirectX、Vulkan，并重启模拟器。",
    icon: AlertTriangle
  },
  {
    title: "按钮不显示或不能点",
    body: "模拟器可能显示权限已允许，但实际拦截悬浮窗或无障碍。重新开启悬浮窗、无障碍，再启动服务确认 GO / 半 / 全 是否出现。",
    icon: ShieldAlert
  },
  {
    title: "半自动/全自动不动",
    body: "手动 GO 正常但自动模式不动时，通常不是 API 问题，而是模拟器画面事件、截图刷新或游戏窗口状态不稳定。",
    icon: Wrench
  },
  {
    title: "渠道服或特殊包名",
    body: "如果使用 B 服、台服、渠道服或模拟器改包名，错误纪录 TXT 会包含 package、class、app label，方便加入支持。",
    icon: ClipboardList
  }
];

const emulatorSteps: Step[] = [
  {
    title: "确认模拟器环境",
    body: "优先使用新版 64 位实例，并确认 Android 版本是 11 或以上。旧版 MuMu X、测试版内核或 32 位实例不建议作为首选。"
  },
  {
    title: "启动服务后看按钮",
    body: "如果 GO / 半 / 全 没出现，先处理悬浮窗和无障碍。模拟器里权限显示已允许，不一定代表系统真的放行。"
  },
  {
    title: "先用手动 GO 测一次",
    body: "手动 GO 能用，再测试半自动和全自动；手动 GO 也不能用，就先看截图、OCR、API 或语音设置。"
  },
  {
    title: "看到截图错误就换渲染器",
    body: "没有返回截图、当前显示器无效、bitmap_null 多半是模拟器图形层问题。切换渲染器后重启模拟器，再重现一次。"
  },
  {
    title: "导出错误纪录 TXT",
    body: "如果还是失败，把 TXT、模拟器名称和版本、Android 版本、32/64 位、图形渲染模式、FGO 服务器一起发给作者。"
  }
];

const projectionTips: GuideCard[] = [
  {
    title: "为什么电脑有画面但 App 不能翻译",
    body: "投屏软件只是把手机画面显示到电脑。FgoGotran 仍然运行在手机里，只能通过 Android 截图读取手机当前显示内容。",
    icon: MonitorSmartphone
  },
  {
    title: "为什么手机黑屏会失败",
    body: "手机屏幕关闭、变黑或进入省电投屏模式时，Android 可能不给应用真实游戏截图，所以 OCR 没文字。",
    icon: AlertTriangle
  },
  {
    title: "推荐方式",
    body: "优先 USB 有线投屏，并保持手机屏幕亮起。scrcpy 这类工具可以使用 stay-awake / keep-active 选项。",
    icon: Wrench
  },
  {
    title: "亮度影响不大",
    body: "普通屏幕亮度通常不影响 Android 截图像素；真正影响的是息屏、黑屏、隐私屏或特殊显示层。",
    icon: Smartphone
  }
];

const diagnosticHelps: DiagnosticHelp[] = [
  {
    title: "悬浮窗权限未授权",
    means: "App 没有显示在其他应用上层的权限，所以按钮无法显示在 FGO 上。",
    action: "去系统设置允许 FgoGotran 显示在其他应用上层，再回到首页启动服务。"
  },
  {
    title: "无障碍服务未启用",
    means: "Android 没有把游戏画面事件交给 FgoGotran。",
    action: "进入系统无障碍设置，关闭后重新开启 FgoGotran。"
  },
  {
    title: "没有返回截图 / 当前显示器无效 / bitmap_null",
    means: "Android 或模拟器没有给 App 可用的游戏截图。",
    action: "手机保持亮屏；模拟器切换图形渲染模式；投屏时不要使用黑屏模式。"
  },
  {
    title: "API 请求失败",
    means: "翻译接口没有正常返回。",
    action: "在 API 设置页重新测试，确认 Key、模型名、接口地址、余额和网络。"
  },
  {
    title: "Azure TTS 请求失败",
    means: "语音服务没有正常返回音频。",
    action: "在语音设置页测试语音，确认 Speech Key 与区域匹配。"
  },
  {
    title: "未支持的 FGO 包名",
    means: "App 看到一个疑似 FGO 的包名，但还没有加入支持列表。",
    action: "导出错误纪录 TXT，里面会包含 package、class 和 app label。"
  }
];

const feedbackLines = [
  "FgoGotran 版本：",
  "设备：手机 / 模拟器",
  "手机或模拟器型号：",
  "模拟器架构：32 位 / 64 位 / 不知道",
  "图形渲染：OpenGL / DirectX / Vulkan / 不知道",
  "Android 版本：",
  "FGO 服务器：日服 / 简中服 / 繁中服",
  "使用模式：手动 / 半自动 / 全自动 / 裁剪",
  "问题现象：",
  "错误纪录 TXT：已附上",
  "问题画面截图：已附上 / 暂无"
];

const toc = [
  { href: "#error-log", label: "先导出错误纪录" },
  { href: "#phone", label: "手机用户" },
  { href: "#emulator", label: "模拟器用户" },
  { href: "#projection", label: "投屏到电脑" },
  { href: "#error-meaning", label: "错误含义" },
  { href: "#reply-template", label: "反馈模板" }
];

function CardGrid({ items }: { items: GuideCard[] }) {
  return (
    <div className="docs-scope-grid">
      {items.map((item) => {
        const Icon = item.icon;
        return (
          <article className="docs-scope-card supported" key={item.title}>
            <Icon size={20} aria-hidden="true" />
            <div>
              <h3>{item.title}</h3>
              <p>{item.body}</p>
            </div>
          </article>
        );
      })}
    </div>
  );
}

function StepList({ steps }: { steps: Step[] }) {
  return (
    <div className="docs-step-list">
      {steps.map((step, index) => (
        <section className="docs-step" key={step.title}>
          <div className="docs-step-number">{index + 1}</div>
          <div className="docs-step-body">
            <h3>{step.title}</h3>
            <p>{step.body}</p>
          </div>
        </section>
      ))}
    </div>
  );
}

export default function TroubleshootingPage() {
  return (
    <div className="docs-shell trouble-guide-shell">
      <aside className="docs-sidebar" aria-label="故障排查分类">
        <div className="docs-sidebar-title">故障排查</div>
        <a href="#error-log">
          <FileText size={16} aria-hidden="true" />
          错误纪录
        </a>
        <a href="#phone">
          <Smartphone size={16} aria-hidden="true" />
          手机用户
        </a>
        <a href="#emulator">
          <MonitorSmartphone size={16} aria-hidden="true" />
          模拟器用户
        </a>
        <a href="#projection">
          <WifiOff size={16} aria-hidden="true" />
          投屏到电脑
        </a>
      </aside>

      <article className="docs-article">
        <header className="docs-hero">
          <p className="eyebrow">Troubleshooting</p>
          <h1>FgoGotran 故障排查</h1>
          <p>
            手机用户通常只需要检查权限、API 和语音设置。模拟器问题更多，请优先导出错误纪录 TXT。
          </p>
          <div className="azure-hero-actions">
            <Link className="primary-button" href="/guide">
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

        <section className="docs-scope-panel">
          <div className="docs-scope-heading">
            <ClipboardList size={22} aria-hidden="true" />
            <div>
              <h2>先判断你是哪种情况</h2>
              <p>不要从所有问题逐个看。先按自己的使用环境走对应部分。</p>
            </div>
          </div>
          <CardGrid items={quickCards} />
        </section>

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

          <StepList steps={logSteps} />
        </section>

        <section className="docs-section" id="phone">
          <div className="docs-section-heading">
            <Smartphone size={24} aria-hidden="true" />
            <div>
              <h2>手机用户</h2>
              <p>真机问题通常比较少，优先检查权限、API、语音和 OCR 时机。</p>
            </div>
          </div>
          <CardGrid items={phoneChecks} />
        </section>

        <section className="docs-section" id="emulator">
          <div className="docs-section-heading">
            <MonitorSmartphone size={24} aria-hidden="true" />
            <div>
              <h2>模拟器不能用先看这里</h2>
              <p>模拟器更容易出问题，重点看 64 位实例、Android 版本、截图、图形渲染、悬浮窗和包名。</p>
            </div>
          </div>

          <div className="docs-callout">
            <AlertTriangle size={20} aria-hidden="true" />
            <p>
              模拟器不是一台真正手机。不同模拟器的截图、悬浮窗、无障碍实现可能不同，所以手机正常但某些模拟器失败是可能的。
            </p>
          </div>

          <div className="trouble-emulator-checks">
            <CardGrid items={emulatorChecks} />
          </div>

          <StepList steps={emulatorSteps} />
        </section>

        <section className="docs-section" id="projection">
          <div className="docs-section-heading">
            <WifiOff size={24} aria-hidden="true" />
            <div>
              <h2>手机投屏到电脑</h2>
              <p>电脑能看到游戏画面，不代表 FgoGotran 一定能读取画面；关键是手机本机画面是否仍然真实显示。</p>
            </div>
          </div>

          <div className="docs-callout">
            <AlertTriangle size={20} aria-hidden="true" />
            <p>
              不建议使用会让手机屏幕黑屏的投屏模式。FgoGotran 需要手机上的 Android 截图，黑屏时通常无法翻译。
            </p>
          </div>

          <CardGrid items={projectionTips} />
        </section>

        <section className="docs-section" id="error-meaning">
          <div className="docs-section-heading">
            <AlertTriangle size={24} aria-hidden="true" />
            <div>
              <h2>常见错误含义</h2>
              <p>错误纪录里看到这些标题时，可以先按对应方法处理。</p>
            </div>
          </div>

          <div className="api-detail-grid">
            {diagnosticHelps.map((item) => (
              <article className="api-detail-card" key={item.title}>
                <div className="guide-label">错误纪录</div>
                <h2>{item.title}</h2>
                <p>{item.means}</p>
                <p>{item.action}</p>
              </article>
            ))}
          </div>
        </section>

        <section className="docs-section" id="reply-template">
          <div className="docs-section-heading">
            <ClipboardList size={24} aria-hidden="true" />
            <div>
              <h2>反馈模板</h2>
              <p>照这个格式回复，可以更快判断是权限、模拟器、API、语音还是包名问题。</p>
            </div>
          </div>

          <pre className="trouble-feedback-template">{feedbackLines.join("\n")}</pre>
        </section>
      </article>

      <aside className="docs-toc" aria-label="本页内容">
        <div className="docs-toc-title">本页内容</div>
        {toc.map((item) => (
          <a href={item.href} key={item.href}>
            {item.label}
          </a>
        ))}
      </aside>
    </div>
  );
}
