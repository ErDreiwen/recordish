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

test("server-renders the Record-able homepage", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<title>Record-able for Forge 1\.8\.9<\/title>/i);
  assert.match(html, /RECORD-ABLE \/ FORGE 1\.8\.9/);
  assert.match(html, /Record Minecraft\. Keep the good parts\./);
  assert.match(html, /Minecraft[\s\S]*1\.8\.9/);
  assert.match(html, /Forge[\s\S]*11\.15\.1\.2318/);
  assert.match(html, /Java[\s\S]*8/);
  assert.match(
    html,
    /href="\/downloads\/recordable-1\.0\.0-forge-1\.8\.9\.jar"/,
  );
  assert.match(html, /href="\/docs"/);
  assert.match(html, /Original source/);
  assert.match(html, /Vanilla\/Forge/);
  assert.match(html, new RegExp(expectedChecksum));
  assert.match(html, /https:\/\/github\.com\/ErDreiwen/);
  assert.match(html, /https:\/\/modrinth\.com\/mod\/record-able/);
  assert.match(html, /https:\/\/discord\.gg\/Qv32Natvb2/);
  assert.match(
    html,
    /property="og:image" content="https?:\/\/[^"]+\/og\.png"/,
  );
  assert.doesNotMatch(html, /codex-preview|react-loading-skeleton/i);
});

test("server-renders concise installation and usage docs", async () => {
  const response = await render("/docs");
  assert.equal(response.status, 200);

  const html = await response.text();
  assert.match(
    html,
    /<title>Docs · Record-able for Forge 1\.8\.9<\/title>/i,
  );
  assert.match(html, /Record-able docs/);
  assert.match(html, /Forge 11\.15\.1\.2318/);
  assert.match(html, /Default keys/);
  assert.match(html, /recordings\\recording_auto_clips/);
  assert.match(html, /Download FFmpeg/);
  assert.match(html, /Lunar\/Ichor runtime/);
  assert.match(html, /Troubleshooting/);
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
