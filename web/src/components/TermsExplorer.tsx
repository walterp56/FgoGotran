"use client";

import { useEffect, useMemo, useState } from "react";
import { RefreshCw, Search } from "lucide-react";
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

type PreviewStatus = "loading" | "ready" | "fallback";

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

function cacheBustedPreviewUrl(path: string) {
  const url = previewUrl(path);
  return `${url}${url.includes("?") ? "&" : "?"}t=${Date.now()}`;
}

async function fetchPreviewRows() {
  const [characters, terms] = await Promise.all([
    fetch(cacheBustedPreviewUrl(siteConfig.characterPreviewPath), { cache: "no-store" }),
    fetch(cacheBustedPreviewUrl(siteConfig.termsPreviewPath), { cache: "no-store" })
  ]);
  if (!characters.ok || !terms.ok) {
    throw new Error("preview files not published");
  }

  const characterRows = normalizeRows(await characters.json(), "character");
  const termRows = normalizeRows(await terms.json(), "term");
  const nextRows = [...characterRows, ...termRows];
  if (nextRows.length === 0) {
    throw new Error("preview files are empty");
  }
  return nextRows;
}

export function TermsExplorer() {
  const [rows, setRows] = useState<TermPreviewRow[]>([]);
  const [sourceLabel, setSourceLabel] = useState("正在读取 CDN 预览数据");
  const [previewStatus, setPreviewStatus] = useState<PreviewStatus>("loading");
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("all");

  async function loadPreviewRows() {
    setRows([]);
    setCategory("all");
    setSourceLabel("正在读取 CDN 预览数据");
    setPreviewStatus("loading");
    try {
      const nextRows = await fetchPreviewRows();
      setRows(nextRows);
      setSourceLabel("CDN 预览数据");
      setPreviewStatus("ready");
    } catch {
      setRows(sampleTermRows);
      setSourceLabel("内置示例（CDN 预览暂不可用）");
      setPreviewStatus("fallback");
    }
  }

  useEffect(() => {
    let cancelled = false;
    async function loadInitialPreviewRows() {
      setRows([]);
      setCategory("all");
      setSourceLabel("正在读取 CDN 预览数据");
      setPreviewStatus("loading");
      try {
        const nextRows = await fetchPreviewRows();
        if (cancelled) return;
        setRows(nextRows);
        setSourceLabel("CDN 预览数据");
        setPreviewStatus("ready");
      } catch {
        if (!cancelled) {
          setRows(sampleTermRows);
          setSourceLabel("内置示例（CDN 预览暂不可用）");
          setPreviewStatus("fallback");
        }
      }
    }
    loadInitialPreviewRows();
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
            disabled={previewStatus === "loading"}
            placeholder="搜索日文、中文、别名或分类"
          />
        </label>
        <select
          value={category}
          onChange={(event) => setCategory(event.target.value)}
          disabled={previewStatus === "loading"}
        >
          {categories.map((item) => (
            <option key={item} value={item}>
              {item === "all" ? "全部分类" : item}
            </option>
          ))}
        </select>
      </div>
      <div className="terms-meta">
        <span>{sourceLabel}</span>
        <div className="terms-meta-actions">
          <span>{previewStatus === "loading" ? "读取中" : `${filteredRows.length} / ${rows.length} 条`}</span>
          <button
            className="terms-refresh"
            type="button"
            onClick={loadPreviewRows}
            disabled={previewStatus === "loading"}
            aria-label="刷新术语预览数据"
          >
            <RefreshCw size={14} aria-hidden="true" />
            刷新
          </button>
        </div>
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
            {previewStatus === "loading" ? (
              <tr>
                <td className="terms-empty" colSpan={4}>
                  正在读取 CDN 预览数据...
                </td>
              </tr>
            ) : filteredRows.length > 0 ? (
              filteredRows.map((row) => (
                <tr key={`${row.source}-${row.jp}-${row.cn}`}>
                  <td lang="ja">{row.jp}</td>
                  <td>{row.cn}</td>
                  <td>{row.category}</td>
                  <td>{row.aliases || "—"}</td>
                </tr>
              ))
            ) : (
              <tr>
                <td className="terms-empty" colSpan={4}>
                  没有匹配的术语。
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}
