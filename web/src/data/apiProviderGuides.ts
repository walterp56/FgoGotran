export type ApiProviderGuide = {
  slug: string;
  providerId: string;
  title: string;
  shortTitle: string;
  summary: string;
  vpnSummary: string;
  freeQuotaSummary: string;
  recommendedModels: string[];
  setupNotes: string[];
  sourceLinks: Array<{
    label: string;
    href: string;
  }>;
  lastChecked: string;
};

export const apiProviderGuides: ApiProviderGuide[] = [
  {
    slug: "deepseek",
    providerId: "deepseek",
    title: "DeepSeek API",
    shortTitle: "DeepSeek",
    summary: "低成本、响应快，质量稳定，剧情翻译首选。",
    vpnSummary: "一般网络下通常可以直接测试；如果偶发超时，可以先换网络或稍后重试。",
    freeQuotaSummary: "没有固定公开免费层或试用额度；是否有赠送余额以平台账户页为准。",
    recommendedModels: ["deepseek-v4-flash", "deepseek-v4-pro"],
    setupNotes: [
      "API Key 在 DeepSeek 平台创建后填入 FgoGotran。",
      "测试失败时，先确认账户余额、模型名和 API Key 是否仍可用。",
      "如果 flash 偶尔没有按 FgoGotran 格式返回中文，可以尝试 pro 或备用服务商。"
    ],
    sourceLinks: [
      { label: "DeepSeek 文档", href: "https://api-docs.deepseek.com/" },
      { label: "API Key", href: "https://platform.deepseek.com/api_keys" }
    ],
    lastChecked: "2026-06-29"
  },
  {
    slug: "zhipu",
    providerId: "zhipu",
    title: "智谱 GLM API",
    shortTitle: "智谱 GLM",
    summary: "中文表达稳定，适合作为 DeepSeek 之外的备用服务商。",
    vpnSummary: "一般不需要额外网络工具即可访问智谱开放平台接口；实际可用性仍取决于当前网络环境。",
    freeQuotaSummary: "智谱模型概览中标注了部分免费 Flash 模型；新人额度、活动额度和赠送资源以开放平台控制台显示为准。",
    recommendedModels: ["glm-4.5-air", "glm-4.5-flash"],
    setupNotes: [
      "API Key 在智谱开放平台创建后填入 FgoGotran。",
      "模型名优先使用控制台或文档中的完整名称。",
      "如果需要低成本测试，先用 Flash；如果格式和翻译稳定性不够，再换 Air。"
    ],
    sourceLinks: [
      { label: "智谱开放平台", href: "https://open.bigmodel.cn/" },
      { label: "模型概览", href: "https://docs.bigmodel.cn/cn/guide/start/model-overview" },
      { label: "API Key", href: "https://bigmodel.cn/usercenter/proj-mgmt/apikeys" }
    ],
    lastChecked: "2026-06-29"
  },
  {
    slug: "qwen-cn",
    providerId: "qwen-cn",
    title: "阿里云百炼 Qwen（中国站）",
    shortTitle: "Qwen 中国站",
    summary: "使用阿里云百炼中国站账号和 API Key，适合使用中国站控制台的用户。",
    vpnSummary: "面向中国站控制台和中国站接口时，通常不需要 VPN；如果使用国际站 Key，请改选国际站。",
    freeQuotaSummary: "百炼文档和控制台会列出部分模型的免费额度。qwen-flash/qwen-plus 等模型常见为输入和输出各 100 万 tokens，通常有效期 90 天；以控制台领取页面为准。",
    recommendedModels: ["qwen-flash", "qwen-plus"],
    setupNotes: [
      "FgoGotran 中选择“阿里云百炼 中国站”。",
      "API Key 必须来自中国站百炼控制台。",
      "不要把国际站 API Key 填到中国站配置里；两边的接口地址不同。"
    ],
    sourceLinks: [
      { label: "百炼文档", href: "https://help.aliyun.com/zh/model-studio/" },
      { label: "百炼控制台", href: "https://bailian.console.aliyun.com/" }
    ],
    lastChecked: "2026-06-29"
  },
  {
    slug: "qwen-intl",
    providerId: "qwen-intl",
    title: "Alibaba Cloud Model Studio Qwen（国际站）",
    shortTitle: "Qwen 国际站",
    summary: "使用 Alibaba Cloud 国际站账号和 Model Studio API Key，适合使用国际站控制台的用户。",
    vpnSummary: "国际站接口是否可直连取决于用户所在地和网络环境；无法连接时可换网络，或改用中国站配置。",
    freeQuotaSummary: "国际站文档显示新用户可领取免费 token 额度，常见有效期为 90 天；具体额度、模型和地域以国际站控制台为准。",
    recommendedModels: ["qwen-flash", "qwen-plus"],
    setupNotes: [
      "FgoGotran 中选择“阿里云百炼 国际站”。",
      "API Key 必须来自 Alibaba Cloud Model Studio 国际站。",
      "如果国际站连接不稳定，优先尝试中国站、DeepSeek 或智谱。"
    ],
    sourceLinks: [
      { label: "Model Studio 文档", href: "https://www.alibabacloud.com/help/en/model-studio/what-is-model-studio" },
      { label: "Model Studio 控制台", href: "https://modelstudio.console.alibabacloud.com/" }
    ],
    lastChecked: "2026-06-29"
  },
  {
    slug: "openai",
    providerId: "openai",
    title: "OpenAI API",
    shortTitle: "OpenAI",
    summary: "翻译质量强，但账号、地区和网络要求需要用户自己确认。",
    vpnSummary: "是否需要额外网络工具取决于用户所在地和网络环境；请确认自己能访问 OpenAI API。",
    freeQuotaSummary: "OpenAI API 未提供稳定公开免费层；试用额度或赠送额度以平台账户页显示为准。",
    recommendedModels: ["gpt-4o", "gpt-4o-mini"],
    setupNotes: [
      "需要在 OpenAI 平台创建 API Key 并确认账户有可用额度。",
      "模型名可以按 OpenAI 文档填写；应用内测试成功后再保存。",
      "如果网络或账号地区不可用，建议先使用 DeepSeek、智谱或 Qwen。"
    ],
    sourceLinks: [
      { label: "支持国家/地区", href: "https://help.openai.com/en/articles/5347006-openai-api-supported-countries-and-territories" },
      { label: "API Key", href: "https://platform.openai.com/api-keys" }
    ],
    lastChecked: "2026-06-29"
  },
  {
    slug: "gemini",
    providerId: "gemini",
    title: "Google Gemini API",
    shortTitle: "Gemini",
    summary: "有免费层，适合测试 Google Gemini API；实际网络可用性需要用户自己确认。",
    vpnSummary: "Google AI Studio/Gemini API 的访问地区有限；无法连接时请换网络或使用其他服务商。",
    freeQuotaSummary: "Gemini API 官方文档列出免费层和付费层。免费层请求可能用于改进 Google 产品；付费层规则不同。",
    recommendedModels: ["gemini-3.1-flash-lite", "gemini-3.1-flash"],
    setupNotes: [
      "需要先在 Google AI Studio 创建 API Key。",
      "免费层适合测试，但不要只看免费层决定长期使用成本。",
      "如果在本地网络无法访问，请换其他可直连的服务商。"
    ],
    sourceLinks: [
      { label: "可用地区", href: "https://ai.google.dev/gemini-api/docs/available-regions" },
      { label: "API Key", href: "https://aistudio.google.com/apikey" }
    ],
    lastChecked: "2026-06-29"
  },
  {
    slug: "claude",
    providerId: "claude",
    title: "Anthropic Claude API",
    shortTitle: "Claude",
    summary: "长文本和语气处理强，但成本和接入要求通常更高。",
    vpnSummary: "Anthropic API 支持地区有限；是否可直连取决于用户所在地和网络环境。",
    freeQuotaSummary: "Anthropic 官方文档未列出稳定公开免费层；试用额度或赠送额度以 Console 账户页为准。",
    recommendedModels: ["claude-haiku-4-5-20251001"],
    setupNotes: [
      "应用内必须选择 Claude 接口类型。",
      "Claude API 格式不是 OpenAI Chat Completions；不要填到自定义 OpenAI 兼容接口里。",
      "如果只想低成本日常翻译，优先测试 Haiku。"
    ],
    sourceLinks: [
      { label: "支持国家/地区", href: "https://www.anthropic.com/supported-countries" },
      { label: "API Key", href: "https://platform.claude.com/settings/keys" }
    ],
    lastChecked: "2026-06-29"
  },
  {
    slug: "custom",
    providerId: "custom",
    title: "自定义兼容接口",
    shortTitle: "自定义",
    summary: "适合第三方网关、自建接口、公司代理或其他兼容 OpenAI Chat Completions 的服务。",
    vpnSummary: "是否需要 VPN 或代理取决于第三方服务商和用户网络环境。",
    freeQuotaSummary: "免费额度、试用额度和免费模型完全取决于第三方服务商。",
    recommendedModels: ["按服务商文档填写"],
    setupNotes: [
      "只推荐给熟悉 API 设置的用户。",
      "接口地址需要兼容 OpenAI Chat Completions。",
      "如果测试失败，请先确认模型名、Base URL、Key 和服务商返回格式。"
    ],
    sourceLinks: [
      { label: "OpenAI Chat Completions 参考", href: "https://platform.openai.com/docs/api-reference/chat/create" }
    ],
    lastChecked: "2026-06-29"
  }
];

export function getApiProviderGuide(slug: string) {
  return apiProviderGuides.find((guide) => guide.slug === slug);
}

export function getApiProviderGuideByProviderId(providerId: string) {
  return apiProviderGuides.find((guide) => guide.providerId === providerId);
}

