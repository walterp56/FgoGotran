import type { Metadata } from "next";
import { TermsExplorer } from "@/components/TermsExplorer";

export const metadata: Metadata = {
  title: "术语库"
};

export default function TermsPage() {
  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">Terminology</p>
          <h1>术语库校对</h1>
          <p>
            可查看和校对固定翻译，帮助发现漏词、错译和不一致翻译，持续完善 FGO 中文术语库。
          </p>
        </div>
      </section>

      <section className="section">
        <TermsExplorer />
      </section>
    </>
  );
}
