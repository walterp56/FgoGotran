import type { Metadata } from "next";
import Link from "next/link";
import {
  AlertTriangle,
  BookOpenCheck,
  Boxes,
  CheckCircle2,
  CircleGauge,
  CloudOff,
  Cpu,
  ExternalLink,
  FileCog,
  FolderOpen,
  KeyRound,
  Laptop,
  Network,
  Play,
  RotateCcw,
  Server,
  Settings2,
  ShieldCheck,
  Smartphone,
  TestTube2,
  Wrench
} from "lucide-react";
import {
  localControlTabs,
  localFinalChecklist,
  localGuideToc,
  localQuickStartSteps,
  localRuntimeDefaults,
  localRuntimeStatuses,
  localTroubleshooting,
  localTuningSteps
} from "@/data/localGuide";

export const metadata: Metadata = {
  title: "FgoGotran Local 使用指南",
  description: "在 Windows 上配置 llama.cpp 与 GGUF，并让 FgoGotran Android 应用通过可信局域网使用本地翻译模型。"
};

const modelStartingPoints = [
  { memory: "6–8 GB 显存", model: "7B/8B Q4", note: "适合作为首次加载与速度测试" },
  { memory: "10–12 GB 显存", model: "8B–14B Q4", note: "实际占用仍受架构和上下文影响" },
  { memory: "16 GB 显存", model: "约 14B Q4", note: "也可尝试较小模型的更高量化" }
];

const phoneSteps = [
  "让手机和电脑连接同一个可信 Wi-Fi 或局域网。",
  "把 Windows 当前网络配置为“专用网络”。",
  "在当前 Profile 选择“可信局域网 + 本机”，保存并重新启动服务。",
  "等待状态变成“已就绪”，点击总览的“显示 Endpoint”，再用其中相同的电脑 IP 与端口打开 /health 地址。",
  "在 FgoGotran 的 API 设置中选择“自定义 / 本地 AI”。",
  "分别点击总览的“显示 Endpoint”和“显示 API Key”，填写两项完整值及模型名称，然后测试并应用设置。"
];

