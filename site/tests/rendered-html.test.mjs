import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import test from "node:test";

const releaseManifest = JSON.parse(
  await readFile(
    new URL("../release-manifest.json", import.meta.url),
    "utf8",
  ),
);
const expectedChecksum = releaseManifest.sha256.toUpperCase();
const nightlyDownloadUrl =
  "https://github.com/ErDreiwen/recordish/releases/download/nightly/recordish-nightly-forge-1.8.9.jar";

function formattedFileSize(bytes) {
  const mebibyte = 1024 * 1024;
  return bytes >= mebibyte
    ? (bytes / mebibyte).toFixed(2) + " MiB"
    : (bytes / 1024).toFixed(1) + " KiB";
}

async function render(path = "/") {
  const route = path === "/" ? "" : path.replace(/^\/|\/$/g, "");
  const html = await readFile(
    new URL(`../out/${route ? `${route}/` : ""}index.html`, import.meta.url),
    "utf8",
  );
  return new Response(html, {
    status: 200,
    headers: { "content-type": "text/html; charset=utf-8" },
  });
}

test("server-renders the unofficial record-ish homepage", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(
    html,
    /<title>record-ish .* A Record-able Port for Forge 1\.8\.9<\/title>/i,
  );
  assert.match(html, /UNOFFICIAL \/ COMMUNITY PORT \/ FORGE 1\.8\.9/);
  assert.match(html, /<h1[^>]*aria-label="record-ish"[^>]*>/i);
  assert.match(html, /aria-hidden="true"[^>]*class="brand-art"/i);
  assert.match(html, /class="brand-rec"/i);
  assert.doesNotMatch(
    html,
    /class="(?:brand-record|brand-dash|brand-ish)"/i,
  );
  assert.match(
    html,
    /class="brand-credit"[^>]*>a Record-able port<\/p>/i,
  );
  assert.match(html, /Record Minecraft\. Keep the good bits\./);
  assert.match(html, /Minecraft[\s\S]*1\.8\.9/);
  assert.match(html, /Forge[\s\S]*11\.15\.1\.2318/);
  assert.match(html, /Java[\s\S]*8/);
  assert.match(html, /href="\/download\/"/);
  assert.ok(
    !html.includes(`href="${releaseManifest.downloadUrl}"`),
  );
  assert.match(html, /href="\/docs\/"/);
  assert.match(html, /href="\/faq\/"/);
  assert.match(html, /href="\/report\/"/);
  assert.match(html, /Original source/);
  assert.match(html, /Original Record-able by/);
  assert.match(html, /unofficial as it gets and never claiming otherwise/i);
  assert.match(html, /Vanilla\/Forge/);
  assert.doesNotMatch(html, new RegExp(expectedChecksum));
  assert.match(html, /https:\/\/github\.com\/ErDreiwen\/recordish/);
  assert.match(html, /Baguette\?/);
  assert.match(
    html,
    /https:\/\/www\.google\.com\/maps\/search\/\?api=1(?:&|&amp;)query=Pollen\+Bakery\+Kampus\+42\+Aytoun\+Street\+Manchester\+M1\+3GL/,
  );
  assert.match(html, /https:\/\/discord\.gg\/YRJrvgverM/);
  assert.match(html, /href="\/docs\/?#credits"/);
  assert.doesNotMatch(html, /https:\/\/www\.vecteezy\.com\/vector-art\//);
  await access(
    new URL("../public/brand/recordish-wordmark.png", import.meta.url),
  );
  assert.match(
    html,
    /property="og:image" content="https:\/\/recordish\.kmsi\.me\/og-record-ish\.png"/,
  );
  assert.doesNotMatch(html, /codex-preview|react-loading-skeleton/i);
});

test("server-renders the local report desk", async () => {
  const response = await render("/report");
  assert.equal(response.status, 200);

  const html = await response.text();
  assert.match(html, /<title>Report Center/i);
  assert.match(html, /SOMETHING KNACKERED\? \/ GIVE US THE USEFUL BITS/);
  assert.match(html, /Tell us what broke\. Properly\./);
  assert.match(html, /record-ish/);
  assert.match(html, /Original Record-able/);
  assert.match(html, /Quick report/);
  assert.match(html, /Full nerd report/);
  assert.match(html, /No logs, no jargon\. About a minute\./);
  assert.match(html, /UI \/ usability/);
  assert.match(html, /Recording \/ output/);
  assert.match(html, /FFmpeg \/ install/);
  assert.match(html, /Launcher compatibility/);
  assert.match(html, /Feature request/);
  assert.match(html, /How often/);
  assert.match(html, /What went wrong\?/);
  assert.match(html, /What were you doing just before\?/);
  assert.match(html, /Generated Markdown report/);
  assert.match(html, /Open report on GitHub/);
  assert.match(html, /Copy instead/);
  assert.match(html, /Save \.md/);
  assert.match(
    html,
    /https:\/\/github\.com\/ErDreiwen\/recordish\/issues\/new/,
  );
  assert.match(html, /Nothing gets uploaded here/);
  assert.match(html, /removed private information/);
  assert.match(html, /Tick the privacy box first/);
  assert.match(html, /https:\/\/discord\.gg\/YRJrvgverM/);
});

