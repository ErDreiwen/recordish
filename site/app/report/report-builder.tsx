"use client";

import { useMemo, useState } from "react";
import { COMMUNITY_DISCORD, PORT_ISSUES } from "../links";
import { RELEASE } from "../release";

type Category =
  | "bug"
  | "ui"
  | "recording"
  | "ffmpeg"
  | "launcher"
  | "docs"
  | "feature"
  | "other";

type Project = "community" | "original";
type ReportMode = "quick" | "technical";

type ReportState = {
  project: Project;
  category: Category;
  summary: string;
  steps: string;
  expected: string;
  actual: string;
  logs: string;
  launcher: string;
  operatingSystem: string;
  java: string;
  minecraft: string;
  modVersion: string;
  loader: string;
  ffmpeg: string;
  frequency: string;
  otherMods: string;
  optifine: boolean;
  shaders: boolean;
  privacyChecked: boolean;
};

const CATEGORIES: {
  value: Category;
  label: string;
  short: string;
}[] = [
  { value: "bug", label: "Bug", short: "Something is properly broken" },
  { value: "ui", label: "UI / usability", short: "Looks daft or fights you" },
  {
    value: "recording",
    label: "Recording / output",
    short: "Video, audio, replay, or clips",
  },
  {
    value: "ffmpeg",
    label: "FFmpeg / install",
    short: "Setup, path, or encoder grief",
  },
  {
    value: "launcher",
    label: "Launcher compatibility",
    short: "Prism, Lunar, or something odd",
  },
  { value: "docs", label: "Docs", short: "The guide is missing or muddy" },
  { value: "feature", label: "Feature request", short: "One sensible improvement" },
  { value: "other", label: "Other / unsure", short: "No clue where it belongs" },
];

const CATEGORY_HINTS: Record<Category, string> = {
  bug: "Give the shortest repeatable route to the failure. “It broke” is not a route.",
  ui: "Name the screen, control, or wording that made you stop and squint.",
  recording: "Include the output format and whether video, game audio, or microphone audio was affected.",
  ffmpeg: "Run Test FFmpeg and include the result. Believe the test, not vibes.",
  launcher: "Name the exact launcher profile. Lunar means Vanilla/Forge.",
  docs: "Name the muddy bit and what you expected it to explain.",
  feature: "Tell us what problem it solves. Another button needs a reason.",
  other: "Say what you tried, where you got stuck, and what you expected.",
};

const initialReport: ReportState = {
  project: "community",
  category: "bug",
  summary: "",
  steps: "",
  expected: "",
  actual: "",
  logs: "",
  launcher: "",
  operatingSystem: "",
  java: "Java 8 (64-bit)",
  minecraft: "1.8.9",
  modVersion: RELEASE.version,
  loader: "Forge 11.15.1.2318",
  ffmpeg: "",
  frequency: "",
  otherMods: "",
  optifine: false,
  shaders: false,
  privacyChecked: false,
};

function clean(value: string, fallback = "_Not provided_") {
  return value.trim() || fallback;
}

function reportTitle(report: ReportState) {
  const category =
    CATEGORIES.find((item) => item.value === report.category)?.label ??
    "Report";
  return `[${category}] ${clean(report.summary, "Short summary")}`;
}

function projectLabel(project: Project) {
  return project === "community"
    ? "record-ish — Forge 1.8.9"
    : "Original Record-able — Fabric";
}

