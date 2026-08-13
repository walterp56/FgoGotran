import type { Metadata } from "next";
import type { StaticImageData } from "next/image";
import Link from "next/link";
import image1 from "./image1.png";
import image2 from "./image2.png";
import image3 from "./image3.png";
import image4 from "./image4.png";
import image5 from "./image5.png";
import image6 from "./image6.png";
import image7 from "./image7.png";
import {
  AlertTriangle,
  Building2,
  CheckCircle2,
  Cloud,
  ExternalLink,
  KeyRound,
  MapPin,
  PlayCircle,
  WalletCards
} from "lucide-react";
import type { LucideIcon } from "lucide-react";

export const metadata: Metadata = {
  title: "Azure 语音服务申请指南",
  description: "教用户申请 Azure Speech 语音服务，取得 Speech Key 和区域，并填入 FgoGotran 使用 AI 语音。"
};

type AzureStep = {
  id: string;
  title: string;
  body: string;
  note?: string;
  value?: string;
  icon: LucideIcon;
};

type SourceLink = {
  label: string;
  href: string;
};

type SpeechGuideImage = {
  image: StaticImageData;
  alt: string;
};

const speechGuideImages: SpeechGuideImage[] = [
  { image: image1, alt: "Azure Speech guide screenshot image 1" },
  { image: image2, alt: "Azure Speech guide screenshot image 2" },
  { image: image3, alt: "Azure Speech guide screenshot image 3" },
  { image: image4, alt: "Azure Speech guide screenshot image 4" },
  { image: image5, alt: "Azure Speech guide screenshot image 5" },
  { image: image6, alt: "Azure Speech guide screenshot image 6" },
  { image: image7, alt: "Azure Speech guide screenshot image 7" },
];

const globalStepImages: SpeechGuideImage[][] = [
  [speechGuideImages[0], speechGuideImages[1], speechGuideImages[2]],
  [speechGuideImages[3], speechGuideImages[4]],
  [speechGuideImages[5]],
  [speechGuideImages[6]],
  [],
  []
];

const sourceLinks: SourceLink[] = [
  {
    label: "Azure 免费账户",
    href: "https://azure.microsoft.com/en-us/pricing/purchase-options/azure-account"
  },
  {
    label: "Azure Speech 价格",
    href: "https://azure.microsoft.com/en-us/pricing/details/speech/"
  },
  {
    label: "Text to Speech 快速开始",
    href: "https://learn.microsoft.com/en-us/azure/ai-services/speech-service/get-started-text-to-speech"
  },
  {
    label: "Speech 支持区域",
    href: "https://learn.microsoft.com/en-us/azure/ai-services/speech-service/regions"
  },
  {
    label: "Azure China / 21Vianet 说明",
    href: "https://learn.microsoft.com/en-us/azure/ai-services/speech-service/sovereign-clouds"
  }
];

const globalSteps: AzureStep[] = [
  {
    id: "azure-account",
    title: "创建或登录 Azure 账户",
    body: "打开Azure Global，选择免费账户或即用即付账户。新用户通常需要完成手机和支付方式验证。",
    note: "https://azure.microsoft.com/zh-cn",
    icon: WalletCards
  },
  {
    id: "create-speech",
    title: "创建 Speech 资源",
    body: "进入 Azure Portal，搜索 Speech service 或 语音服务，创建一个语音资源。",
    icon: Cloud
  },
  {
    id: "select-region",
    title: "填写资源清单",
    body: "使用 Southeast Asia。",
    note: "Free FO层每月包含50万字符的神经语音额度。",
    value: "southeastasia",
    icon: MapPin
  },
  {
    id: "copy-key",
    title: "复制 Key",
    body: "资源创建完成后，进入资源页面的 Keys and Endpoint，复制 KEY 1 或 KEY 2。",
    icon: KeyRound
  },
  {
    id: "app-settings",
    title: "填入 FgoGotran",
    body: "打开 FgoGotran 设置页，进入语音设置，区域选择全球 Azure，粘贴 Azure Speech Key。",
    value: "全球 Azure / southeastasia",
    icon: CheckCircle2
  },
  {
    id: "test-voice",
    title: "测试语音",
    body: "点击测试语音。正常情况下会播放玛修测试台词，代表 Key、区域和 Azure TTS 都可用。",
    value: "瑪修・基列萊特，在此。御主……戰鬥準備完成，請下達指示。",
    icon: PlayCircle
  }
];

