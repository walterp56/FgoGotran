import type { Metadata } from "next";
import type { ReactNode } from "react";
import { hasLocale, NextIntlClientProvider } from "next-intl";
import { getMessages, getTranslations, setRequestLocale } from "next-intl/server";
import { notFound } from "next/navigation";
import { DocumentLang } from "@/components/DocumentLang";
import { SiteFooter } from "@/components/SiteFooter";
import { SiteHeader } from "@/components/SiteHeader";
import { routing } from "@/i18n/routing";

export function generateStaticParams() {
  return routing.locales.map((locale) => ({ locale }));
}

export const dynamicParams = false;

type LocaleLayoutProps = {
  children: ReactNode;
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: LocaleLayoutProps): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "meta" });

  return {
    title: {
      default: t("defaultTitle"),
      template: t("titleTemplate")
    },
    description: t("description"),
    openGraph: {
      locale: locale === "zh-TW" ? "zh_TW" : locale === "en" ? "en_US" : "zh_CN",
      type: "website"
    },
    metadataBase: new URL("https://fgogotran.com"),
    icons: {
      icon: "/brand/gotran-icon.png",
      shortcut: "/brand/gotran-icon.png",
      apple: "/brand/gotran-icon.png"
    }
  };
}

export default async function LocaleLayout({ children, params }: LocaleLayoutProps) {
  const { locale } = await params;

  if (!hasLocale(routing.locales, locale)) {
    notFound();
  }

  setRequestLocale(locale);
  const messages = await getMessages();

  // Server Components read messages through getTranslations(), so the client
  // provider only needs the namespaces used by Client Components. Passing the
  // whole catalog would inline every namespace into each page's payload.
  const clientMessages = {
    nav: messages.nav,
    common: messages.common,
    terms: messages.terms
  };

  return (
    <NextIntlClientProvider locale={locale} messages={clientMessages}>
      <DocumentLang />
      <SiteHeader />
      <main>{children}</main>
      <SiteFooter />
    </NextIntlClientProvider>
  );
}