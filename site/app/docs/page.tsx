import type { Metadata } from "next";
import Link from "next/link";
import { COMMUNITY_DISCORD } from "../links";
import { WindowShell } from "../window-shell";

export const metadata: Metadata = {
  title: "Docs",
  description:
    "Install record-ish, get FFmpeg behaving, and fix the usual Forge 1.8.9 nonsense without the corporate waffle.",
};

export default function DocsPage() {
  return (
    <WindowShell>
      <main id="main-content" className="docs">
        <header className="docs-header">
          <div>
            <p className="issue-line">
              RIGHT THEN / FIELD MANUAL / RECORD-ISH 1.0.0
            </p>
            <h1>Docs. Read these before kicking off.</h1>
            <p>
              Installation, recording, files, and fixes. Straight answers. No
              ten-minute intro.
            </p>
          </div>
          <Link className="button button-primary" href="/download">
            Get the JAR
          </Link>
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
            <h2>Install it properly</h2>
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
              One record-ish JAR. Not two. If the clapperboard turns up on the
              title screen, you are sorted.
            </p>
          </section>

          <section id="controls">
            <p className="section-number">02</p>
            <h2>Keys that do stuff</h2>
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
              Join a world, press record, then press it again to save. Auto
              Record starts enabled; switch it off in settings if that does
              your head in. Replay, bookmark, and censor keys begin unbound.
            </p>
          </section>

          <section id="files">
            <p className="section-number">03</p>
            <h2>Where your files went</h2>
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
            <p>
              All paths are inside the launcher&apos;s game folder. Check the
              right instance before declaring them vanished.
            </p>
          </section>

          <section id="ffmpeg">
            <p className="section-number">04</p>
            <h2>FFmpeg does the work</h2>
            <p>
              FFmpeg turns captured frames and audio into playable video. On
              first launch, choose <strong>Download FFmpeg</strong>, review the
              source and destination, then confirm the download. Nothing is
              fetched before that final click. We ask first. We are not animals.
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
            <h2>Lunar being Lunar</h2>
            <p>
              Use Lunar&apos;s <strong>Vanilla/Forge</strong> option with a
              Forge 1.8.9 profile. Put the mod in:
            </p>
            <pre>
              <code>%USERPROFILE%\.lunarclient\profiles\vanilla-1.8\mods</code>
            </pre>
            <p>
              Do not use branded Lunar/Ichor. It bins arbitrary external Forge
              JARs before Forge sees them. The mod cannot fix a launcher that
              never lets it through the door.
            </p>
          </section>

          <section id="troubleshooting">
            <p className="section-number">06</p>
            <h2>When it has gone a bit wrong</h2>
            <h3>The mod has gone missing</h3>
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
            <h3>Black video or silent audio</h3>
            <p>
              Run <strong>Capture Test</strong>; temporarily disable shaders
              and OptiFine Fast Render. For audio, enable Capture Audio and
              restart after changing audio devices.
            </p>
            <h3>The replay did not save</h3>
            <p>
              Enable Replay Buffer, bind Save Replay Buffer, and let the buffer
              warm up. Automatic clips also need Auto-Clipping and at least one
              trigger enabled.
            </p>
          </section>
        </div>

        <div className="docs-end">
          <Link className="button" href="/">
            Back home
          </Link>
          <Link className="button button-primary" href="/download">
            Get record-ish
          </Link>
        </div>
      </main>

      <footer className="site-footer">
        <p>
          Unofficial Forge 1.8.9 port · Original Record-able by
          Minewind&apos;s Jo Eusebe · Credit where it is due.
        </p>
        <nav aria-label="Project links">
          <a
            href="https://github.com/JoEusebe/record-able"
            rel="noreferrer"
            target="_blank"
          >
            Original source
          </a>
          <a
            href={COMMUNITY_DISCORD}
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