test("server-renders one verified, pissy download page", async () => {
  const response = await render("/download");
  assert.equal(response.status, 200);

  const html = await response.text();
  assert.match(html, /<title>Download .* record-ish/i);
  assert.match(html, /ONE FILE \/ NO INSTALLER \/ NO BOLLOCKS/);
  assert.match(html, /Download the bloody thing\./);
  assert.ok(
    html.includes(`href="${releaseManifest.downloadUrl}"`),
  );
  if (releaseManifest.downloadUrl.startsWith("/")) {
    assert.ok(
      html.includes(`download="${releaseManifest.fileName}"`),
    );
  } else {
    assert.ok(
      !html.includes(`download="${releaseManifest.fileName}"`),
    );
  }
  assert.match(html, new RegExp(releaseManifest.fileName.replaceAll(".", "\\.")));
  assert.ok(html.includes(formattedFileSize(releaseManifest.fileSizeBytes)));
  assert.ok(
    html.includes("v" + releaseManifest.version) ||
      html.includes("v<!-- -->" + releaseManifest.version),
  );
  assert.match(html, /Minecraft[\s\S]*1\.8\.9/);
  assert.match(html, /Forge[\s\S]*11\.15\.1\.2318/);
  assert.match(html, /Java[\s\S]*8/);
  assert.match(html, /File fingerprint \(SHA-256\)/);
  assert.match(html, /SHA-256 is the JAR(?:&#x27;|')s file fingerprint/);
  assert.match(html, /class="release-fingerprint-row"/);
  assert.match(html, /SHA-256 is just the name of the fingerprint check/);
  assert.match(html, new RegExp(expectedChecksum));
  assert.ok(
    html.includes(`href="${releaseManifest.checksumUrl}"`),
  );
  assert.match(html, /LATEST MAIN BUILD \/ HERE BE DRAGONS/);
  assert.match(html, /Download risky nightly/);
  assert.ok(html.includes(`href="${nightlyDownloadUrl}"`));
  assert.match(html, /Do not double-click it\./);
  assert.match(html, /Vanilla\/Forge/);
  assert.match(html, /Branded Lunar\/Ichor/);
  assert.match(html, /FFmpeg is not bundled/);
  assert.match(html, /href="\/docs\/#lunar"/);
  assert.match(html, /https:\/\/modrinth\.com\/mod\/record-able/);
  assert.match(
    html,
    /https:\/\/github\.com\/ErDreiwen\/recordish/,
  );
  assert.match(html, /MIT licensed/);
});

test("keeps the technical report and sends original-mod complaints upstream", async () => {
  const [builder, css, links] = await Promise.all([
    readFile(
      new URL("../app/report/report-builder.tsx", import.meta.url),
      "utf8",
    ),
    readFile(new URL("../app/globals.css", import.meta.url), "utf8"),
    readFile(new URL("../app/links.ts", import.meta.url), "utf8"),
  ]);

  assert.match(builder, /reportMode === "technical"/);
  assert.match(builder, /Steps to reproduce/);
  assert.match(builder, /Other mods, shaders, or resource packs/);
  assert.match(builder, /Relevant log excerpt/);
  assert.match(builder, /Original mod\? Fuck off upstream, mate\./);
  assert.match(builder, /absolutely not my problem/);
  assert.match(builder, /COMMUNITY_DISCORD/);
  assert.match(builder, /PORT_ISSUES/);
  assert.match(links, /https:\/\/discord\.gg\/YRJrvgverM/);
  assert.match(links, /https:\/\/github\.com\/ErDreiwen\/recordish/);
  assert.match(builder, /https:\/\/modrinth\.com\/mod\/record-able/);
  assert.match(builder, /report-builder-upstream/);
  assert.match(css, /\.report-builder-upstream/);
  assert.match(css, /\.upstream-shove/);
});

test("server-renders FAQs for the original mod and record-ish port", async () => {
  const response = await render("/faq");
  assert.equal(response.status, 200);

  const html = await response.text();
  assert.match(html, /<title>FAQ .* record-ish/i);
  assert.match(html, /Questions people keep asking/);
  assert.match(html, /Original Record-able/);
  assert.match(html, /record-ish/);
  assert.match(html, /Is this the official Record-able website/);
  assert.match(html, /unofficial, community-maintained Forge 1\.8\.9 port/i);
  assert.match(html, /Does it work in Prism and Lunar Launcher/);
  assert.match(html, /How do I report a port, UI, or website issue/);
  assert.match(html, /uploads nothing/i);
  assert.match(html, /href="\/report\/"/);
});

test("server-renders concise installation and usage docs", async () => {
  const response = await render("/docs");
  assert.equal(response.status, 200);

  const html = await response.text();
  assert.match(
    html,
    /<title>Docs .* record-ish .* Record-able Port for Forge 1\.8\.9<\/title>/i,
  );
  assert.match(html, /Docs\. Read these before kicking off\./);
  assert.match(html, /Forge 11\.15\.1\.2318/);
  assert.match(html, /Keys that do stuff/);
  assert.match(html, /recordings\\recording_auto_clips/);
  assert.match(html, /Download FFmpeg/);
  assert.match(html, /branded Lunar\/Ichor/);
  assert.match(html, /When it has gone a bit wrong/);
  assert.match(html, /id="credits"/);
  assert.match(html, /Patrick Santos/);
  assert.match(
    html,
    /https:\/\/www\.vecteezy\.com\/vector-art\/940848-orange-rounded-cartoon-text-effect/,
  );
  assert.match(
    html,
    /class="troubleshooting-question-mark"[^>]*>\?<\/span>/,
  );
  assert.match(html, /href="\/download\/"/);
  assert.ok(
    !html.includes(`href="${releaseManifest.downloadUrl}"`),
  );
  assert.ok(html.includes(releaseManifest.version));
});

test("validates release metadata and any bundled stable JAR", async () => {
  assert.equal(releaseManifest.schemaVersion, 1);
  assert.equal(releaseManifest.channel, "stable");
  assert.match(
    releaseManifest.version,
    /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/,
  );
  assert.match(expectedChecksum, /^[0-9A-F]{64}$/);
  assert.match(releaseManifest.sourceCommit, /^[0-9a-f]{40}$/i);
  assert.ok(Number.isSafeInteger(releaseManifest.fileSizeBytes));
  assert.ok(releaseManifest.fileSizeBytes > 0);
  assert.ok(!Number.isNaN(Date.parse(releaseManifest.publishedAt)));
  assert.ok(releaseManifest.fileName.endsWith(".jar"));
  assert.ok(releaseManifest.fileName.startsWith("recordish-"));
  assert.ok(
    releaseManifest.downloadUrl.endsWith("/" + releaseManifest.fileName),
  );
  assert.ok(
    releaseManifest.checksumUrl.endsWith(
      "/" + releaseManifest.fileName + ".sha256",
    ),
  );
  assert.doesNotMatch(releaseManifest.downloadUrl, /\/releases\/latest\//);
  assert.doesNotMatch(releaseManifest.checksumUrl, /\/releases\/latest\//);

  if (releaseManifest.downloadUrl.startsWith("/")) {
    assert.ok(releaseManifest.checksumUrl.startsWith("/"));
    const jar = new URL(
      "../public" + releaseManifest.downloadUrl,
      import.meta.url,
    );
    const bytes = await readFile(jar);
    const checksum = createHash("sha256")
      .update(bytes)
      .digest("hex")
      .toUpperCase();
    const checksumFile = await readFile(
      new URL("../public" + releaseManifest.checksumUrl, import.meta.url),
      "utf8",
    );

    assert.equal(checksum, expectedChecksum);
    assert.equal(bytes.byteLength, releaseManifest.fileSizeBytes);
    assert.equal(
      checksumFile.trim(),
      expectedChecksum + "  " + releaseManifest.fileName,
    );
  } else {
    for (const value of [
      releaseManifest.downloadUrl,
      releaseManifest.checksumUrl,
    ]) {
      const url = new URL(value);
      assert.equal(url.protocol, "https:");
    }
  }

  await assert.rejects(access(new URL("../app/_sites-preview", import.meta.url)));

  const [page, docs, reportBuilder, windowShell, layout, packageJson] =
    await Promise.all([
      readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
      readFile(new URL("../app/docs/page.tsx", import.meta.url), "utf8"),
      readFile(
        new URL("../app/report/report-builder.tsx", import.meta.url),
        "utf8",
      ),
      readFile(new URL("../app/window-shell.tsx", import.meta.url), "utf8"),
      readFile(new URL("../app/layout.tsx", import.meta.url), "utf8"),
      readFile(new URL("../package.json", import.meta.url), "utf8"),
    ]);

  assert.doesNotMatch(page, /SkeletonPreview|codex-preview/);
  assert.doesNotMatch(layout, /Starter Project|codex-preview/);
  assert.doesNotMatch(packageJson, /react-loading-skeleton/);
  assert.match(packageJson, /"name": "recordish-site"/);
  for (const currentSource of [page, docs, windowShell]) {
    assert.doesNotMatch(currentSource, /\/downloads\/.*\.jar/);
    assert.match(currentSource, /\/download/);
  }
  for (const currentSource of [page, docs, reportBuilder]) {
    assert.doesNotMatch(currentSource, /\b1\.0\.0\b/);
    assert.match(currentSource, /RELEASE\.version/);
  }
});

test("insets the briefs divider below the three-column requirements bar", async () => {
  const css = await readFile(
    new URL("../app/globals.css", import.meta.url),
    "utf8",
  );

  assert.match(
    css,
    /\.briefs article \+ article::before\s*\{[^}]*top:\s*clamp\(2rem,\s*5vw,\s*4rem\);[^}]*bottom:\s*clamp\(2rem,\s*5vw,\s*4rem\);/s,
  );
  assert.match(
    css,
    /@media \(max-width:\s*760px\)[\s\S]*?\.briefs article \+ article::before\s*\{[^}]*display:\s*none;/,
  );
});
