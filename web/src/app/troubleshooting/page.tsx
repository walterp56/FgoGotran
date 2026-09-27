import { LocaleRedirect } from "@/components/LocaleRedirect";
import { legacyMetadata } from "@/lib/legacy";

export const metadata = legacyMetadata;

export default function LegacyTroubleshootingPage() {
  return <LocaleRedirect path="troubleshooting" />;
}