function markdownFor(report: ReportState, mode: ReportMode) {
  const category =
    CATEGORIES.find((item) => item.value === report.category)?.label ??
    "Other";

  if (mode === "quick") {
    return `# ${reportTitle(report)}

> **Project:** ${projectLabel(report.project)}
> **Area:** ${category}
> **Report type:** Quick report

## What went wrong

${clean(report.actual)}

## What I was doing

${clean(report.steps)}

## The basics

| Item | Value |
| --- | --- |
| Launcher | ${clean(report.launcher, "Unknown")} |
| Frequency | ${clean(report.frequency, "Unknown")} |

## Privacy check

${report.privacyChecked ? "- [x]" : "- [ ]"} I reviewed this report and removed private information.
`;
  }

  const modifiers = [
    report.optifine ? "OptiFine installed" : "No OptiFine reported",
    report.shaders ? "Shaders enabled" : "Shaders disabled",
  ].join("; ");

  return `# ${reportTitle(report)}

> **Project:** ${projectLabel(report.project)}
> **Area:** ${category}
> **Report type:** Full nerd report

## Summary

${clean(report.summary)}

## Steps to reproduce

${clean(report.steps)}

## Expected result

${clean(report.expected)}

## Actual result

${clean(report.actual)}

## Environment

| Item | Value |
| --- | --- |
| Minecraft | ${clean(report.minecraft, "Unknown")} |
| Mod version | ${clean(report.modVersion, "Unknown")} |
| Mod loader | ${clean(report.loader, "Unknown")} |
| Launcher | ${clean(report.launcher, "Unknown")} |
| Operating system | ${clean(report.operatingSystem, "Unknown")} |
| Java | ${clean(report.java, "Unknown")} |
| FFmpeg | ${clean(report.ffmpeg, "Unknown")} |
| Frequency | ${clean(report.frequency, "Unknown")} |
| Other mods / packs | ${clean(report.otherMods, "None reported")} |
| Graphics modifiers | ${modifiers} |

## Relevant logs

\`\`\`text
${clean(report.logs, "No logs attached")}
\`\`\`

## Privacy check

${report.privacyChecked ? "- [x]" : "- [ ]"} I reviewed this report and removed private information.
`;
}

function slugFor(value: string) {
  const slug = value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 48);
  return slug || "recordable-report";
}

