export type ProviderSourceLink = {
  key: string;
  href: string;
  kind: "docs" | "key";
};

export type ApiProviderGuide = {
  slug: string;
  providerId: string;
  sourceLinks: ProviderSourceLink[];
  lastChecked: string;
};

export const apiProviderGuides: ApiProviderGuide[] = [
  {
    slug: "deepseek",
    providerId: "deepseek",
    lastChecked: "2026-06-29",
    sourceLinks: [
      { key: "docs", href: "https://api-docs.deepseek.com/", kind: "docs" },
      { key: "apiKey", href: "https://platform.deepseek.com/api_keys", kind: "key" }
    ]
  },
  {
    slug: "zhipu",
    providerId: "zhipu",
    lastChecked: "2026-06-29",
    sourceLinks: [
      { key: "platform", href: "https://open.bigmodel.cn/", kind: "docs" },
      { key: "models", href: "https://docs.bigmodel.cn/cn/guide/start/model-overview", kind: "docs" },
      { key: "apiKey", href: "https://bigmodel.cn/usercenter/proj-mgmt/apikeys", kind: "key" }
    ]
  },
  {
    slug: "qwen-cn",
    providerId: "qwen-cn",
    lastChecked: "2026-06-29",
    sourceLinks: [
      { key: "docs", href: "https://help.aliyun.com/zh/model-studio/", kind: "docs" },
      { key: "console", href: "https://bailian.console.aliyun.com/", kind: "key" }
    ]
  },
  {
    slug: "qwen-intl",
    providerId: "qwen-intl",
    lastChecked: "2026-06-29",
    sourceLinks: [
      { key: "docs", href: "https://www.alibabacloud.com/help/en/model-studio/what-is-model-studio", kind: "docs" },
      { key: "console", href: "https://modelstudio.console.alibabacloud.com/", kind: "key" }
    ]
  },
  {
    slug: "openai",
    providerId: "openai",
    lastChecked: "2026-06-29",
    sourceLinks: [
      { key: "countries", href: "https://help.openai.com/en/articles/5347006-openai-api-supported-countries-and-territories", kind: "docs" },
      { key: "apiKey", href: "https://platform.openai.com/api-keys", kind: "key" }
    ]
  },
  {
    slug: "gemini",
    providerId: "gemini",
    lastChecked: "2026-06-29",
    sourceLinks: [
      { key: "regions", href: "https://ai.google.dev/gemini-api/docs/available-regions", kind: "docs" },
      { key: "apiKey", href: "https://aistudio.google.com/apikey", kind: "key" }
    ]
  },
  {
    slug: "claude",
    providerId: "claude",
    lastChecked: "2026-06-29",
    sourceLinks: [
      { key: "countries", href: "https://www.anthropic.com/supported-countries", kind: "docs" },
      { key: "apiKey", href: "https://platform.claude.com/settings/keys", kind: "key" }
    ]
  },
  {
    slug: "custom",
    providerId: "custom",
    lastChecked: "2026-06-29",
    sourceLinks: [
      { key: "reference", href: "https://platform.openai.com/docs/api-reference/chat/create", kind: "docs" }
    ]
  }
];

export function getApiProviderGuide(slug: string) {
  return apiProviderGuides.find((guide) => guide.slug === slug);
}

export function getApiProviderGuideByProviderId(providerId: string) {
  return apiProviderGuides.find((guide) => guide.providerId === providerId);
}