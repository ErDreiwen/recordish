"use client";

import { useMemo, useState } from "react";

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
  modVersion: "1.0.0",
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

function markdownFor(report: ReportState) {
  const category =
    CATEGORIES.find((item) => item.value === report.category)?.label ??
    "Other";
  const modifiers = [
    report.optifine ? "OptiFine installed" : "No OptiFine reported",
    report.shaders ? "Shaders enabled" : "Shaders disabled",
  ].join("; ");

  return `# ${reportTitle(report)}

> **Project:** ${projectLabel(report.project)}
> **Area:** ${category}

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
  const [actionStatus, setActionStatus] = useState(
    "Your draft stays in this tab. Nothing has been sent.",
  );

  const markdown = useMemo(() => markdownFor(report), [report]);
  const completenessItems = [
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
      modVersion: project === "community" ? "1.0.0" : "",
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

  function resetReport() {
    setReport(initialReport);
    setActionStatus("Draft binned. Nothing was submitted.");
  }

  return (
    <div className="report-builder">
      <section className="report-form" aria-labelledby="report-form-title">
        <div className="report-section-heading">
          <div>
            <p className="section-number">01 / DESCRIBE</p>
            <h2 id="report-form-title">What needs sorting?</h2>
          </div>
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
              Short summary <b aria-hidden="true">*</b>
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

          <label className="field field-wide">
            <span>
              Steps to reproduce <b aria-hidden="true">*</b>
              <small>One action per line works best</small>
            </span>
            <textarea
              onChange={(event) => setField("steps", event.target.value)}
              placeholder={"1. Open settings\n2. Choose Download FFmpeg\n3. ..."}
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
        </div>

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
                  report.project === "community" ? "1.0.0" : "From Modrinth"
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

        <div className="privacy-gate">
          <div className="privacy-warning" role="note">
            <strong>Privacy. Yes, it matters.</strong>
            <p>
              Logs can expose usernames, server addresses, chat, folder names,
              or access tokens. Strip that out. Never paste a password. We are
              not that desperate.
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
      </section>

      <aside className="report-output" aria-labelledby="preview-title">
        <div className="output-heading">
          <div>
            <p className="section-number">LIVE PREVIEW</p>
            <h2 id="preview-title">Your tidy little report</h2>
          </div>
          <span className="draft-badge">NOT SENT</span>
        </div>

        <p className="report-title-preview">{reportTitle(report)}</p>
        <textarea
          aria-label="Generated Markdown report"
          className="markdown-preview"
          readOnly
          rows={24}
          value={markdown}
        />

        <div className="output-actions">
          <button
            aria-describedby="share-help"
            className="button button-primary"
            disabled={!report.privacyChecked}
            onClick={copyReport}
            type="button"
          >
            Copy report
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
          Tick the privacy box first. We are not helping you leak your own
          stuff.
        </p>
        <p className="action-status" aria-live="polite">
          {actionStatus}
        </p>

        <div className="handoff-card">
          <p className="eyebrow">Right, where now?</p>
          <h3>Take it to Discord for now.</h3>
          {report.project === "community" ? (
            <p>
              The record-ish public issue tracker is not live yet.
              Copy or save the report, then take it to the official Record-able
              Discord. It will not send itself, and this page uploads nothing.
            </p>
          ) : (
            <p>
              For the original Fabric mod, use the official Record-able Discord
              or Modrinth page. The upstream GitHub currently restricts new
              issue creation. This page uploads nothing.
            </p>
          )}
          <div className="handoff-links">
            <a
              className="button"
              href="https://discord.gg/Qv32Natvb2"
              rel="noreferrer"
              target="_blank"
            >
              Open Discord ↗
            </a>
            {report.project === "original" ? (
              <a
                className="button"
                href="https://modrinth.com/mod/record-able"
                rel="noreferrer"
                target="_blank"
              >
                Official Modrinth ↗
              </a>
            ) : null}
          </div>
        </div>

        <p className="category-recap">
          For <strong>{projectLabel(report.project)}</strong> · Filing as{" "}
          <strong>{selectedCategory?.label}</strong>
        </p>
      </aside>
    </div>
  );
}
