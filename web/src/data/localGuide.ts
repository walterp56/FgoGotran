export type LocalStatusDef = {
  code: string;
  tone: "neutral" | "working" | "ready" | "error";
};

export const localStatusDefs: LocalStatusDef[] = [
  { code: "STOPPED", tone: "neutral" },
  { code: "STARTING", tone: "working" },
  { code: "LOADING", tone: "working" },
  { code: "VERIFYING", tone: "working" },
  { code: "RECOVERING", tone: "working" },
  { code: "READY", tone: "ready" },
  { code: "BUSY", tone: "working" },
  { code: "ERROR", tone: "error" }
];

export const localQuickStartKeys = ["python", "llama", "gguf", "panel", "model", "save", "test", "connect"];

export const localControlTabKeys = ["overview", "model", "test", "system"];

export const localParameterKeys = [
  "context",
  "gpu-layers",
  "batch",
  "ubatch",
  "cpu-threads",
  "flash-attention",
  "force-no-think",
  "prompt-cache"
];

export const localTuningKeys = ["close-gpu", "context", "smaller", "batch", "compat"];

export const localTroubleshootingKeys = ["python", "panel", "llama-path", "gguf", "load", "compat", "phone", "save"];

export const localStartingPointKeys = ["small", "medium", "large"];

export const localGuideTocDefs = [
  { href: "#scope", key: "scope" },
  { href: "#architecture", key: "architecture" },
  { href: "#quick-start", key: "quickStart" },
  { href: "#choose-files", key: "chooseFiles" },
  { href: "#control-panel", key: "controlPanel" },
  { href: "#phone", key: "phone" },
  { href: "#status", key: "status" },
  { href: "#performance", key: "performance" },
  { href: "#troubleshooting", key: "troubleshooting" },
  { href: "#security", key: "security" },
  { href: "#checklist", key: "checklist" }
];