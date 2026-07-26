import type { Metadata } from "next";
import Link from "next/link";
import { WindowShell } from "./window-shell";

const DOWNLOAD_PATH =
  "/downloads/recordable-1.0.0-forge-1.8.9.jar";
const CHECKSUM =
  "83716ADB9B2F6E6FC6B8D301DA4AFDA6F9CA11638396EA58430BAE80B08D7E3E";

export const metadata: Metadata = {
  title: {
    absolute: "Recordable Community — Unofficial Forge 1.8.9 Port",
  },
  description:
    "An unofficial community Forge 1.8.9 port of Record-able, the Minecraft recording mod by Minewind's Jo Eusebe.",
};

export default function Home() {
  return (
    <WindowShell>
      <main id="main-content">
        <section className="hero" aria-labelledby="hero-title">
          <div className="hero-copy">
            <p className="issue-line">
              UNOFFICIAL / FORGE 1.8.9 COMMUNITY PORT
            </p>
            <div className="brand-lockup">
              <h1 id="hero-title">Recordable</h1>
              <span className="brand-tag">Community</span>
            </div>
            <p className="hero-tagline">
              Record Minecraft. Keep the good parts.
            </p>
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
            <p className="release-note">
              Unofficial community port · version 1.0.0
            </p>
          </div>

          <aside className="cover-note" aria-label="Release summary">
            <p className="cover-kicker">The BedWars edition</p>
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

        <section className="briefs" aria-label="Original mod and community port">
          <article>
            <p className="section-number">01</p>
            <h2>The original</h2>
            <p>
              Record-able is the MIT-licensed Minecraft recorder created by
              Minewind&apos;s Jo Eusebe. Its official releases live on
              Modrinth and the original source repository.
            </p>
          </article>
          <article>
            <p className="section-number">02</p>
            <h2>The Community port</h2>
            <p>
              Recordable Community independently adapts that experience to
              Minecraft 1.8.9 on Forge. It is maintained by ErDreiwen and is
              not an official release or endorsement from the original author.
            </p>
          </article>
        </section>

        <section className="link-desk" aria-labelledby="links-title">
          <div>
            <p className="eyebrow">Files &amp; links</p>
            <h2 id="links-title">Get Recordable Community</h2>
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
            <Link href="/faq">
              <span>03</span>
              FAQ
              <small>Original &amp; port</small>
            </Link>
            <Link href="/report">
              <span>04</span>
              Report Center
              <small>Prepare a useful report</small>
            </Link>
            <a
              href="https://github.com/JoEusebe/record-able"
              rel="noreferrer"
              target="_blank"
            >
              <span>05</span>
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
          Recordable Community is an unofficial port · Original Record-able by
          Minewind&apos;s Jo Eusebe · Port maintained by ErDreiwen · MIT
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
