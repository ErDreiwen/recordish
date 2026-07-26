import type { Metadata } from "next";
import Link from "next/link";
import { CHECKSUM_PATH, DOWNLOAD_PATH, RELEASE } from "../release";
import { WindowShell } from "../window-shell";

export const metadata: Metadata = {
  title: "Download",
  description:
    "Download the verified record-ish Forge 1.8.9 JAR, check its requirements, and install it without making a meal of it.",
};

export default function DownloadPage() {
  return (
    <WindowShell>
      <main className="download-page" id="main-content">
        <header className="download-hero">
          <div>
            <p className="issue-line">
              ONE FILE / NO INSTALLER / NO BOLLOCKS
            </p>
            <h1>Download the bloody thing.</h1>
            <p>
              Forge 1.8.9. Java 8. One JAR. If those words do not describe your
              setup, stop clicking at random and read the docs.
            </p>
          </div>
          <aside className="release-ticket" aria-label="Current release">
            <span>CURRENT STABLE BUILD</span>
            <strong>v{RELEASE.version}</strong>
            <small>THE BEDWARS-ERA ONE</small>
          </aside>
        </header>

        <section className="download-action" aria-labelledby="download-title">
          <div>
            <p className="eyebrow">Right file. Right version.</p>
            <h2 id="download-title">record-ish {RELEASE.version}</h2>
            <p>
              Unofficial Record-able port · Forge {RELEASE.minecraft} ·{" "}
              {RELEASE.fileSize}
            </p>
          </div>
          <a
            aria-label={`Download record-ish ${RELEASE.version} for Forge ${RELEASE.minecraft}, ${RELEASE.fileSize} JAR`}
            className="download-main-button"
            download={RELEASE.fileName}
            href={DOWNLOAD_PATH}
          >
            <span>Download the JAR</span>
            <small>{RELEASE.fileName}</small>
          </a>
          <p className="download-browser-note">
            Browser whinging about a JAR? Fair enough. Check the filename and
            SHA-256 below. Do not rename it to <code>.exe</code>, you weapon.
          </p>
        </section>

        <section
          className="requirements download-requirements"
          aria-label="Required versions"
        >
          <span>
            <small>Minecraft</small>
            {RELEASE.minecraft}
          </span>
          <span>
            <small>Forge</small>
            {RELEASE.forge}
          </span>
          <span>
            <small>Java</small>
            {RELEASE.java}
          </span>
        </section>

        <div className="download-grid">
          <section className="download-panel" aria-labelledby="install-title">
            <p className="section-number">01 / INSTALL</p>
            <h2 id="install-title">Do this. In this order.</h2>
            <ol>
              <li>Make a Minecraft {RELEASE.minecraft} instance.</li>
              <li>Install Forge {RELEASE.forge}.</li>
              <li>
                Put <code>{RELEASE.fileName}</code> in that instance&apos;s{" "}
                <code>mods</code> folder.
              </li>
              <li>Launch it with Java {RELEASE.java}.</li>
            </ol>
            <p>
              The clapperboard button on the title screen means it loaded.
              Congratulations on moving one file.
            </p>
            <p>
              FFmpeg is not bundled. The mod offers to download it on first
              launch and asks before fetching anything, because consent is not
              difficult.
            </p>
          </section>

          <section className="download-panel" aria-labelledby="launcher-title">
            <p className="section-number">02 / LAUNCHERS</p>
            <h2 id="launcher-title">Lunar users: use the right bloody option.</h2>
            <p>
              Prism works. Normal Forge works. Lunar works through its{" "}
              <strong>Vanilla/Forge</strong> profile.
            </p>
            <p>
              Branded Lunar/Ichor bins arbitrary external Forge mods before the
              game starts. If you use that option, record-ish will not load.
              That launcher ignored the JAR; the JAR did not ignore you.
            </p>
            <Link className="text-link" href="/docs#lunar">
              Read the Lunar instructions →
            </Link>
          </section>

          <aside className="jar-warning" aria-labelledby="jar-warning-title">
            <span aria-hidden="true">!</span>
            <div>
              <h2 id="jar-warning-title">Do not double-click it.</h2>
              <p>
                This is a Minecraft mod, not a Windows installer. Put it in the{" "}
                <code>mods</code> folder and leave it there. Opening it achieves
                precisely bugger all.
              </p>
            </div>
          </aside>

          <section className="download-panel download-verify" aria-labelledby="verify-title">
            <p className="section-number">03 / VERIFY</p>
            <h2 id="verify-title">Paranoid? Good. Check it.</h2>
            <dl className="release-facts">
              <div>
                <dt>Filename</dt>
                <dd>
                  <code>{RELEASE.fileName}</code>
                </dd>
              </div>
              <div>
                <dt>Size</dt>
                <dd>{RELEASE.fileSize}</dd>
              </div>
              <div>
                <dt>SHA-256</dt>
                <dd>
                  <code>{RELEASE.sha256}</code>
                </dd>
              </div>
            </dl>
            <details className="hash-help">
              <summary>How do I check the hash?</summary>
              <p>
                Open PowerShell in your Downloads folder and run this. The
                result should match the SHA-256 above exactly.
              </p>
              <pre>
                <code>
                  Get-FileHash .\{RELEASE.fileName} -Algorithm SHA256
                </code>
              </pre>
              <a download href={CHECKSUM_PATH}>
                Download the .sha256 file
              </a>
            </details>
          </section>
        </div>

        <section className="wrong-shelf" aria-labelledby="wrong-shelf-title">
          <div>
            <p className="eyebrow">Wrong Minecraft version?</p>
            <h2 id="wrong-shelf-title">Then you are on the wrong shelf, pal.</h2>
            <p>
              This download is the unofficial Forge 1.8.9 port. For the
              original, modern Fabric mod, go to the official Record-able page.
              Credit stays where it belongs.
            </p>
          </div>
          <div className="wrong-shelf-actions">
            <a
              className="button"
              href="https://modrinth.com/mod/record-able"
              rel="noreferrer"
              target="_blank"
            >
              Original on Modrinth ↗
            </a>
            <Link className="button" href="/report">
              Something broken?
            </Link>
          </div>
        </section>
      </main>

      <footer className="site-footer">
        <p>
          record-ish is unofficial · Original Record-able by Minewind&apos;s Jo
          Eusebe · Port maintained by ErDreiwen · MIT licensed
        </p>
        <nav aria-label="Download page links">
          <Link href="/docs">Docs</Link>
          <a
            href="https://github.com/ErDreiwen/record-able/tree/forge-1.8.9"
            rel="noreferrer"
            target="_blank"
          >
            Source
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
