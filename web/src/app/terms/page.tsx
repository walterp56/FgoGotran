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
          <h1>查看角色名和术语</h1>
        </div>
      </section>

      <section className="section">
        <TermsExplorer />
      </section>
    </>
  );
}