const troubleshooting = [
  {
    title: "测试提示 Key 为空",
    body: "还没有填写 Azure Speech Key，或填写后没有保存。"
  },
  {
    title: "401 / 403 / authentication failed",
    body: "通常是 Key 和区域不匹配，或把全球 Azure 的 Key 用到了 Azure China 区域。"
  },
  {
    title: "中国大陆用户连接慢",
    body: "先测试全球 Azure 的 southeastasia。如果账号属于 Azure China，再测试 chinanorth3。"
  },
  {
    title: "免费额度用完",
    body: "等下个月额度刷新，或按 Azure 价格页和自己的预算决定是否继续使用付费层。"
  }
];

function SourceAnchor({ source }: { source: SourceLink }) {
  return (
    <a href={source.href} target="_blank" rel="noreferrer">
      {source.label}
      <ExternalLink size={14} aria-hidden="true" />
    </a>
  );
}

function CodeValue({ children }: { children: string }) {
  return <code className="azure-code-value">{children}</code>;
}

export default function AzureSpeechGuidePage() {
  return (
    <div className="docs-shell azure-guide-shell">
      <aside className="docs-sidebar" aria-label="Azure 语音指南分类">
        <div className="docs-sidebar-title">Azure 语音</div>
        <a href="#choose-cloud">
          <Cloud size={16} aria-hidden="true" />
          选择 Azure
        </a>
        <a href="#global-setup">
          <KeyRound size={16} aria-hidden="true" />
          申请 Key
        </a>
        <a href="#china-azure">
          <Building2 size={16} aria-hidden="true" />
          中国 Azure
        </a>
      </aside>

      <article className="docs-article">
        <header className="docs-hero azure-docs-hero">
          <p className="eyebrow">Azure Speech Guide</p>
          <h1>Azure 语音服务申请指南</h1>
          <div className="azure-hero-actions">
            <a className="primary-button" href="https://portal.azure.com/" target="_blank" rel="noreferrer">
              打开全球 Azure
              <ExternalLink size={16} aria-hidden="true" />
            </a>
            <Link className="secondary-button" href="/guide">
              返回使用指南
            </Link>
          </div>
        </header>

        <section className="docs-scope-panel azure-choice-panel" id="choose-cloud">
          <div className="azure-choice-grid">
            <article className="azure-cloud-card recommended">
              <div className="azure-cloud-card-head">
                <Cloud size={22} aria-hidden="true" />
                <span className="api-recommend-badge">推荐</span>
              </div>
              <h3>全球 Azure 步骤</h3>
              <CodeValue>southeastasia</CodeValue>
              <a href="https://portal.azure.com/" target="_blank" rel="noreferrer">
                portal.azure.com
                <ExternalLink size={14} aria-hidden="true" />
              </a>
            </article>

            <article className="azure-cloud-card">
              <div className="azure-cloud-card-head">
                <Building2 size={22} aria-hidden="true" />
              </div>
              <h3>Azure China</h3>
              <CodeValue>chinanorth3</CodeValue>
              <a href="https://portal.azure.cn/" target="_blank" rel="noreferrer">
                portal.azure.cn
                <ExternalLink size={14} aria-hidden="true" />
              </a>
            </article>
          </div>
        </section>

        <section className="docs-section" id="global-setup">
          <div className="docs-section-heading">
            <KeyRound size={24} aria-hidden="true" />
            <div>
              <h2>全球 Azure</h2>
            </div>
          </div>

          <div className="docs-step-list">
            {globalSteps.map((step, index) => {
              const Icon = step.icon;
              const stepImages = globalStepImages[index] ?? [];
              return (
                <section className="docs-step" id={step.id} key={step.id}>
                  <div className="docs-step-number">{index + 1}</div>
                  <div className="docs-step-body azure-step-body">
                    <div className="azure-step-title">
                      <Icon size={19} aria-hidden="true" />
                      <h3>{step.title}</h3>
                    </div>
                    <p>{step.body}</p>
                    {step.value ? <CodeValue>{step.value}</CodeValue> : null}
                    {step.note ? <p className="docs-note">{step.note}</p> : null}
                    {stepImages.map((stepImage) => (
                      <figure className="azure-step-image-frame" key={stepImage.alt}>
                        <img
                          src={stepImage.image.src}
                          alt={stepImage.alt}
                          width={stepImage.image.width}
                          height={stepImage.image.height}
                          loading="lazy"
                        />
                      </figure>
                    ))}
                  </div>
                </section>
              );
            })}
          </div>
        </section>

        <section className="docs-section" id="china-azure">
          <div className="docs-section-heading">
            <Building2 size={24} aria-hidden="true" />
            <div>
              <h2>中国 Azure 说明</h2>
              <p>Azure China 和全球 Azure 不是同一个入口，Key、区域和接口域名也不同。</p>
            </div>
          </div>

          <div className="docs-callout azure-warning-callout">
            <AlertTriangle size={20} aria-hidden="true" />
            <p>
              如果页面提示不能用个人账号登录，请改用组织、工作或学校账号，或回到 FgoGotran 使用全球 Azure
              的 southeastasia。
            </p>
          </div>

          <div className="api-detail-grid">
            <article className="api-detail-card">
              <div className="guide-label">Portal</div>
              <h2>入口</h2>
              <p>Azure China 使用独立门户。</p>
              <code>https://portal.azure.cn/</code>
            </article>
            <article className="api-detail-card">
              <div className="guide-label">Region</div>
              <h2>App 区域</h2>
              <p>如果你的 Speech 资源建在 China North 3，FgoGotran 中选择中国 Azure。</p>
              <code>chinanorth3</code>
            </article>
          </div>
        </section>

        <section className="docs-section" id="troubleshooting">
          <div className="docs-section-heading">
            <AlertTriangle size={24} aria-hidden="true" />
            <div>
              <h2>常见问题</h2>
              <p>测试语音失败时，可以先按这里排查。</p>
            </div>
          </div>

          <div className="api-detail-grid">
            {troubleshooting.map((item) => (
              <article className="api-detail-card" key={item.title}>
                <div className="guide-label">Check</div>
                <h2>{item.title}</h2>
                <p>{item.body}</p>
              </article>
            ))}
          </div>
        </section>

        <section className="docs-section" id="sources">
          <div className="docs-section-heading">
            <ExternalLink size={24} aria-hidden="true" />
            <div>
              <h2>官方来源</h2>
              <p>Azure 的免费额度、入口和支持区域可能变化，发布前建议再打开官方页面确认。</p>
            </div>
          </div>

          <div className="api-source-list azure-source-list">
            {sourceLinks.map((source) => (
              <SourceAnchor source={source} key={source.href} />
            ))}
          </div>
        </section>
      </article>

      <aside className="docs-toc" aria-label="本页内容">
        <div className="docs-toc-title">本页内容</div>
        <a href="#choose-cloud">选择 Azure</a>
        <a href="#global-setup">全球 Azure 步骤</a>
        <a href="#china-azure">中国 Azure</a>
        <a href="#troubleshooting">常见问题</a>
        <a href="#sources">官方来源</a>
      </aside>
    </div>
  );
}
