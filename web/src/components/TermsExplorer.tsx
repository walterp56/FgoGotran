"use client";

import { useEffect, useMemo, useState } from "react";
import { RefreshCw, Search } from "lucide-react";
import { useTranslations } from "next-intl";
import { siteConfig } from "@/data/site";

type TermPreviewRow = {
  jp: string;
  cn: string;
  en: string;
  category: string;
  aliases?: string;
  source: "character" | "term";
};

type RawPreviewRow = Partial<{
  jp: string;
  cn: string;
  en: string;
  jp_name: string;
  cn_name: string;
  en_name: string;
  jp_term: string;
  cn_term: string;
  en_term: string;
  category: string;
  aliases: string;
}>;

type PreviewStatus = "loading" | "ready" | "error";

const COLUMN_COUNT = 5;

function normalizeRows(rows: RawPreviewRow[], source: TermPreviewRow["source"]): TermPreviewRow[] {
  return rows
    .map((row) => ({
      jp: row.jp ?? row.jp_name ?? row.jp_term ?? "",
      cn: row.cn ?? row.cn_name ?? row.cn_term ?? "",
      en: row.en ?? row.en_name ?? row.en_term ?? "",
      category: row.category ?? source,
      aliases: row.aliases,
      source
    }))
    .filter((row) => row.jp && (row.cn || row.en));
}

function cacheBustedPreviewUrl(path: string) {
  return `${path}${path.includes("?") ? "&" : "?"}t=${Date.now()}`;
}

async function fetchPreviewRows() {
  const [characters, terms] = await Promise.all([
    fetch(cacheBustedPreviewUrl(siteConfig.characterPreviewPath), { cache: "no-store" }),
    fetch(cacheBustedPreviewUrl(siteConfig.termsPreviewPath), { cache: "no-store" })
  ]);
  if (!characters.ok || !terms.ok) {
    throw new Error("preview files not available");
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
  const t = useTranslations("terms");
  const [rows, setRows] = useState<TermPreviewRow[]>([]);
  const [sourceLabel, setSourceLabel] = useState("");
  const [previewStatus, setPreviewStatus] = useState<PreviewStatus>("loading");
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("all");

  async function loadPreviewRows() {
    setRows([]);
    setCategory("all");
    setSourceLabel(t("loadingPreview"));
    setPreviewStatus("loading");
    try {
      const nextRows = await fetchPreviewRows();
      setRows(nextRows);
      setSourceLabel(t("loaded"));
      setPreviewStatus("ready");
    } catch {
      setRows([]);
      setSourceLabel(t("loadFailed"));
      setPreviewStatus("error");
    }
  }

  useEffect(() => {
    let cancelled = false;
    async function loadInitialPreviewRows() {
      setRows([]);
      setCategory("all");
      setSourceLabel(t("loadingPreview"));
      setPreviewStatus("loading");
      try {
        const nextRows = await fetchPreviewRows();
        if (cancelled) return;
        setRows(nextRows);
        setSourceLabel(t("loaded"));
        setPreviewStatus("ready");
      } catch {
        if (!cancelled) {
          setRows([]);
          setSourceLabel(t("loadFailed"));
          setPreviewStatus("error");
        }
      }
    }
    loadInitialPreviewRows();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
      return [row.jp, row.cn, row.en, row.category, row.aliases ?? ""]
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
            placeholder={t("searchPlaceholder")}
          />
        </label>
        <select
          value={category}
          onChange={(event) => setCategory(event.target.value)}
          disabled={previewStatus === "loading"}
        >
          {categories.map((item) => (
            <option key={item} value={item}>
              {item === "all" ? t("allCategories") : item}
            </option>
          ))}
        </select>
      </div>
      <div className="terms-meta">
        <span>{sourceLabel}</span>
        <div className="terms-meta-actions">
          <span>
            {previewStatus === "loading"
              ? t("loading")
              : t("count", { shown: filteredRows.length, total: rows.length })}
          </span>
          <button
            className="terms-refresh"
            type="button"
            onClick={loadPreviewRows}
            disabled={previewStatus === "loading"}
            aria-label={t("refreshAria")}
          >
            <RefreshCw size={14} aria-hidden="true" />
            {t("refresh")}
          </button>
        </div>
      </div>
      <div className="terms-table-wrap">
        <table className="terms-table">
          <thead>
            <tr>
              <th>{t("tableJapanese")}</th>
              <th>{t("tableChinese")}</th>
              <th>{t("tableEnglish")}</th>
              <th>{t("tableCategory")}</th>
              <th>{t("tableAliases")}</th>
            </tr>
          </thead>
          <tbody>
            {previewStatus === "loading" ? (
              <tr>
                <td className="terms-empty" colSpan={COLUMN_COUNT}>
                  {t("loadingRows")}
                </td>
              </tr>
            ) : previewStatus === "error" ? (
              <tr>
                <td className="terms-empty" colSpan={COLUMN_COUNT}>
                  {t("errorRows")}
                </td>
              </tr>
            ) : filteredRows.length > 0 ? (
              filteredRows.map((row) => (
                <tr key={`${row.source}-${row.jp}-${row.cn}-${row.en}`}>
                  <td lang="ja">{row.jp}</td>
                  <td>{row.cn || "-"}</td>
                  <td>{row.en || "-"}</td>
                  <td>{row.category}</td>
                  <td>{row.aliases || "-"}</td>
                </tr>
              ))
            ) : (
              <tr>
                <td className="terms-empty" colSpan={COLUMN_COUNT}>
                  {t("noMatch")}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}