import type { Metadata } from "next";
import { WindowShell } from "../window-shell";
import { ReportBuilder } from "./report-builder";

export const metadata: Metadata = {
  title: "Report Center",
  description:
    "Build a useful record-ish report or find official support for the original Record-able mod.",
};

export default function ReportPage() {
  return (
    <WindowShell>
      <main id="main-content" className="report-page">
        <header className="report-header">
          <div>
            <p className="issue-line">
              SOMETHING KNACKERED? / GIVE US THE USEFUL BITS
            </p>
            <h1>Tell us what broke. Properly.</h1>
            <p>
              &ldquo;It does not work, mate&rdquo; tells us very little. Fill
              this in once and get a report somebody can actually fix.
            </p>
          </div>
          <aside className="report-local-note" aria-label="How reports work">
            <strong>Stays on your machine</strong>
            <span>We are mouthy, not creepy. Nothing gets uploaded here.</span>
          </aside>
        </header>

        <ReportBuilder />
      </main>
    </WindowShell>
  );
}
