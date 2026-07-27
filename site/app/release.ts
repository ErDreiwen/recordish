import releaseManifest from "../release-manifest.json";

type ReleaseManifest = {
  schemaVersion: number;
  channel: "stable";
  version: string;
  minecraft: string;
  forge: string;
  java: string;
  fileName: string;
  fileSizeBytes: number;
  sha256: string;
  downloadUrl: string;
  checksumUrl: string;
  sourceCommit: string;
  publishedAt: string;
};

const VERSION_PATTERN =
  /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/;
const SHA256_PATTERN = /^[0-9A-Fa-f]{64}$/;
const COMMIT_PATTERN = /^[0-9A-Fa-f]{40}$/;

function isArtifactUrl(value: string, expectedFileName: string) {
  if (value.startsWith("/")) {
    return value.startsWith("/downloads/") && value.endsWith(expectedFileName);
  }

  try {
    const url = new URL(value);
    return (
      url.protocol === "https:" &&
      !url.pathname.includes("/releases/latest/") &&
      url.pathname.endsWith(`/${expectedFileName}`)
    );
  } catch {
    return false;
  }
}

function validateReleaseManifest(value: unknown): ReleaseManifest {
  if (!value || typeof value !== "object") {
    throw new Error("release-manifest.json must contain an object.");
  }

  const release = value as Record<string, unknown>;
  const stringFields = [
    "version",
    "minecraft",
    "forge",
    "java",
    "fileName",
    "sha256",
    "downloadUrl",
    "checksumUrl",
    "sourceCommit",
    "publishedAt",
  ] as const;

  if (release.schemaVersion !== 1 || release.channel !== "stable") {
    throw new Error("release-manifest.json has an unsupported schema.");
  }
  for (const field of stringFields) {
    if (typeof release[field] !== "string" || release[field].length === 0) {
      throw new Error(`release-manifest.json is missing ${field}.`);
    }
  }
  if (!VERSION_PATTERN.test(release.version as string)) {
    throw new Error("release-manifest.json has an invalid version.");
  }
  if (
    !Number.isSafeInteger(release.fileSizeBytes) ||
    (release.fileSizeBytes as number) <= 0
  ) {
    throw new Error("release-manifest.json has an invalid file size.");
  }
  if (!SHA256_PATTERN.test(release.sha256 as string)) {
    throw new Error("release-manifest.json has an invalid SHA-256.");
  }
  if (!COMMIT_PATTERN.test(release.sourceCommit as string)) {
    throw new Error("release-manifest.json has an invalid source commit.");
  }
  if (Number.isNaN(Date.parse(release.publishedAt as string))) {
    throw new Error("release-manifest.json has an invalid publication date.");
  }

  const fileName = release.fileName as string;
  if (
    !fileName.endsWith(".jar") ||
    fileName.includes("/") ||
    fileName.includes("\\")
  ) {
    throw new Error("release-manifest.json has an invalid JAR filename.");
  }
  if (!isArtifactUrl(release.downloadUrl as string, fileName)) {
    throw new Error("release-manifest.json has an invalid download URL.");
  }
  if (!isArtifactUrl(release.checksumUrl as string, `${fileName}.sha256`)) {
    throw new Error("release-manifest.json has an invalid checksum URL.");
  }

  return value as ReleaseManifest;
}

function formatFileSize(bytes: number) {
  const mebibyte = 1024 * 1024;
  if (bytes >= mebibyte) {
    return `${(bytes / mebibyte).toFixed(2)} MiB`;
  }
  return `${(bytes / 1024).toFixed(1)} KiB`;
}

const manifest = validateReleaseManifest(releaseManifest);

export const RELEASE = Object.freeze({
  ...manifest,
  sha256: manifest.sha256.toUpperCase(),
  fileSize: formatFileSize(manifest.fileSizeBytes),
});

export const STABLE_DOWNLOAD_URL = RELEASE.downloadUrl;
export const STABLE_CHECKSUM_URL = RELEASE.checksumUrl;
export const NIGHTLY_DOWNLOAD_URL =
  "https://github.com/ErDreiwen/recordish/releases/download/nightly/recordish-nightly-forge-1.8.9.jar";
