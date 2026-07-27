# record-ish website

The simple product site, Report Center, FAQ, and web documentation for the
unofficial record-ish Forge 1.8.9 port of Record-able.

## Local development

Requires Node.js 22.13 or newer.

```powershell
npm install
npm run dev
```

Build and test the site:

```powershell
npm run build
npm test
```

## Release data and downloads

The repository still contains the bundled `1.0.0` JAR and checksum as a safe
transition for the currently deployed site. Do not replace them manually.

The first successful automated stable release publishes its JAR, `.sha256`
file, and `release-manifest.json` from the same tested build as GitHub Release
assets. Only after those assets are published does the workflow remove
`site/public/downloads`, copy the exact manifest to
`site/release-manifest.json`, test the static site, and push the metadata update
to `main`.

Vercel's Git integration then deploys that commit. An optional
`VERCEL_DEPLOY_HOOK_URL` repository secret can trigger a second, explicit Vercel
build after the push when bot-authored commits need a deployment guarantee.

Every successful `main` build also refreshes the rolling `nightly` prerelease
with fixed nightly JAR, checksum, and manifest asset names. Nightly metadata is
kept separate and never replaces the stable website download.

See [RELEASING.md](../RELEASING.md) for Semantic Version rules, recovery
instructions, and the complete GitHub/Vercel flow.

The production site is a static Next.js export hosted by Vercel at
`https://recordish.kmsi.me`. Vercel should use `site` as the project root.

The Forge 1.8.9 port is published at
`https://github.com/ErDreiwen/recordish`.
