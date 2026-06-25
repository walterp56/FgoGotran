"use client";

import { useEffect, useMemo, useState } from "react";
import { AlertCircle, CheckCircle2, Database, Download, RefreshCw } from "lucide-react";
import { cdnUrl, formatBytes, formatDateTime, type AppManifest, type DbManifest } from "@/lib/cdn";
import { siteConfig } from "@/data/site";

type LoadState<T> =
  | { status: "loading"; data?: undefined; error?: undefined }
  | { status: "ready"; data: T; error?: undefined }
  | { status: "error"; data?: undefined; error: string };

async function fetchJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { cache: "no-store" });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export function ReleaseStatus() {
  const [dbState, setDbState] = useState<LoadState<DbManifest>>({ status: "loading" });
  const [appState, setAppState] = useState<LoadState<AppManifest>>({ status: "loading" });

  const dbManifestUrl = useMemo(() => cdnUrl(siteConfig.dbManifestPath), []);
  const appManifestUrl = useMemo(() => cdnUrl(siteConfig.apkManifestPath), []);

  useEffect(() => {
    let cancelled = false;
    fetchJson<DbManifest>(dbManifestUrl)
      .then((data) => {
        if (!cancelled) setDbState({ status: "ready", data });
      })
      .catch((error: Error) => {
        if (!cancelled) setDbState({ status: "error", error: error.message });
      });

    fetchJson<AppManifest>(appManifestUrl)
      .then((data) => {
        if (!cancelled) setAppState({ status: "ready", data });
      })
      .catch((error: Error) => {
        if (!cancelled) setAppState({ status: "error", error: error.message });
      });

    return () => {
      cancelled = true;
    };
  }, [appManifestUrl, dbManifestUrl]);

  return (
    <div className="status-grid">
      <article className="status-card">
        <div className="status-title">
          <Database size={20} aria-hidden="true" />
          <h3>术语库状态</h3>
        </div>
        {dbState.status === "loading" ? (
          <StatusLoading />
        ) : dbState.status === "ready" ? (
          <>
            <p className="status-value">{dbState.data.contentVersion}</p>
            <dl className="compact-list">
              <div>
                <dt>条目</dt>
                <dd>{dbState.data.totalCount}</dd>
              </div>
              <div>
                <dt>生成时间</dt>
                <dd>{formatDateTime(dbState.data.generatedAt)}</dd>
              </div>
              <div>
                <dt>大小</dt>
                <dd>{formatBytes(dbState.data.dbSize)}</dd>
              </div>
            </dl>
          </>
        ) : (
          <StatusError message={`暂时无法读取 CDN：${dbState.error}`} />
        )}
      </article>

      <article className="status-card">
        <div className="status-title">
          <Download size={20} aria-hidden="true" />
          <h3>APK 发布</h3>
        </div>
        {appState.status === "loading" ? (
          <StatusLoading />
        ) : appState.status === "ready" ? (
          <>
            <p className="status-value">v{appState.data.versionName}</p>
            <dl className="compact-list">
              <div>
                <dt>Android</dt>
                <dd>{appState.data.minimumAndroid}</dd>
              </div>
              <div>
                <dt>大小</dt>
                <dd>{formatBytes(appState.data.apkSize)}</dd>
              </div>
              <div>
                <dt>发布时间</dt>
                <dd>{formatDateTime(appState.data.releaseDate)}</dd>
              </div>
            </dl>
            <a className="primary-button small" href={appState.data.apkUrl}>
              <Download size={16} aria-hidden="true" />
              下载最新版
            </a>
          </>
        ) : (
          <StatusError message="APK manifest 尚未发布到 CDN。" />
        )}
      </article>
    </div>
  );
}

function StatusLoading() {
  return (
    <p className="status-note">
      <RefreshCw size={16} aria-hidden="true" />
      正在读取 CDN manifest
    </p>
  );
}

function StatusError({ message }: { message: string }) {
  return (
    <>
      <p className="status-note warning">
        <AlertCircle size={16} aria-hidden="true" />
        {message}
      </p>
      <p className="muted">页面可正常使用；发布对应 manifest 后这里会自动显示。</p>
    </>
  );
}

export function PublishedBadge() {
  return (
    <span className="published-badge">
      <CheckCircle2 size={15} aria-hidden="true" />
      CDN ready
    </span>
  );
}
