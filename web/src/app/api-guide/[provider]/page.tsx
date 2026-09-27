import { LocaleRedirect } from "@/components/LocaleRedirect";
import { apiProviderGuides } from "@/data/apiProviderGuides";
import { legacyMetadata } from "@/lib/legacy";

export const metadata = legacyMetadata;

export function generateStaticParams() {
  return apiProviderGuides.map((guide) => ({ provider: guide.slug }));
}

export const dynamicParams = false;

type LegacyApiProviderPageProps = {
  params: Promise<{ provider: string }>;
};

export default async function LegacyApiProviderPage({ params }: LegacyApiProviderPageProps) {
  const { provider } = await params;
  return <LocaleRedirect path={`api-guide/${provider}`} />;
}