"use client";

import { useEffect, useMemo, useState } from "react";
import { Search } from "lucide-react";
import { sampleTermRows, type TermPreviewRow } from "@/data/sampleTerms";
import { cdnUrl } from "@/lib/cdn";
import { siteConfig } from "@/data/site";

type RawPreviewRow = Partial<{
  jp: string;
  cn: string;
  jp_name: string;
  cn_name: string;
  jp_term: string;
  cn_term: string;
  category: string;
  aliases: string;
}>;

function normalizeRows(rows: RawPreviewRow[], source: TermPreviewRow["source"]): TermPreviewRow[] {
  return rows
    .map((row) => ({
      jp: row.jp ?? row.jp_name ?? row.jp_term ?? "",
      cn: row.cn ?? row.cn_name ?? row.cn_term ?? "",
      category: row.category ?? source,
      aliases: row.aliases,
      source
    }))
    .filter((row) => row.jp && row.cn);
}

function previewUrl(path: string) {
  if (typeof window !== "undefined") {
    const hostname = window.location.hostname;
    if (hostname === "localhost" || hostname === "127.0.0.1" || hostname === "::1") {
      return path;
    }
  }
  return cdnUrl(path);
}

export function TermsExplorer() {
  const [rows, setRows] = useState<TermPreviewRow[]>(sampleTermRows);
  const [sourceLabel, setSourceLabel] = useState("内置示例");
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("all");

  useEffect(() => {
    let cancelled = false;
    async function loadPreviewRows() {
      try {
        const [characters, terms] = await Promise.all([
          fetch(previewUrl(siteConfig.characterPreviewPath), { cache: "no-store" }),
          fetch(previewUrl(siteConfig.termsPreviewPath), { cache: "no-store" })
        ]);
        if (!characters.ok || !terms.ok) {
          throw new Error("preview files not published");
        }
        const characterRows = normalizeRows(await characters.json(), "character");
        const termRows = normalizeRows(await terms.json(), "term");
        const nextRows = [...characterRows, ...termRows];
        if (!cancelled && nextRows.length > 0) {
          setRows(nextRows);
          setSourceLabel("CDN 预览数据");
        }
      } catch {
        if (!cancelled) {
          setRows(sampleTermRows);
          setSourceLabel("内置示例");
        }
      }
    }
    loadPreviewRows();
    return () => {
      cancelled = true;
    };
  }, []);

  const categories = useMemo(() => {
    return ["all", ...Array.from(new Set(rows.map((row) => row.category))).sort()];
  }, [rows]);

  const filteredRows = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return rows.filter((row) => {
      const categoryMatch = category === "all" || row.category === category;
      if (!categoryMatch) return false;
      if (!needle) return true;
      return [row.jp, row.cn, row.category, row.aliases ?? ""]
        .join("\n")
        .toLowerCase()
        .includes(needle);
    });
  }, [category, query, rows]);

  return (
    <section className="terms-panel">
      <div className="terms-toolbar">
        <label className="search-field">
          <Search size={18} aria-hidden="true" />
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="搜索日文、中文、别名或分类"
          />
        </label>
        <select value={category} onChange={(event) => setCategory(event.target.value)}>
          {categories.map((item) => (
            <option key={item} value={item}>
              {item === "all" ? "全部分类" : item}
            </option>
          ))}
        </select>
      </div>
      <div className="terms-meta">
        <span>{sourceLabel}</span>
        <span>{filteredRows.length} / {rows.length} 条</span>
      </div>
      <div className="terms-table-wrap">
        <table className="terms-table">
          <thead>
            <tr>
              <th>日文</th>
              <th>简体中文</th>
              <th>分类</th>
              <th>别名</th>
            </tr>
          </thead>
          <tbody>
            {filteredRows.map((row) => (
              <tr key={`${row.source}-${row.jp}-${row.cn}`}>
                <td lang="ja">{row.jp}</td>
                <td>{row.cn}</td>
                <td>{row.category}</td>
                <td>{row.aliases || "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
