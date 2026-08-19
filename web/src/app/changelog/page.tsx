import type { Metadata } from "next";
import { Smartphone } from "lucide-react";
import { SectionHeader } from "@/components/SectionHeader";

export const metadata: Metadata = {
  title: "更新记录"
};

const changelog = [
  {
    title: "v2.3.0",
    date: "20-8-2026",
    icon: Smartphone,
    items: ["改用 MediaProjection 屏幕捕获","提升模拟器与真机截图兼容性","优化半自动模式选项点击逻辑","减少选项翻译红圈误报","选项 OCR 自动重试并使用固定位置","选项区域 2x 放大，提高识别率","修复 MediaProjection 回调与截图回退","优化渲染 UI"]
  },
  {
    title: "v2.2.0",
    date: "18-8-2026",
    icon: Smartphone,
    items: ["优化 AI 翻译提示词结构，降低 API 用量与响应时间","修复小写假名导致的翻译失败","改进裁剪模式逻辑","更新悬浮菜单 UI","翻译上下文新增最近 2 条场景对话，提升代词与语气判断","修复 API 请求中术语与角色名锁定问题","修复模拟器悬浮窗崩溃问题","截图增加超时保护，避免模拟器卡死","更新 UI"]
  },
  {
    title: "v2.1.0",
    date: "15-8-2026",
    icon: Smartphone,
    items: ["优化 AI 翻译提示词结构，提升响应速度","改进裁剪模式：翻译选中范围内所有可见文字，不再漏行","改进裁剪模式显示效果，更贴近原本 OCR 文字大小和位置","优化简中服 / 繁中服的 OCR 与语音流程","修复简中服 / 繁中服语气增强请求处理问题","移除可能导致模拟器崩溃的无障碍状态小圆点","改善模拟器兼容性与运行稳定性","改进角色名显示与语音档案匹配逻辑","更新 UI 与网页说明内容" ]
  },
  {
    title: "v2.0.0",
    date: "13-8-2026",
    icon: Smartphone,
    items: ["新增 AI 语音功能","支持简中服、繁中服","简中 / 繁中服可直接读取游戏中文文本并朗读","新增角色语音档案与 CDN 在线更新”,”新增语音设置页：Azure、语速、音量、测试语音","新增最近错误页面，方便排查问题","新增 ML Kit 中文 OCR”,”优化模拟器兼容性、语音播放、防重复播放","优化 AI 翻译提示词结构","更新 UI" ]
  },
  {
    title: "v1.1.0",
    date: "12-7-2026",
    icon: Smartphone,
    items: ["新增 PaddleOCR，提升文字識別", "新增 x86_64 ABI 支持，改善模拟器兼容性", "修复裁剪模式翻译显示", "优化 AI 翻译提示词结构", "更新UI"]
  },
  {
    title: "v1.0.2",
    date: "5-7-2026",
    icon: Smartphone,
    items: ["修复译文偶尔显示 JSON 格式的问题", "增加显示日文原文", "增加更新弹窗显示APP更新内容", "適配所有螢幕尺寸", "改进区域翻译模式的文本区域适配", "优化历史面板、悬浮按钮交互", "优化 AI 翻译提示词结构", "更新UI"]
  },
  {
    title: "v1.0.1",
    date: "1-7-2026",
    icon: Smartphone,
    items: ["优化 AI 翻译提示词结构"]
  },
  {
    title: "v1.0.0",
    date: "30-6-2026",
    icon: Smartphone,
    items: ["支持手动、半自动、全自动和裁剪模式", "支持自定义翻译 API"]
  }
];

export default function ChangelogPage() {
  return (
    <>
      <section className="page-hero">
        <div className="page-hero-inner">
          <p className="eyebrow">Changelog</p>
          <h1>更新记录</h1>
          <p>记录 FgoGotran Android App 的主要更新。</p>
        </div>
      </section>

      <section className="section">
        <SectionHeader title="最近更新" />
        <div className="changelog-grid">
          {changelog.map((entry) => {
            const Icon = entry.icon;
            return (
              <article className="changelog-card" key={`${entry.title}-${entry.date}`}>
                <div className="status-title">
                  <Icon size={20} aria-hidden="true" />
                  <h3>{entry.title}</h3>
                </div>
                <p className="muted">{entry.date}</p>
                <ul>
                  {entry.items.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              </article>
            );
          })}
        </div>
      </section>
    </>
  );
}
