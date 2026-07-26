import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import test from "node:test";

const expectedChecksum =
  "83716ADB9B2F6E6FC6B8D301DA4AFDA6F9CA11638396EA58430BAE80B08D7E3E";

async function render(path = "/") {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set(
    "test",
    `${process.pid}-${Date.now()}-${path.replace(/\W/g, "")}`,
  );
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request(`http://recordable.test${path}`, {
      headers: { accept: "text/html" },
    }),
    {
      ASSETS: {
        fetch: async () => new Response("Not found", { status: 404 }),
      },
    },
    {
      waitUntil() {},
      passThroughOnException() {},
    },
  );
}

test("server-renders the Manc-attitude Recordable Community homepage", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(
    html,
    /<title>Recordable Community .* Unofficial Forge 1\.8\.9 Port<\/title>/i,
  );
  assert.match(html, /MANC ATTITUDE \/ UNOFFICIAL \/ FORGE 1\.8\.9/);
  assert.match(html, /<h1[^>]*>Recordable<\/h1>/);
  assert.match(html, />Community<\/span>/);
  assert.match(html, /Record Minecraft\. Keep the good bits\./);
  assert.match(html, /MCR ATTITUDE \/ ZERO CORPORATE WAFFLE/);
  assert.match(html, /Minecraft[\s\S]*1\.8\.9/);
  assert.match(html, /Forge[\s\S]*11\.15\.1\.2318/);
  assert.match(html, /Java[\s\S]*8/);
  assert.match(
    html,
    /href="\/downloads\/recordable-1\.0\.0-forge-1\.8\.9\.jar"/,
  );
  assert.match(html, /href="\/docs"/);
  assert.match(html, /href="\/faq"/);
  assert.match(html, /href="\/report"/);
  assert.match(html, /Original source/);
  assert.match(html, /Original Record-able by/);
  assert.match(html, /unofficial as it gets and never claiming otherwise/i);
  assert.match(html, /Vanilla\/Forge/);
  assert.match(html, new RegExp(expectedChecksum));
  assert.match(html, /https:\/\/github\.com\/ErDreiwen/);
  assert.match(html, /https:\/\/modrinth\.com\/mod\/record-able/);
  assert.match(html, /https:\/\/discord\.gg\/Qv32Natvb2/);
  assert.match(
    html,
    /property="og:image" content="https?:\/\/[^"]+\/og-manc\.png"/,
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
  assert.match(html, /Recordable Community/);
  assert.match(html, /Original Record-able/);
  assert.match(html, /UI \/ usability/);
  assert.match(html, /Recording \/ output/);
  assert.match(html, /FFmpeg \/ install/);
  assert.match(html, /Launcher compatibility/);
  assert.match(html, /Feature request/);
  assert.match(html, /How often/);
  assert.match(html, /Other mods, shaders, or resource packs/);
  assert.match(html, /Steps to reproduce/);
  assert.match(html, /Generated Markdown report/);
  assert.match(html, /Copy report/);
  assert.match(html, /Save \.md/);
  assert.match(html, /Nothing gets uploaded here/);
  assert.match(html, /removed private information/);
  assert.match(html, /Tick the privacy box first/);
  assert.match(html, /https:\/\/discord\.gg\/Qv32Natvb2/);
});

test("server-renders FAQs for the original mod and Community port", async () => {
  const response = await render("/faq");
  assert.equal(response.status, 200);

  const html = await response.text();
  assert.match(html, /<title>FAQ .* Recordable Community/i);
  assert.match(html, /Questions people keep asking/);
  assert.match(html, /Original Record-able/);
  assert.match(html, /Recordable Community/);
  assert.match(html, /Is this the official Record-able website/);
  assert.match(html, /unofficial, community-maintained Forge 1\.8\.9 port/i);
  assert.match(html, /Does it work in Prism and Lunar Launcher/);
  assert.match(html, /How do I report a port, UI, or website issue/);
  assert.match(html, /uploads nothing/i);
  assert.match(html, /href="\/report"/);
});

test("server-renders concise installation and usage docs", async () => {
  const response = await render("/docs");
  assert.equal(response.status, 200);

  const html = await response.text();
  assert.match(html, /<title>Docs .* Recordable Community .* Forge 1\.8\.9 Port<\/title>/i);
  assert.match(html, /Docs\. Read these before kicking off\./);
  assert.match(html, /Forge 11\.15\.1\.2318/);
  assert.match(html, /Keys that do stuff/);
  assert.match(html, /recordings\\recording_auto_clips/);
  assert.match(html, /Download FFmpeg/);
  assert.match(html, /branded Lunar\/Ichor/);
  assert.match(html, /When it has gone a bit wrong/);
});

test("ships the verified Forge JAR and no starter preview", async () => {
  const jar = new URL(
    "../public/downloads/recordable-1.0.0-forge-1.8.9.jar",
    import.meta.url,
  );
  const bytes = await readFile(jar);
  const checksum = createHash("sha256").update(bytes).digest("hex").toUpperCase();

  assert.equal(checksum, expectedChecksum);
  await assert.rejects(access(new URL("../app/_sites-preview", import.meta.url)));

  const [page, layout, packageJson] = await Promise.all([
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/layout.tsx", import.meta.url), "utf8"),
    readFile(new URL("../package.json", import.meta.url), "utf8"),
  ]);

  assert.doesNotMatch(page, /SkeletonPreview|codex-preview/);
  assert.doesNotMatch(layout, /Starter Project|codex-preview/);
  assert.doesNotMatch(packageJson, /react-loading-skeleton/);
  assert.match(packageJson, /"name": "recordable-forge-site"/);
});
