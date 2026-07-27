# Releasing record-ish

This repository has two release channels:

- **Stable** releases are reviewed Semantic Version releases such as `v1.1.0`.
- **Nightly** is a rolling prerelease rebuilt from every successful `main`
  commit. It is useful for testing and is not promoted as the normal download.

The mod targets Minecraft 1.8.9, Forge 11.15.1.2318, and Java 8. Gradle requires
a JDK 17 or newer host; CI uses JDK 17 while compilation and Java-based checks
use an explicitly installed JDK 8 toolchain.

## Version source of truth

`version.txt` contains the current stable semantic version. Release Please owns
updates to that file and to `CHANGELOG.md`. Minecraft, Forge, and Java target
versions are release metadata, not part of the semantic version.

The Gradle command line still receives `-PmodVersion=<version>` in CI. That
ensures a build is stamped from the workflow's exact version even if a different
branch is checked out. The final stable filename is:

```text
recordish-<version>-forge-1.8.9.jar
```

Do not edit a Java version constant, the website release data, a checksum, or a
built JAR by hand. Those values are generated from the release build.

## Conventional Commit rules

Release Please determines the next stable version from commits on `main`:

- `fix: ...` increments the patch version.
- `feat: ...` increments the minor version.
- `feat!: ...`, `fix!: ...`, or a `BREAKING CHANGE:` footer increments the
  major version.
- `docs:`, `test:`, `build:`, `ci:`, and `chore:` do not normally request a
  stable mod release.

Use squash merges and give the squash commit a clear Conventional Commit title.
Commits that only affect `site/**` or `docs/**` are excluded from the mod release
calculation.

## Stable release flow

1. Review the Release Please PR. When `RELEASE_PLEASE_TOKEN` is configured, its
   normal pull-request CI must pass before merge.
2. Merge the Release Please PR.
3. `.github/workflows/release.yml` creates a draft GitHub Release and checks out
   its exact tagged commit.
4. The workflow rebuilds and tests the mod, packages the remapped JAR, computes
   its SHA-256 fingerprint, and creates `release-manifest.json`.
5. The versioned JAR, checksum, and manifest are uploaded while the release is
   still a draft.
6. The exact manifest is preserved as an Actions artifact for the site metadata
   job while the release is still a draft.
7. Only after all uploads succeed is the release published and marked latest.
8. The exact manifest is copied to `site/release-manifest.json`; the transitional
   repository-hosted JAR directory is removed; the site is tested; and a small
   `chore(release): publish vX site metadata` commit is pushed to `main`.
9. Vercel's Git integration deploys that metadata commit. The JAR itself remains
   a GitHub Release asset rather than growing the Git repository forever.

The exact stable publication gate always rebuilds and tests the tagged commit.
That gate runs even when Release Please uses the built-in `GITHUB_TOKEN`, whose
generated pull-request events GitHub intentionally does not use to start other
workflows. A failed stable build leaves the GitHub Release as a recoverable
draft; it never publishes an unverified JAR.

The stable download URL is immutable and versioned. The website reads the
committed release manifest, whose URLs point to the matching GitHub Release.

Public-repository commits on the configured Production branch normally deploy
through Vercel's Git integration. `VERCEL_DEPLOY_HOOK_URL` is optional, but
recommended as a deployment guarantee and recovery path for the bot-authored
metadata commit. When configured as a repository secret, the metadata job sends
a `POST` to that branch-linked hook after the push. No Vercel credential is
hardcoded.

## Nightly flow

Every successful push to `main` builds from the stable base in `version.txt`:

```text
<base-version>-nightly.<run-number>.g<short-commit>
```

The CI workflow verifies that the commit is still the head of `main` before
packaging and again immediately before it mutates the dedicated moving
`nightly` tag through the GitHub API. Its public assets keep fixed names:

```text
recordish-nightly-forge-1.8.9.jar
recordish-nightly-forge-1.8.9.jar.sha256
nightly-manifest.json
```

The nightly release must always remain a prerelease so GitHub's `latest`
endpoint continues to mean the latest stable build.

## Manual recovery

Run the **Release** workflow manually with a `vX.Y.Z` tag:

- `publish-draft` rebuilds and completes an existing draft release.
- `site-metadata` downloads the already-published release manifest and retries
  only the site metadata commit and optional Vercel hook.

The recovery path refuses malformed tags and refuses to replace assets on an
already-published stable release.

## Required repository settings

GitHub Actions must be allowed to:

- create and update pull requests for Release Please;
- write GitHub Releases and the dedicated `nightly` tag;
- push the generated site metadata commit to `main`.

By default Release Please uses the repository's built-in `GITHUB_TOKEN`. That is
enough to create the release PR and to run the exact post-merge stable build, but
GitHub intentionally does not trigger pull-request workflows for PR activity
created by that token. To run CI automatically on generated Release Please PRs,
add a fine-grained personal access token or GitHub App token as the
`RELEASE_PLEASE_TOKEN` repository secret. Give it only the repository contents,
issues, and pull-request access Release Please requires. The workflow falls back
to `GITHUB_TOKEN` when the secret is absent.

No personal token is required to build, verify, or publish the exact stable
artifact. Checkout credentials are not persisted; write tokens are exposed only
to the narrow tag, release, and metadata-push steps.

If `main` is protected, allow the GitHub Actions app to push the metadata commit
or replace that final push with a bot-authored pull request.

Vercel must track `main` as Production and use `site` as the project root. The
site is deployed through the existing Vercel Git integration or the optional,
recommended deploy hook; no ChatGPT/OpenAI hosting is involved.

## Release checks and legacy caveats

- Publish only `build/libs/*.jar`, the final remapped artifact. Files under
  `build/intermediates` are not distributable.
- The released JAR must be the exact bytes that were hashed and tested.
- `pipelineSmokeTest` is currently manual: its missing-FFmpeg fallback check is
  not isolated from an FFmpeg executable already available on the runner.
- The FFmpeg installer smoke test performs a live external download. Keep it
  scheduled or manual rather than making releases depend on an upstream host.
- A headless runner cannot prove that Minecraft, shaders, audio devices, and
  launchers work together. Perform a final Prism/Forge launch check for material
  recording changes.
- Forge, MCP mappings, old Loom, and snapshot Mixin dependencies are fragile.
  Do not casually upgrade the wrapper or plugin graph as part of a release.

## Local packaging check

After building a versioned JAR, the packaging helper can be exercised without
publishing anything:

```powershell
node scripts/package-release.mjs `
  --jar build/libs/recordish-1.0.0-forge-1.8.9.jar `
  --version 1.0.0 `
  --channel stable `
  --output-dir build/release-package `
  --repository ErDreiwen/recordish `
  --commit 0b05278d90d3568011258a9826484ba3abee5363
```

Inspect the generated JAR, checksum, and manifest together. Never upload only
one of them.
