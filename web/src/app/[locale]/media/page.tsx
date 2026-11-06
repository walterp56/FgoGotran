import { NextIntlClientProvider } from "next-intl";
import { getMessages, getTranslations, setRequestLocale } from "next-intl/server";
import { MediaShowcase } from "@/components/MediaShowcase";
import { mediaItems } from "@/data/media";
import { pageMetadata } from "@/lib/seo";

type MediaPageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: MediaPageProps) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "media" });
  return pageMetadata(locale, "media", { title: t("title"), description: t("description") });
}

export default async function MediaPage({ params }: MediaPageProps) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("media");
  const messages = await getMessages();

  return (
    <>
      <section className="page-hero media-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">{t("eyebrow")}</p>
          <h1>{t("h1")}</h1>
        </div>
      </section>
      <section className="section compact media-section">
        <NextIntlClientProvider messages={{ media: messages.media }}>
          <MediaShowcase videos={mediaItems} />
        </NextIntlClientProvider>
      </section>
    </>
  );
}
