import type { Metadata } from "next";
import { WindowShell } from "../window-shell";
import { ReportBuilder } from "./report-builder";

export const metadata: Metadata = {
  title: "Report Center",
  description:
    "Build a clear bug, interface, recording, FFmpeg, launcher, documentation, or feature report for Recordable Community or the original Record-able mod.",
};

export default function ReportPage() {
  return (
    <WindowShell>
      <main id="main-content" className="report-page">
        <header className="report-header">
          <div>
            <p className="issue-line">
              RECORDABLE COMMUNITY / GUIDED REPORT CENTER
            </p>
            <h1>Make a useful report.</h1>
            <p>
              Describe the problem once. The desk turns it into a tidy report
              that maintainers can act on.
            </p>
          </div>
          <aside className="report-local-note" aria-label="How reports work">
            <strong>Local tool</strong>
            <span>Nothing is uploaded or submitted from this page.</span>
          </aside>
        </header>

        <ReportBuilder />
      </main>
    </WindowShell>
  );
}
