import type { ReactNode } from "react";
import "./globals.css";

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="zh-Hans" data-scroll-behavior="smooth">
      <body>{children}</body>
    </html>
  );
}