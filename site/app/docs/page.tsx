import type { Metadata } from "next";
import Link from "next/link";
import { WindowShell } from "../window-shell";

export const metadata: Metadata = {
  title: "Docs",
  description:
    "Install and use Record-able for Forge 1.8.9, configure FFmpeg, and troubleshoot common recording issues.",
};

const DOWNLOAD_PATH =
  "/downloads/recordable-1.0.0-forge-1.8.9.jar";

export default function DocsPage() {
  return (
    <WindowShell>
      <main id="main-content" className="docs">
        <header className="docs-header">
          <div>
            <p className="issue-line">FIELD MANUAL / VERSION 1.0.0</p>
            <h1>Record-able docs</h1>
            <p>
              The short guide to installing, recording, and finding your clips.
            </p>
          </div>
          <a className="button button-primary" href={DOWNLOAD_PATH} download>
            Download .jar
          </a>
        </header>

        <nav className="docs-index" aria-label="Documentation sections">
          <a href="#install">Install</a>
          <a href="#controls">Controls</a>
          <a href="#files">Files</a>
          <a href="#ffmpeg">FFmpeg</a>
          <a href="#lunar">Lunar</a>
          <a href="#troubleshooting">Troubleshooting</a>
        </nav>

        <div className="docs-grid">
          <section id="install">
            <p className="section-number">01</p>
            <h2>Install</h2>
            <ol>
              <li>Create a Minecraft 1.8.9 instance.</li>
              <li>Install Forge 11.15.1.2318.</li>
              <li>
                Put the downloaded JAR in the instance&apos;s{" "}
                <code>mods</code> folder.
              </li>
              <li>Start Minecraft with Java 8.</li>
            </ol>
            <p className="callout">
              Keep only one Record-able JAR in the profile. A small
              clapperboard on the title screen confirms it loaded.
            </p>
          </section>

          <section id="controls">
            <p className="section-number">02</p>
            <h2>Default keys</h2>
            <dl className="key-list">
              <div>
                <dt>Start / stop</dt>
                <dd>
                  <kbd>-</kbd>
                </dd>
              </div>
              <div>
                <dt>Pause / resume</dt>
                <dd>
                  <kbd>=</kbd>
                </dd>
              </div>
              <div>
                <dt>Settings</dt>
                <dd>
                  <kbd>F9</kbd>
                </dd>
              </div>
              <div>
                <dt>Video collection</dt>
                <dd>
                  <kbd>F12</kbd>
                </dd>
              </div>
              <div>
                <dt>Mic push-to-talk</dt>
                <dd>
                  <kbd>V</kbd>
                </dd>
              </div>
            </dl>
            <p>
              Join a world, press the recording key, then press it again to
              save. Auto-record is on by default and can be disabled in
              settings. Replay, bookmark, and censor keys begin unbound.
            </p>
          </section>

          <section id="files">
            <p className="section-number">03</p>
            <h2>Files</h2>
            <dl className="path-list">
              <div>
                <dt>Recordings</dt>
                <dd>
                  <code>recordings</code>
                </dd>
              </div>
              <div>
                <dt>Automatic clips</dt>
                <dd>
                  <code>recordings\recording_auto_clips</code>
                </dd>
              </div>
              <div>
                <dt>Configuration</dt>
                <dd>
                  <code>config\recordable.json</code>
                </dd>
              </div>
              <div>
                <dt>Managed FFmpeg</dt>
                <dd>
                  <code>recordable\ffmpeg\bin</code>
                </dd>
              </div>
            </dl>
            <p>All paths are relative to the launcher&apos;s game folder.</p>
          </section>

          <section id="ffmpeg">
            <p className="section-number">04</p>
            <h2>FFmpeg</h2>
            <p>
              FFmpeg turns captured frames and audio into playable video. On
              first launch, choose <strong>Download FFmpeg</strong>, review the
              source and destination, then confirm the download. Nothing is
              fetched before that final click.
            </p>
            <p>
              Already have it? Open settings with <kbd>F9</kbd>, search for
              FFmpeg, paste the full path to <code>ffmpeg.exe</code>, and choose{" "}
              <strong>Test FFmpeg</strong>. Keep <code>ffprobe.exe</code> beside
              it when possible.
            </p>
          </section>

          <section id="lunar">
            <p className="section-number">05</p>
            <h2>Lunar Launcher</h2>
            <p>
              Use Lunar&apos;s <strong>Vanilla/Forge</strong> option with a
              Forge 1.8.9 profile. Put the mod in:
            </p>
            <pre>
              <code>%USERPROFILE%\.lunarclient\profiles\vanilla-1.8\mods</code>
            </pre>
            <p>
              Do not use the branded Lunar/Ichor runtime. It filters arbitrary
              external Forge JARs before Forge sees them; that cannot be fixed
              from inside this mod.
            </p>
          </section>

          <section id="troubleshooting">
            <p className="section-number">06</p>
            <h2>Troubleshooting</h2>
            <h3>The mod does not appear</h3>
            <p>
              Confirm Minecraft, Forge, and Java versions; remove duplicate
              JARs; and check <code>logs\latest.log</code> for{" "}
              <code>Record-able Forge 1.8.9 initialized.</code>
            </p>
            <h3>Recording will not start</h3>
            <p>
              Join a world, run <strong>Test FFmpeg</strong>, check free disk
              space, and review the chat error or latest log. The default free
              space floor is 500 MB.
            </p>
            <h3>Black video or missing audio</h3>
            <p>
              Run <strong>Capture Test</strong>; temporarily disable shaders
              and OptiFine Fast Render. For audio, enable Capture Audio and
              restart after changing audio devices.
            </p>
            <h3>Replay did not save</h3>
            <p>
              Enable Replay Buffer, bind Save Replay Buffer, and let the buffer
              warm up. Automatic clips also need Auto-Clipping and at least one
              trigger enabled.
            </p>
          </section>
        </div>

        <div className="docs-end">
          <Link className="button" href="/">
            ← Back home
          </Link>
          <a className="button button-primary" href={DOWNLOAD_PATH} download>
            Download Record-able
          </a>
        </div>
      </main>

      <footer className="site-footer">
        <p>Need more detail? Open an issue after the public fork launches.</p>
        <nav aria-label="Project links">
          <a
            href="https://github.com/JoEusebe/record-able"
            rel="noreferrer"
            target="_blank"
          >
            Original source
          </a>
          <a
            href="https://discord.gg/Qv32Natvb2"
            rel="noreferrer"
            target="_blank"
          >
            Discord
          </a>
        </nav>
      </footer>
    </WindowShell>
  );
}
