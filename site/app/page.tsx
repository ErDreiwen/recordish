import type { Metadata } from "next";
import Link from "next/link";
import {
  BAGUETTE_MAP,
  COMMUNITY_DISCORD,
  PORT_REPOSITORY,
} from "./links";
import { WindowShell } from "./window-shell";

export const metadata: Metadata = {
  title: {
    absolute: "record-ish — A Record-able Port for Forge 1.8.9",
  },
  description:
    "record-ish is the unofficial Forge 1.8.9 port of Record-able, originally by Minewind's Jo Eusebe. No corporate waffle—just recording, clips, and a proper manual.",
};

export default function Home() {
  return (
    <WindowShell>
      <main id="main-content">
        <section className="hero" aria-labelledby="hero-title">
          <div className="hero-copy">
            <p className="issue-line">
              UNOFFICIAL / COMMUNITY PORT / FORGE 1.8.9
            </p>
            <div className="brand-lockup">
              <div className="brand-stack">
                <h1
                  aria-label="record-ish"
                  className="brand-logo"
                  id="hero-title"
                >
                  <span aria-hidden="true" className="brand-record">
                    record
                  </span>
                  <span aria-hidden="true" className="brand-dash">
                    -
                  </span>
                  <span aria-hidden="true" className="brand-ish">
                    ish
                  </span>
                </h1>
                <p className="brand-credit">a Record-able port</p>
                <span aria-hidden="true" className="brand-rec">
                  <i /> REC
                </span>
              </div>
            </div>
            <p className="hero-tagline">
              Record Minecraft. Keep the good bits.
            </p>
            <p className="standfirst">
              Video, game audio, instant replays, and automatic clips. All
              in-game. No bloated nonsense.
            </p>

            <div className="hero-actions" aria-label="Primary actions">
              <Link className="button button-primary" href="/download">
                Get the JAR
              </Link>
              <Link className="button" href="/docs">
                Read this first
              </Link>
            </div>
            <p className="release-note">
              Unofficial. Community-built. Not pretending otherwise. · v1.0.0
            </p>
          </div>

          <aside className="cover-note" aria-label="Release summary">
            <p className="cover-kicker">The BedWars build</p>
            <p className="cover-number">1.8.9</p>
            <p>
              For the 1.8.9 diehards who are not binning their whole setup for
              a recorder.
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

        <section className="briefs" aria-label="Original mod and record-ish port">
          <article>
            <p className="section-number">01</p>
            <h2>The original</h2>
            <p>
              Minewind&apos;s Jo Eusebe built the original Record-able and
              released it under MIT. Credit goes there. Official builds stay
              on Modrinth and the original repository. Dead simple.
            </p>
          </article>
          <article>
            <p className="section-number">02</p>
            <h2>The record-ish port</h2>
            <p>
              We dragged that desktop experience back to Forge 1.8.9 because
              BedWars players still live here. Maintained by ErDreiwen;
              unofficial as it gets and never claiming otherwise.
            </p>
          </article>
        </section>

        <section className="link-desk" aria-labelledby="links-title">
          <div>
            <p className="eyebrow">The useful bits</p>
            <h2 id="links-title">Right. Get on with it.</h2>
          </div>
          <nav className="file-links" aria-label="Project files">
            <Link href="/download">
              <span>01</span>
              Download
              <small>One JAR. Try not to lose it.</small>
            </Link>
            <Link href="/docs">
              <span>02</span>
              Docs
              <small>Read before shouting</small>
            </Link>
            <Link href="/faq">
              <span>03</span>
              FAQ
              <small>Questions, obviously</small>
            </Link>
            <Link href="/report">
              <span>04</span>
              Something knackered?
              <small>Build a useful report</small>
            </Link>
            <a
              href="https://github.com/JoEusebe/record-able"
              rel="noreferrer"
              target="_blank"
            >
              <span>05</span>
              Original source
              <small>Credit where it is due ↗</small>
            </a>
          </nav>
          <p className="fork-status">
            The Forge 1.8.9 fork is live on{" "}
            <a
              href={PORT_REPOSITORY}
              rel="noreferrer"
              target="_blank"
            >
              GitHub
            </a>
            . Original credit remains with Jo Eusebe.
          </p>
        </section>

        <aside className="lunar-note" aria-labelledby="lunar-title">
          <div className="info-mark" aria-hidden="true">
            i
          </div>
          <div>
            <h2 id="lunar-title">Lunar being awkward?</h2>
            <p>
              Use <strong>Vanilla/Forge</strong>. Branded Lunar/Ichor bins
              external Forge JARs before they load. That is the launcher being
              clever, not this mod.
            </p>
          </div>
        </aside>

      </main>

      <footer className="site-footer">
        <p>
          record-ish is unofficial · Original Record-able by Minewind&apos;s Jo
          Eusebe · Port maintained by ErDreiwen · MIT licensed
        </p>
        <nav aria-label="Social links">
          <a
            href={PORT_REPOSITORY}
            rel="noreferrer"
            target="_blank"
          >
            GitHub
          </a>
          <a
            href={BAGUETTE_MAP}
            rel="noreferrer"
            target="_blank"
          >
            Baguette?
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
