import Link from "next/link";
import {
  NIGHTLY_DOWNLOAD_URL,
  RELEASE,
  STABLE_CHECKSUM_URL,
  STABLE_DOWNLOAD_URL,
} from "../release";
import { createPageMetadata } from "../site-config";
import { SiteFooter } from "../site-footer";
import { WindowShell } from "../window-shell";

export const metadata = createPageMetadata({
  title: "Download",
  description:
    "Download the verified Recordish Forge 1.8.9 JAR and install the unofficial Record-able recording mod port.",
  path: "/download/",
});

export default function DownloadPage() {
  const stableDownloadIsLocal = STABLE_DOWNLOAD_URL.startsWith("/");

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
            download={stableDownloadIsLocal ? RELEASE.fileName : undefined}
            href={STABLE_DOWNLOAD_URL}
          >
            <span>Download the JAR</span>
            <small>{RELEASE.fileName}</small>
          </a>
          <p className="download-browser-note">
            Browser whinging about a JAR? Fair enough. Check the filename and
            file fingerprint below. Do not rename it to <code>.exe</code>, you
            weapon.
          </p>

          <aside className="nightly-download" aria-label="Nightly build">
            <div>
              <strong>LATEST MAIN BUILD / HERE BE DRAGONS</strong>
              <span>
                Fresh code, fewer guarantees. It may break, sulk, or ruin the
                clip you actually wanted. Stable above is the sensible one.
              </span>
            </div>
            <a className="nightly-download-link" href={NIGHTLY_DOWNLOAD_URL}>
              <span>Download risky nightly</span>
              <small>Built from main · absolutely no promises</small>
            </a>
          </aside>
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
            <p>
              SHA-256 is the JAR&apos;s file fingerprint. If it matches, you
              downloaded the file we shipped rather than a damaged or swapped
              copy.
            </p>
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
              <div className="release-fingerprint-row">
                <dt>File fingerprint (SHA-256)</dt>
                <dd>
                  <code>{RELEASE.sha256}</code>
                  <small className="release-fingerprint-note">
                    SHA-256 is just the name of the fingerprint check. Ignore
                    it unless you want to verify the download.
                  </small>
                </dd>
              </div>
            </dl>
            <details className="hash-help">
              <summary>How do I check the fingerprint?</summary>
              <p>
                Open PowerShell in your Downloads folder and run this. The
                result should match the fingerprint above exactly.
              </p>
              <pre>
                <code>
                  Get-FileHash .\{RELEASE.fileName} -Algorithm SHA256
                </code>
              </pre>
              <a
                download={
                  stableDownloadIsLocal
                    ? `${RELEASE.fileName}.sha256`
                    : undefined
                }
                href={STABLE_CHECKSUM_URL}
              >
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

      <SiteFooter />
    </WindowShell>
  );
}
