import type { ReactNode } from "react";
import Link from "next/link";

export function WindowShell({ children }: { children: ReactNode }) {
  return (
    <div className="desktop">
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      <div className="window-shell">
        <div className="title-bar" aria-hidden="true">
          <span className="app-mark">R</span>
          <span className="title-bar-label">recordable-community.exe</span>
          <span className="window-controls">
            <i>—</i>
            <i>□</i>
            <i className="close-control">×</i>
          </span>
        </div>
        <nav className="menu-bar" aria-label="Site navigation">
          <Link href="/">Home</Link>
          <Link href="/docs">Docs</Link>
          <Link href="/faq">FAQ</Link>
          <Link href="/report">Report a problem</Link>
          <a href="/downloads/recordable-1.0.0-forge-1.8.9.jar">Download</a>
        </nav>
        <div className="window-content">{children}</div>
        <div className="status-bar" aria-hidden="true">
          <span>Ready</span>
          <span>Unofficial Forge 1.8.9 port</span>
        </div>
      </div>
    </div>
  );
}
