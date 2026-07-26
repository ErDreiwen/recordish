export const RELEASE = {
  version: "1.0.0",
  minecraft: "1.8.9",
  forge: "11.15.1.2318",
  java: "8",
  fileName: "recordable-1.0.0-forge-1.8.9.jar",
  fileSize: "1.68 MiB",
  sha256:
    "00D7E034DDD1E94B4E9A2B5B89C0AD09C7FE9A63E2291D4B3439A69925D6ED71",
} as const;

export const DOWNLOAD_PATH = `/downloads/${RELEASE.fileName}`;
export const CHECKSUM_PATH = `${DOWNLOAD_PATH}.sha256`;