export default function LocalGuidePage() {
  return (
    <div className="docs-shell local-guide-shell">
      <aside className="docs-sidebar" aria-label="FgoGotran Local 指南分类">
        <div className="docs-sidebar-title">FgoGotran Local</div>
        <a href="#quick-start">
          <Play size={16} aria-hidden="true" />
          快速开始
        </a>
        <a href="#control-panel">
          <Settings2 size={16} aria-hidden="true" />
          控制面板
        </a>
        <a href="#phone">
          <Smartphone size={16} aria-hidden="true" />
          连接手机
        </a>
        <a href="#troubleshooting">
          <Wrench size={16} aria-hidden="true" />
          故障排查
        </a>
      </aside>

      <article className="docs-article">
        <header className="docs-hero local-docs-hero">
          <p className="eyebrow">Local AI Guide</p>
          <h1>FgoGotran Local 使用指南</h1>
          <p>在 Windows 电脑运行自己的 GGUF 模型，让手机上的 FgoGotran 通过可信局域网调用本地翻译服务。</p>
          <div className="docs-meta" aria-label="适用环境">
            <span>Windows</span>
            <span>llama.cpp</span>
            <span>GGUF</span>
            <span>简体中文指南</span>
          </div>
          <div className="local-hero-actions">
            <a className="primary-button" href="#quick-start">
              <Play size={17} aria-hidden="true" />
              开始设置
            </a>
            <Link className="secondary-button" href="/api-guide">
              云端 API 指南
            </Link>
          </div>
        </header>

        <section className="docs-scope-panel" id="scope">
          <div className="docs-scope-heading">
            <BookOpenCheck size={22} aria-hidden="true" />
            <div>
              <h2>用途与边界</h2>
              <p>FgoGotran Local 是本地运行控制工具，可在首次确认后自动准备 Python 和 llama.cpp；GGUF 始终由用户管理。</p>
            </div>
          </div>
          <div className="docs-scope-grid">
            <article className="docs-scope-card supported">
              <CloudOff size={20} aria-hidden="true" />
              <div>
                <h3>本地完成翻译推理</h3>
                <p>模型准备完成后，FGO 对白由电脑上的 llama-server 和 GGUF 处理，不需要云端翻译 API Key。</p>
              </div>
            </article>
            <article className="docs-scope-card unsupported">
              <Boxes size={20} aria-hidden="true" />
              <div>
                <h3>大型运行文件不进入 Git</h3>
                <p>Python 和 llama.cpp 的可选下载只写入已忽略的 user_data；也可完全手动配置。工具不会自动修改驱动、防火墙、路由器，或下载 GGUF/TTS 资源。</p>
              </div>
            </article>
          </div>
          <div className="docs-callout local-inline-callout">
            <AlertTriangle size={20} aria-hidden="true" />
            <p>首次自动准备需要联网并会先显示确认；Python 发布者签名以及 llama.cpp 的大小和 SHA-256 必须验证通过。GGUF 请自行从可信发布者取得，本地接口仍使用独立 API Key。</p>
          </div>
        </section>

        <section className="docs-section" id="architecture">
          <div className="docs-section-heading">
            <Network size={24} aria-hidden="true" />
            <div>
              <h2>连接方式</h2>
              <p>管理页面和手机使用的翻译接口是两个不同入口。</p>
            </div>
          </div>

          <div className="local-network-diagram" aria-label="FgoGotran Local 网络连接示意图">
            <div className="local-network-node">
              <Smartphone size={25} aria-hidden="true" />
              <strong>Android 手机</strong>
              <span>FgoGotran</span>
            </div>
            <div className="local-network-link">
              <span>可信局域网</span>
              <span aria-hidden="true">→</span>
            </div>
            <div className="local-network-node primary">
              <Server size={25} aria-hidden="true" />
              <strong>翻译接口</strong>
              <code>电脑 IP:18080</code>
            </div>
            <div className="local-network-link short" aria-hidden="true">→</div>
            <div className="local-network-node">
              <Cpu size={25} aria-hidden="true" />
              <strong>llama-server</strong>
              <span>GGUF 模型</span>
            </div>
          </div>

          <div className="local-control-address">
            <Laptop size={21} aria-hidden="true" />
            <div>
              <strong>电脑管理页面：<code>http://127.0.0.1:18081</code></strong>
              <p>它固定只监听本机。手机不能使用或打开这个地址。</p>
            </div>
          </div>
        </section>

        <section className="docs-section" id="quick-start">
          <div className="docs-section-heading">
            <Play size={24} aria-hidden="true" />
            <div>
              <h2>快速开始</h2>
              <p>第一次使用按顺序完成，不要跳过“已就绪”和连接测试。</p>
            </div>
          </div>

          <div className="local-source-links" aria-label="官方准备资源">
            <a href="https://www.python.org/downloads/windows/" target="_blank" rel="noreferrer">
              Python Windows 下载
              <ExternalLink size={15} aria-hidden="true" />
            </a>
            <a href="https://github.com/ggml-org/llama.cpp/releases" target="_blank" rel="noreferrer">
              llama.cpp 官方 Releases
              <ExternalLink size={15} aria-hidden="true" />
            </a>
          </div>

          <div className="docs-step-list">
            {localQuickStartSteps.map((step, index) => (
              <section className="docs-step" key={step.title}>
                <div className="docs-step-number">{index + 1}</div>
                <div className="docs-step-body">
                  <h3>{step.title}</h3>
                  <p>{step.body}</p>
                  {step.note ? <p className="docs-note">{step.note}</p> : null}
                </div>
              </section>
            ))}
          </div>
        </section>

        <section className="docs-section" id="choose-files">
          <div className="docs-section-heading">
            <FolderOpen size={24} aria-hidden="true" />
            <div>
              <h2>选择 llama.cpp 与 GGUF</h2>
              <p>运行时、显卡驱动、模型架构和 Chat Template 必须彼此兼容。</p>
            </div>
          </div>

          <div className="local-choice-grid">
            <article className="local-choice-card">
              <Server size={21} aria-hidden="true" />
              <h3>llama.cpp 运行包</h3>
              <ul>
                <li>自动设置按 nvidia-smi 的兼容版本选择官方 Windows x64 CUDA build。</li>
                <li>没有匹配 NVIDIA/CUDA 组合时，自动使用官方 CPU x64 build。</li>
                <li>同一 Release 的主包与 CUDA DLL 包会一起校验并解压。</li>
                <li>更新时解压到新目录，测试通过前保留旧版本。</li>
              </ul>
            </article>
            <article className="local-choice-card">
              <Boxes size={21} aria-hidden="true" />
              <h3>GGUF 模型</h3>
              <ul>
                <li>选择 Instruction 或 Chat 模型，不使用未经对齐的 Base 模型。</li>
                <li>确认能够理解日文，并稳定输出所选中文。</li>
                <li>确认 llama.cpp 支持模型架构和 Chat Template。</li>
                <li>从可信发布者下载，并在提供校验值时完成核对。</li>
              </ul>
            </article>
          </div>

          <div className="local-table-wrap">
            <table className="local-guide-table">
              <caption>显存导向的初始选择，不代表翻译质量排名</caption>
              <thead>
                <tr>
                  <th scope="col">可用显存</th>
                  <th scope="col">建议起点</th>
                  <th scope="col">说明</th>
                </tr>
              </thead>
              <tbody>
                {modelStartingPoints.map((item) => (
                  <tr key={item.memory}>
                    <td>{item.memory}</td>
                    <td>{item.model}</td>
                    <td>{item.note}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="local-table-note">模型架构、Context Size、Batch Size 和其他 GPU 程序都会影响真实显存占用。</p>
        </section>

        <section className="docs-section" id="control-panel">
          <div className="docs-section-heading">
            <Settings2 size={24} aria-hidden="true" />
            <div>
              <h2>控制面板</h2>
              <p>页面默认在 <code>127.0.0.1:18081</code> 打开，并分成四个标签页。</p>
            </div>
          </div>

          <div className="local-panel-grid">
            {localControlTabs.map((tab) => (
              <article className="local-panel-card" key={tab.title}>
                <h3>{tab.title}</h3>
                <p>{tab.body}</p>
              </article>
            ))}
          </div>

          <h3 className="local-subheading">首次测试保留这些默认值</h3>
          <div className="local-table-wrap">
            <table className="local-guide-table">
              <thead>
                <tr>
                  <th scope="col">参数</th>
                  <th scope="col">默认值</th>
                  <th scope="col">使用建议</th>
                </tr>
              </thead>
              <tbody>
                {localRuntimeDefaults.map((parameter) => (
                  <tr key={parameter.name}>
                    <td><code>{parameter.name}</code></td>
                    <td>{parameter.defaultValue}</td>
                    <td>{parameter.guidance}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <section className="docs-section" id="phone">
          <div className="docs-section-heading">
            <Smartphone size={24} aria-hidden="true" />
            <div>
              <h2>连接 Android 手机</h2>
              <p>请点击总览的“显示 Endpoint”并复制当前地址，不要凭记忆手动组合 IP 和端口。</p>
            </div>
          </div>

          <div className="docs-callout">
            <AlertTriangle size={20} aria-hidden="true" />
            <p>手机不能填写 <code>localhost</code>、<code>127.0.0.1</code>、<code>0.0.0.0</code>、电脑主机名或路由器地址。</p>
          </div>

          <ol className="local-numbered-list">
            {phoneSteps.map((step) => <li key={step}>{step}</li>)}
          </ol>

          <div className="local-endpoint-grid">
            <article>
              <span>API 地址</span>
              <code>http://&lt;电脑局域网IP&gt;:18080/v1/chat/completions</code>
            </article>
            <article>
              <span>手机浏览器测试</span>
              <code>http://&lt;电脑局域网IP&gt;:18080/health</code>
            </article>
          </div>

          <div className="local-field-map">
            <div><span>电脑总览</span><strong>显示 Endpoint</strong><span aria-hidden="true">→</span><strong>手机“API 地址”</strong></div>
            <div><span>电脑总览</span><strong>Model ID</strong><span aria-hidden="true">→</span><strong>手机“模型”</strong></div>
            <div><span>电脑总览</span><strong>显示 API Key</strong><span aria-hidden="true">→</span><strong>手机“API Key”</strong></div>
          </div>
        </section>

        <section className="docs-section" id="status">
          <div className="docs-section-heading">
            <CircleGauge size={24} aria-hidden="true" />
            <div>
              <h2>运行状态说明</h2>
              <p>只有“已就绪”表示手机现在可以稳定发起翻译请求。</p>
            </div>
          </div>

          <div className="local-status-grid">
            {localRuntimeStatuses.map((status) => (
              <article className="local-status-card" key={status.code}>
                <div>
                  <code>{status.code}</code>
                  <span className={`local-status-pill ${status.tone}`}>{status.label}</span>
                </div>
                <p>{status.meaning}</p>
              </article>
            ))}
          </div>
        </section>

        <section className="docs-section" id="performance">
          <div className="docs-section-heading">
            <Cpu size={24} aria-hidden="true" />
            <div>
              <h2>性能调节顺序</h2>
              <p>每次只调整一类设置，保存并重新启动后再判断效果。</p>
            </div>
          </div>
          <div className="docs-step-list">
            {localTuningSteps.map((step, index) => (
              <section className="docs-step" key={step.title}>
                <div className="docs-step-number">{index + 1}</div>
                <div className="docs-step-body">
                  <h3>{step.title}</h3>
                  <p>{step.body}</p>
                </div>
              </section>
            ))}
          </div>
          <div className="docs-callout local-inline-callout">
            <TestTube2 size={20} aria-hidden="true" />
            <p>判断翻译质量时要连续测试多段 FGO 对白，包括人名、术语、省略主语和较长句子；不要只看内置兼容性测试。</p>
          </div>
        </section>

        <section className="docs-section" id="troubleshooting">
          <div className="docs-section-heading">
            <Wrench size={24} aria-hidden="true" />
            <div>
              <h2>故障排查</h2>
              <p>先按问题现象检查，再查看“系统 → 运行日志”中的最后几行。</p>
            </div>
          </div>
          <div className="local-trouble-list">
            {localTroubleshooting.map((item) => (
              <details key={item.title}>
                <summary>{item.title}</summary>
                <p>{item.body}</p>
              </details>
            ))}
          </div>
        </section>

        <section className="docs-section" id="security">
          <div className="docs-section-heading">
            <ShieldCheck size={24} aria-hidden="true" />
            <div>
              <h2>安全、备份与更新</h2>
              <p>局域网 HTTP 没有传输加密，只适合可信家庭网络或可信专用网络。</p>
            </div>
          </div>

          <div className="local-security-grid">
            <article>
              <ShieldCheck size={20} aria-hidden="true" />
              <h3>网络安全</h3>
              <p>防火墙只允许专用网络。不要使用公共 Wi-Fi，不要关闭整个防火墙，也不要把 18080 或 18081 映射到公网。</p>
            </article>
            <article>
              <KeyRound size={20} aria-hidden="true" />
              <h3>API Key</h3>
              <p>不要分享完整 Key。怀疑泄露时先停止服务，再更换 Key，并更新所有手机；旧 Key 会立即失效。</p>
            </article>
            <article>
              <FileCog size={20} aria-hidden="true" />
              <h3>配置备份</h3>
              <p>更新或重置前备份 user_data。这个目录含 API Key，不能上传、公开或随故障截图一起发送。</p>
            </article>
            <article>
              <RotateCcw size={20} aria-hidden="true" />
              <h3>安全更新</h3>
              <p>先停止服务，再把新版 llama.cpp 解压到新目录。更新路径并通过加载与翻译测试后，才移除旧版本。</p>
            </article>
          </div>
        </section>

        <section className="docs-section" id="checklist">
          <div className="docs-section-heading">
            <CheckCircle2 size={24} aria-hidden="true" />
            <div>
              <h2>最终检查清单</h2>
              <p>全部确认后，再开始长时间运行 FGO 翻译。</p>
            </div>
          </div>
          <ul className="local-checklist">
            {localFinalChecklist.map((item) => (
              <li key={item}>
                <CheckCircle2 size={18} aria-hidden="true" />
                <span>{item}</span>
              </li>
            ))}
          </ul>
        </section>
      </article>

      <aside className="docs-toc" aria-label="本页内容">
        <div className="docs-toc-title">本页内容</div>
        {localGuideToc.map((item) => (
          <a href={item.href} key={item.href}>{item.label}</a>
        ))}
      </aside>
    </div>
  );
}