export function ReportBuilder() {
  const [report, setReport] = useState<ReportState>(initialReport);
  const [reportMode, setReportMode] = useState<ReportMode>("quick");
  const [actionStatus, setActionStatus] = useState(
    "Your draft stays in this tab. Nothing has been sent.",
  );

  const markdown = useMemo(
    () => markdownFor(report, reportMode),
    [report, reportMode],
  );
  const issueUrl = useMemo(() => {
    const parameters = new URLSearchParams({
      title: reportTitle(report),
      body: markdown,
    });
    return `${PORT_ISSUES}?${parameters.toString()}`;
  }, [markdown, report]);
  const completenessItems =
    reportMode === "quick"
      ? [
          Boolean(report.project),
          Boolean(report.category),
          report.summary.trim().length >= 8,
          report.steps.trim().length >= 10,
          report.actual.trim().length >= 5,
          Boolean(report.launcher),
          Boolean(report.frequency),
          report.privacyChecked,
        ]
      : [
          Boolean(report.project),
          Boolean(report.category),
          report.summary.trim().length >= 8,
          report.steps.trim().length >= 10,
          report.expected.trim().length >= 5,
          report.actual.trim().length >= 5,
          Boolean(report.launcher),
          Boolean(report.operatingSystem),
          Boolean(report.frequency),
          report.privacyChecked,
        ];
  const completed = completenessItems.filter(Boolean).length;
  const completeness = Math.round((completed / completenessItems.length) * 100);
  const selectedCategory = CATEGORIES.find(
    (item) => item.value === report.category,
  );

  function setField<Key extends keyof ReportState>(
    field: Key,
    value: ReportState[Key],
  ) {
    setReport((current) => ({ ...current, [field]: value }));
  }

  function setProject(project: Project) {
    setReport((current) => ({
      ...current,
      project,
      minecraft: project === "community" ? "1.8.9" : "",
      modVersion: project === "community" ? RELEASE.version : "",
      loader:
        project === "community"
          ? "Forge 11.15.1.2318"
          : "Fabric (version unknown)",
      launcher: "",
      java: project === "community" ? "Java 8 (64-bit)" : "Unknown",
    }));
  }

  async function copyReport() {
    if (!report.privacyChecked) {
      setActionStatus(
        "Tick the privacy box first. We are not helping you leak your own secrets.",
      );
      return;
    }

    try {
      await navigator.clipboard.writeText(markdown);
      setActionStatus("Copied. Lob it into Discord when you are ready.");
    } catch {
      const textArea = document.createElement("textarea");
      textArea.value = markdown;
      textArea.setAttribute("readonly", "");
      textArea.style.position = "fixed";
      textArea.style.opacity = "0";
      document.body.appendChild(textArea);
      textArea.select();
      const copied = document.execCommand("copy");
      textArea.remove();
      setActionStatus(
        copied
          ? "Copied. Lob it into Discord when you are ready."
          : "Clipboard blocked it. Select the preview and copy it manually.",
      );
    }
  }

  function downloadReport() {
    if (!report.privacyChecked) {
      setActionStatus(
        "Tick the privacy box first. We are not helping you leak your own secrets.",
      );
      return;
    }

    const file = new Blob([markdown], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(file);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `${slugFor(report.summary)}.md`;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
    setActionStatus("Saved as Markdown.");
  }

  function openIssue() {
    if (!report.privacyChecked) {
      setActionStatus(
        "Tick the privacy box first. We are not helping you leak your own secrets.",
      );
      return;
    }

    window.open(issueUrl, "_blank", "noopener,noreferrer");
    setActionStatus(
      "GitHub opened with the report filled in. You still need to submit it there.",
    );
  }

  function resetReport() {
    setReport(initialReport);
    setReportMode("quick");
    setActionStatus("Draft binned. Nothing was submitted.");
  }

  return (
    <div
      className={
        report.project === "original"
          ? "report-builder report-builder-upstream"
          : "report-builder"
      }
    >
      <section className="report-form" aria-labelledby="report-form-title">
        <div className="report-section-heading">
          <div>
            <p className="section-number">01 / DESCRIBE</p>
            <h2 id="report-form-title">What needs sorting?</h2>
          </div>
          {report.project === "community" ? (
            <div className="completeness">
              <label htmlFor="report-completeness">
                Ready to share <strong>{completeness}%</strong>
              </label>
              <progress
                id="report-completeness"
                max="100"
                value={completeness}
              >
                {completeness}%
              </progress>
              <span>{completed} of {completenessItems.length} essentials</span>
            </div>
          ) : null}
        </div>

        <fieldset className="category-fieldset">
          <legend>Which thing are you actually using?</legend>
          <div className="project-choice">
            <label
              className={
                report.project === "community"
                  ? "project-card project-card-selected"
                  : "project-card"
              }
            >
              <input
                checked={report.project === "community"}
                name="project"
                onChange={() => setProject("community")}
                type="radio"
                value="community"
              />
              <span>
                <strong>record-ish</strong>
                <small>A Record-able port · Forge 1.8.9</small>
              </span>
              <b>THIS PORT</b>
            </label>
            <label
              className={
                report.project === "original"
                  ? "project-card project-card-selected"
                  : "project-card"
              }
            >
              <input
                checked={report.project === "original"}
                name="project"
                onChange={() => setProject("original")}
                type="radio"
                value="original"
              />
              <span>
                <strong>Original Record-able</strong>
                <small>Official mod · Fabric</small>
              </span>
              <b>UPSTREAM</b>
            </label>
          </div>
        </fieldset>

        {report.project === "original" ? (
          <section
            aria-labelledby="upstream-shove-title"
            className="upstream-shove"
          >
            <p className="eyebrow">ORIGINAL FABRIC MOD SELECTED</p>
            <h2 id="upstream-shove-title">
              Original mod? Fuck off upstream, mate.
            </h2>
            <p>
              That one is not mine to fix. I did not build it, I do not
              maintain it, and a report here would go straight in the
              imaginary bin. Use the original Modrinth page, or ask in my
              Discord if you are not sure where the problem belongs.
            </p>
            <p className="upstream-fine-print">
              No disrespect to the original lot. It is simply their mod, their
              support desk, and absolutely not my problem.
            </p>
            <div className="upstream-links">
              <a
                className="button button-primary"
                href={COMMUNITY_DISCORD}
                rel="noreferrer"
                target="_blank"
              >
                Ask in my Discord ↗
              </a>
              <a
                className="button"
                href="https://modrinth.com/mod/record-able"
                rel="noreferrer"
                target="_blank"
              >
                Official Modrinth ↗
              </a>
            </div>
          </section>
        ) : (
          <>
            <fieldset className="category-fieldset report-mode-fieldset">
              <legend>How much effort are we dealing with?</legend>
              <div className="report-mode-choice">
                <label
                  className={
                    reportMode === "quick"
                      ? "mode-card mode-card-selected"
                      : "mode-card"
                  }
                >
                  <input
                    checked={reportMode === "quick"}
                    name="report-mode"
                    onChange={() => setReportMode("quick")}
                    type="radio"
                    value="quick"
                  />
                  <span>
                    <strong>Quick report</strong>
                    <small>No logs, no jargon. About a minute.</small>
                  </span>
                  <b>RECOMMENDED</b>
                </label>
                <label
                  className={
                    reportMode === "technical"
                      ? "mode-card mode-card-selected"
                      : "mode-card"
                  }
                >
                  <input
                    checked={reportMode === "technical"}
                    name="report-mode"
                    onChange={() => setReportMode("technical")}
                    type="radio"
                    value="technical"
                  />
                  <span>
                    <strong>Full nerd report</strong>
                    <small>I know what logs and versions are.</small>
                  </span>
                  <b>DETAILED</b>
                </label>
              </div>
            </fieldset>

        <fieldset className="category-fieldset report-category-fieldset">
          <legend>What has gone wrong? Do not overthink it.</legend>
          <div className="category-grid">
            {CATEGORIES.map((item) => (
              <label
                className={
                  report.category === item.value
                    ? "category-card category-card-selected"
                    : "category-card"
                }
                key={item.value}
              >
                <input
                  checked={report.category === item.value}
                  name="category"
                  onChange={() => setField("category", item.value)}
                  type="radio"
                  value={item.value}
                />
                <span>
                  <strong>{item.label}</strong>
                  <small>{item.short}</small>
                </span>
              </label>
            ))}
          </div>
          <p className="field-hint">{CATEGORY_HINTS[report.category]}</p>
        </fieldset>

        <div className="report-fields">
          <label className="field field-wide">
            <span>
              Give it a short name <b aria-hidden="true">*</b>
              <small>{report.summary.length}/100</small>
            </span>
            <input
              maxLength={100}
              onChange={(event) => setField("summary", event.target.value)}
              placeholder="Example: FFmpeg download gives up at 80%"
              required
              value={report.summary}
            />
          </label>

          {reportMode === "quick" ? (
            <>
              <label className="field field-wide">
                <span>
                  What went wrong? <b aria-hidden="true">*</b>
                  <small>Plain English is perfect</small>
                </span>
                <textarea
                  maxLength={1200}
                  onChange={(event) => setField("actual", event.target.value)}
                  placeholder="Example: I closed Minecraft and the video would not play."
                  required
                  rows={5}
                  value={report.actual}
                />
              </label>

              <label className="field field-wide">
                <span>
                  What were you doing just before?{" "}
                  <b aria-hidden="true">*</b>
                  <small>No detective novel needed</small>
                </span>
                <textarea
                  maxLength={1200}
                  onChange={(event) => setField("steps", event.target.value)}
                  placeholder="Example: I pressed record, played one match, then closed the game."
                  required
                  rows={4}
                  value={report.steps}
                />
              </label>

              <label className="field">
                <span>
                  Launcher / profile <b aria-hidden="true">*</b>
                </span>
                <select
                  onChange={(event) => setField("launcher", event.target.value)}
                  required
                  value={report.launcher}
                >
                  <option value="">Choose one…</option>
                  <option>Prism Launcher</option>
                  <option>Lunar — Vanilla/Forge</option>
                  <option>Lunar — branded/Ichor</option>
                  <option>Minecraft Launcher</option>
                  <option>MultiMC</option>
                  <option>Other launcher</option>
                </select>
              </label>

              <label className="field">
                <span>
                  How often? <b aria-hidden="true">*</b>
                </span>
                <select
                  onChange={(event) =>
                    setField("frequency", event.target.value)
                  }
                  required
                  value={report.frequency}
                >
                  <option value="">Choose one…</option>
                  <option>Every time</option>
                  <option>Often</option>
                  <option>Sometimes</option>
                  <option>Happened once</option>
                  <option>Not sure</option>
                </select>
              </label>
            </>
          ) : (
            <>
              <label className="field field-wide">
                <span>
                  Steps to reproduce <b aria-hidden="true">*</b>
                  <small>One action per line works best</small>
                </span>
                <textarea
                  onChange={(event) => setField("steps", event.target.value)}
                  placeholder={
                    "1. Open settings\n2. Choose Download FFmpeg\n3. ..."
                  }
                  required
                  rows={5}
                  value={report.steps}
                />
              </label>

              <label className="field">
                <span>
                  Expected <b aria-hidden="true">*</b>
                </span>
                <textarea
                  onChange={(event) => setField("expected", event.target.value)}
                  placeholder="What should have happened?"
                  required
                  rows={4}
                  value={report.expected}
                />
              </label>

              <label className="field">
                <span>
                  Actually happened <b aria-hidden="true">*</b>
                </span>
                <textarea
                  onChange={(event) => setField("actual", event.target.value)}
                  placeholder="What did it do instead? Include the exact error if there is one."
                  required
                  rows={4}
                  value={report.actual}
                />
              </label>
            </>
          )}
        </div>

        {reportMode === "technical" ? (
          <>
            <details className="environment-panel" open>
          <summary>
            <span>
              <strong>02 / ENVIRONMENT</strong>
              <small>Versions, launcher, and the usual suspects</small>
            </span>
          </summary>
          <div className="environment-grid">
            <label className="field">
              <span>Minecraft version</span>
              <input
                onChange={(event) =>
                  setField("minecraft", event.target.value)
                }
                placeholder={
                  report.project === "community" ? "1.8.9" : "Example: 1.21.4"
                }
                value={report.minecraft}
              />
            </label>

            <label className="field">
              <span>Mod version</span>
              <input
                onChange={(event) =>
                  setField("modVersion", event.target.value)
                }
                placeholder={
                  report.project === "community" ? RELEASE.version : "From Modrinth"
                }
                value={report.modVersion}
              />
            </label>

            <label className="field">
              <span>Launcher / profile</span>
              <select
                onChange={(event) => setField("launcher", event.target.value)}
                value={report.launcher}
              >
                <option value="">Choose one…</option>
                <option>Prism Launcher</option>
                <option>Lunar — Vanilla/Forge</option>
                <option>Lunar — branded/Ichor</option>
                <option>Minecraft Launcher</option>
                <option>MultiMC</option>
                <option>Other launcher</option>
              </select>
            </label>

            <label className="field">
              <span>Operating system</span>
              <select
                onChange={(event) =>
                  setField("operatingSystem", event.target.value)
                }
                value={report.operatingSystem}
              >
                <option value="">Choose one…</option>
                <option>Windows 11</option>
                <option>Windows 10</option>
                <option>Windows 8.1 / 8</option>
                <option>Windows 7</option>
                <option>Linux</option>
                <option>macOS</option>
                <option>Other / unknown</option>
              </select>
            </label>

            <label className="field">
              <span>Java</span>
              <select
                onChange={(event) => setField("java", event.target.value)}
                value={report.java}
              >
                <option>Java 8 (64-bit)</option>
                <option>Java 8 (32-bit)</option>
                <option>Java 17</option>
                <option>Java 21</option>
                <option>Java 25</option>
                <option>Other Java version</option>
                <option>Unknown</option>
              </select>
            </label>

            <label className="field">
              <span>Mod loader / version</span>
              <input
                onChange={(event) => setField("loader", event.target.value)}
                placeholder={
                  report.project === "community"
                    ? "Forge 11.15.1.2318"
                    : "Fabric Loader version"
                }
                value={report.loader}
              />
            </label>

            <label className="field">
              <span>FFmpeg status</span>
              <select
                onChange={(event) => setField("ffmpeg", event.target.value)}
                value={report.ffmpeg}
              >
                <option value="">Choose one…</option>
                <option>Test passed</option>
                <option>Install or download failed</option>
                <option>Custom path configured</option>
                <option>Not installed</option>
                <option>Unknown / not relevant</option>
              </select>
            </label>

            <label className="field">
              <span>How often?</span>
              <select
                onChange={(event) => setField("frequency", event.target.value)}
                value={report.frequency}
              >
                <option value="">Choose one…</option>
                <option>Every time</option>
                <option>Often</option>
                <option>Sometimes</option>
                <option>Happened once</option>
                <option>Unknown / not relevant</option>
              </select>
            </label>

            <label className="field field-wide">
              <span>Other mods, shaders, or resource packs</span>
              <input
                onChange={(event) => setField("otherMods", event.target.value)}
                placeholder="Example: OptiFine L5, Patcher, replay mods, pack name"
                value={report.otherMods}
              />
            </label>

            <fieldset className="environment-checks">
              <legend>Graphics modifiers</legend>
              <label>
                <input
                  checked={report.optifine}
                  onChange={(event) =>
                    setField("optifine", event.target.checked)
                  }
                  type="checkbox"
                />
                OptiFine installed
              </label>
              <label>
                <input
                  checked={report.shaders}
                  onChange={(event) =>
                    setField("shaders", event.target.checked)
                  }
                  type="checkbox"
                />
                Shaders enabled
              </label>
            </fieldset>
          </div>
        </details>

        <details className="logs-panel">
          <summary>
            <span>
              <strong>03 / LOGS</strong>
              <small>Optional, unless it contains the answer</small>
            </span>
          </summary>
          <div className="logs-content">
            <label className="field">
              <span>Relevant log excerpt</span>
              <textarea
                onChange={(event) => setField("logs", event.target.value)}
                placeholder="Paste only the lines around the error from logs/latest.log."
                rows={8}
                spellCheck={false}
                value={report.logs}
              />
            </label>
          </div>
        </details>
          </>
        ) : null}

        <div className="privacy-gate">
          <div className="privacy-warning" role="note">
            <strong>Privacy. Yes, it matters.</strong>
            <p>
              Reports and logs can expose usernames, server addresses, chat,
              folder names, or access tokens. Strip that out. Never paste a
              password. We are not that desperate.
            </p>
          </div>
          <label className="privacy-check">
            <input
              checked={report.privacyChecked}
              onChange={(event) =>
                setField("privacyChecked", event.target.checked)
              }
              type="checkbox"
            />
            <span>I reviewed this report and removed private information.</span>
          </label>
        </div>
          </>
        )}
      </section>

      {report.project === "community" ? (
        <aside className="report-output" aria-labelledby="preview-title">
          <div className="output-heading">
          <div>
            <p className="section-number">READY TO SEND</p>
            <h2 id="preview-title">Your tidy little report</h2>
          </div>
          <span className="draft-badge">NOT SENT</span>
        </div>

        <p className="report-title-preview">{reportTitle(report)}</p>
        {reportMode === "quick" ? (
          <details className="generated-report-panel">
            <summary>See the generated report</summary>
            <textarea
              aria-label="Generated Markdown report"
              className="markdown-preview"
              readOnly
              rows={16}
              value={markdown}
            />
          </details>
        ) : (
          <textarea
            aria-label="Generated Markdown report"
            className="markdown-preview"
            readOnly
            rows={24}
            value={markdown}
          />
        )}

        <div className="output-actions">
          {reportMode === "quick" ? (
            <button
              aria-describedby="share-help"
              className="button button-primary"
              disabled={!report.privacyChecked}
              onClick={openIssue}
              type="button"
            >
              Open report on GitHub ↗
            </button>
          ) : null}
          <button
            aria-describedby="share-help"
            className={
              reportMode === "technical"
                ? "button button-primary"
                : "button"
            }
            disabled={!report.privacyChecked}
            onClick={copyReport}
            type="button"
          >
            {reportMode === "quick" ? "Copy instead" : "Copy report"}
          </button>
          <button
            aria-describedby="share-help"
            className="button"
            disabled={!report.privacyChecked}
            onClick={downloadReport}
            type="button"
          >
            Save .md
          </button>
          <button className="text-button" onClick={resetReport} type="button">
            Bin draft
          </button>
        </div>
        <p className="share-help" id="share-help">
          {reportMode === "quick"
            ? "Tick the privacy box first. The GitHub button fills the report in, but nothing is submitted until you approve it there."
            : "Tick the privacy box first, then copy or save the report and open the port issue link below."}
        </p>
        <p className="action-status" aria-live="polite">
          {actionStatus}
        </p>

        <div className="handoff-card">
          <p className="eyebrow">Need a human?</p>
          <h3>Give us the useful bits.</h3>
          <p>
            Port bugs belong on the record-ish issue tracker. If you are
            properly stuck, ask in my Discord and bring the useful bits.
          </p>
          <div className="handoff-links">
            <a
              className="button"
              href={COMMUNITY_DISCORD}
              rel="noreferrer"
              target="_blank"
            >
              Open my Discord ↗
            </a>
            <a
              className="button"
              href={PORT_ISSUES}
              rel="noreferrer"
              target="_blank"
            >
                Open port issue ↗
            </a>
          </div>
        </div>

        <p className="category-recap">
          For <strong>{projectLabel(report.project)}</strong> · Filing as{" "}
          <strong>{selectedCategory?.label}</strong>
        </p>
        </aside>
      ) : null}
    </div>
  );
}
