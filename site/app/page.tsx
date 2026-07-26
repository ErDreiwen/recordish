import type { Metadata } from "next";
import Link from "next/link";
import { WindowShell } from "./window-shell";

const DOWNLOAD_PATH =
  "/downloads/recordable-1.0.0-forge-1.8.9.jar";
const CHECKSUM =
  "83716ADB9B2F6E6FC6B8D301DA4AFDA6F9CA11638396EA58430BAE80B08D7E3E";

export const metadata: Metadata = {
  title: {
    absolute: "Record-able for Forge 1.8.9",
  },
  description:
    "Record Minecraft, save replays, and keep automatic clips with the Forge 1.8.9 port of Record-able.",
};

export default function Home() {
  return (
    <WindowShell>
      <main id="main-content">
        <section className="hero" aria-labelledby="hero-title">
          <div className="hero-copy">
            <p className="issue-line">RECORD-ABLE / FORGE 1.8.9</p>
            <h1 id="hero-title">Record Minecraft. Keep the good parts.</h1>
            <p className="standfirst">
              Video, game audio, instant replays, and automatic clips—captured
              inside Minecraft.
            </p>

            <div className="hero-actions" aria-label="Primary actions">
              <a className="button button-primary" href={DOWNLOAD_PATH} download>
                Download .jar
              </a>
              <Link className="button" href="/docs">
                Read the docs
              </Link>
            </div>
            <p className="release-note">Forge port · version 1.0.0</p>
          </div>

          <aside className="cover-note" aria-label="Release summary">
            <p className="cover-kicker">The BedWars issue</p>
            <p className="cover-number">1.8.9</p>
            <p>
              A faithful desktop port for the version people still play.
            </p>
          </aside>
        </section>

        <section className="requirements" aria-label="System requirements">
          <span>
            <small>Minecraft</small>
            1.8.9
          </span>
          <span>
            <small>Forge</small>
            11.15.1.2318
          </span>
          <span>
            <small>Java</small>
            8
          </span>
        </section>

        <section className="briefs" aria-label="About Record-able">
          <article>
            <p className="section-number">01</p>
            <h2>The mod</h2>
            <p>
              Record-able writes standard video files with Minecraft audio,
              optional microphone audio, pause and resume, replay saving,
              overlays, and event-triggered clips.
            </p>
          </article>
          <article>
            <p className="section-number">02</p>
            <h2>The fork</h2>
            <p>
              This Forge 1.8.9 edition adapts the current desktop experience to
              the legacy client, including its recorder, collection, settings,
              FFmpeg setup, and BedWars-friendly kill clips.
            </p>
          </article>
        </section>

        <section className="link-desk" aria-labelledby="links-title">
          <div>
            <p className="eyebrow">Files &amp; links</p>
            <h2 id="links-title">Get the port</h2>
          </div>
          <nav className="file-links" aria-label="Project files">
            <a href={DOWNLOAD_PATH} download>
              <span>01</span>
              Download
              <small>Forge 1.8.9 JAR</small>
            </a>
            <Link href="/docs">
              <span>02</span>
              Docs
              <small>Install &amp; use</small>
            </Link>
            <a
              href="https://github.com/JoEusebe/record-able"
              rel="noreferrer"
              target="_blank"
            >
              <span>03</span>
              Original source
              <small>GitHub ↗</small>
            </a>
          </nav>
          <p className="fork-status">
            The ErDreiwen fork is being prepared and is not public yet.
          </p>
        </section>

        <aside className="lunar-note" aria-labelledby="lunar-title">
          <div className="info-mark" aria-hidden="true">
            i
          </div>
          <div>
            <h2 id="lunar-title">Using Lunar?</h2>
            <p>
              Choose Lunar&apos;s <strong>Vanilla/Forge</strong> profile. The
              branded Lunar/Ichor runtime filters external Forge mods before
              they load.
            </p>
          </div>
        </aside>

        <section className="checksum" aria-labelledby="checksum-title">
          <span id="checksum-title">SHA-256</span>
          <code>{CHECKSUM}</code>
        </section>
      </main>

      <footer className="site-footer">
        <p>
          Fork by ErDreiwen · Original mod by Minewind&apos;s Jo Eusebe · MIT
          licensed
        </p>
        <nav aria-label="Social links">
          <a
            href="https://github.com/ErDreiwen"
            rel="noreferrer"
            target="_blank"
          >
            GitHub
          </a>
          <a
            href="https://modrinth.com/mod/record-able"
            rel="noreferrer"
            target="_blank"
          >
            Modrinth
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
