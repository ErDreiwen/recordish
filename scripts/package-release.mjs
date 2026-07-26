#!/usr/bin/env node

import { createHash } from "node:crypto";
import {
  copyFile,
  mkdir,
  readFile,
  stat,
  writeFile,
} from "node:fs/promises";
import { basename, join, resolve } from "node:path";

const DEFAULTS = Object.freeze({
  minecraft: "1.8.9",
  forge: "11.15.1.2318",
  java: "8",
});

const SEMVER =
  /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|[A-Za-z-][0-9A-Za-z-]*))*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;
const STABLE_SEMVER =
  /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const NIGHTLY_SEMVER =
  /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)-nightly\.[1-9]\d*\.g[0-9a-f]{7,40}$/i;

function usage() {
  return `Usage:
  node scripts/package-release.mjs \\
    --jar <path> \\
    --version <semver> \\
    --channel <stable|nightly> \\
    --output-dir <path> \\
    --repository <owner/repository> \\
    --commit <git-sha>

Optional:
  --tag <release-tag>
  --file-name <output-jar-name>
  --manifest-name <output-json-name>
  --minecraft <version>
  --forge <version>
  --java <version>
  --published-at <ISO-8601 timestamp>`;
}

function fail(message) {
  throw new Error(`${message}\n\n${usage()}`);
}

function parseArguments(argv) {
  const result = {};

  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (token === "--help" || token === "-h") {
      console.log(usage());
      process.exit(0);
    }
    if (!token.startsWith("--")) {
      fail(`Unexpected positional argument: ${token}`);
    }

    const key = token.slice(2);
    const value = argv[index + 1];
    if (!value || value.startsWith("--")) {
      fail(`Missing value for --${key}`);
    }
    if (Object.hasOwn(result, key)) {
      fail(`Duplicate option: --${key}`);
    }
    result[key] = value;
    index += 1;
  }

  return result;
}

function required(options, key) {
  const environmentValue =
    key === "repository" ? process.env.GITHUB_REPOSITORY : undefined;
  const value = options[key] ?? environmentValue;
  if (!value) {
    fail(`Missing required option: --${key}`);
  }
  return value;
}

function safeOutputName(value, description, extension) {
  if (
    basename(value) !== value ||
    value === "." ||
    value === ".." ||
    !value.toLowerCase().endsWith(extension)
  ) {
    fail(`${description} must be a plain ${extension} filename: ${value}`);
  }
  return value;
}

function releaseUrl(repository, tag, fileName) {
  return [
    "https://github.com",
    repository,
    "releases",
    "download",
    encodeURIComponent(tag),
    encodeURIComponent(fileName),
  ].join("/");
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const jarPath = resolve(required(options, "jar"));
  const outputDirectory = resolve(required(options, "output-dir"));
  const version = required(options, "version");
  const channel = required(options, "channel");
  const repository = required(options, "repository");
  const sourceCommit = required(options, "commit").toLowerCase();

  if (!SEMVER.test(version)) {
    fail(`Version is not valid Semantic Versioning: ${version}`);
  }
  if (channel !== "stable" && channel !== "nightly") {
    fail(`Channel must be "stable" or "nightly": ${channel}`);
  }
  if (channel === "stable" && !STABLE_SEMVER.test(version)) {
    fail(`A stable release must be exactly MAJOR.MINOR.PATCH: ${version}`);
  }
  if (channel === "nightly" && !NIGHTLY_SEMVER.test(version)) {
    fail(
      "A nightly version must look like " +
        "<base-version>-nightly.<run-number>.g<commit>",
    );
  }
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository)) {
    fail(`Repository must look like owner/name: ${repository}`);
  }
  if (!/^[0-9a-f]{7,40}$/i.test(sourceCommit)) {
    fail(`Commit must be a 7 to 40 digit hexadecimal Git SHA: ${sourceCommit}`);
  }

  const tag = options.tag ?? (channel === "stable" ? `v${version}` : "nightly");
  const fileName = safeOutputName(
    options["file-name"] ??
      (channel === "stable"
        ? `recordable-${version}-forge-1.8.9.jar`
        : "recordable-nightly-forge-1.8.9.jar"),
    "JAR output name",
    ".jar",
  );
  const manifestName = safeOutputName(
    options["manifest-name"] ??
      (channel === "stable"
        ? "release-manifest.json"
        : "nightly-manifest.json"),
    "Manifest output name",
    ".json",
  );
  const checksumName = `${fileName}.sha256`;
  const publishedAtInput = options["published-at"] ?? new Date().toISOString();
  const publishedAtDate = new Date(publishedAtInput);

  if (Number.isNaN(publishedAtDate.getTime())) {
    fail(`Published timestamp is not valid ISO-8601: ${publishedAtInput}`);
  }

  const jarStats = await stat(jarPath).catch(() => null);
  if (!jarStats?.isFile() || jarStats.size <= 0) {
    fail(`JAR does not exist or is empty: ${jarPath}`);
  }

  const jarBytes = await readFile(jarPath);
  if (
    jarBytes.length < 4 ||
    jarBytes[0] !== 0x50 ||
    jarBytes[1] !== 0x4b ||
    jarBytes[2] !== 0x03 ||
    jarBytes[3] !== 0x04
  ) {
    fail(`Input does not have a JAR/ZIP file signature: ${jarPath}`);
  }

  const sha256 = createHash("sha256")
    .update(jarBytes)
    .digest("hex")
    .toUpperCase();
  const destinationJar = join(outputDirectory, fileName);
  const destinationChecksum = join(outputDirectory, checksumName);
  const destinationManifest = join(outputDirectory, manifestName);
  const downloadUrl = releaseUrl(repository, tag, fileName);
  const checksumUrl = releaseUrl(repository, tag, checksumName);

  await mkdir(outputDirectory, { recursive: true });
  if (resolve(jarPath) !== resolve(destinationJar)) {
    await copyFile(jarPath, destinationJar);
  }
  await writeFile(
    destinationChecksum,
    `${sha256}  ${fileName}\n`,
    "utf8",
  );

  const manifest = {
    schemaVersion: 1,
    channel,
    version,
    minecraft: options.minecraft ?? DEFAULTS.minecraft,
    forge: options.forge ?? DEFAULTS.forge,
    java: options.java ?? DEFAULTS.java,
    fileName,
    fileSizeBytes: jarStats.size,
    sha256,
    downloadUrl,
    checksumUrl,
    sourceCommit,
    publishedAt: publishedAtDate.toISOString(),
  };

  await writeFile(
    destinationManifest,
    `${JSON.stringify(manifest, null, 2)}\n`,
    "utf8",
  );

  console.log(
    JSON.stringify(
      {
        jar: destinationJar,
        checksum: destinationChecksum,
        manifest: destinationManifest,
        release: manifest,
      },
      null,
      2,
    ),
  );
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
