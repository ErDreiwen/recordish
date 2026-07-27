import type { Metadata } from "next";

export const SITE_ORIGIN = "https://recordish.kmsi.me";
export const SITE_NAME = "Recordish";
export const SITE_STYLED_NAME = "record-ish";
export const SITE_TITLE =
  "Recordish (record-ish) — A Record-able Port for Forge 1.8.9";
export const SITE_DESCRIPTION =
  "Recordish is the unofficial Forge 1.8.9 port of Record-able, originally by Minewind's Jo Eusebe. Record Minecraft video, game audio, instant replays, and BedWars clips.";
export const SITE_SOCIAL_IMAGE = "/og-record-ish.png";

export function absoluteUrl(path: string) {
  return new URL(path, `${SITE_ORIGIN}/`).toString();
}

export function createPageMetadata({
  title,
  description,
  path,
  absoluteTitle = false,
}: {
  title: string;
  description: string;
  path: string;
  absoluteTitle?: boolean;
}): Metadata {
  const renderedTitle = absoluteTitle ? title : `${title} · ${SITE_TITLE}`;
  const canonicalUrl = absoluteUrl(path);
  const socialImageUrl = absoluteUrl(SITE_SOCIAL_IMAGE);

  return {
    title: absoluteTitle ? { absolute: title } : title,
    description,
    alternates: { canonical: canonicalUrl },
    openGraph: {
      type: "website",
      title: renderedTitle,
      description,
      siteName: SITE_NAME,
      url: canonicalUrl,
      images: [{
        url: socialImageUrl,
        width: 1718,
        height: 916,
        alt: "Recordish, an unofficial Record-able port for Minecraft Forge 1.8.9",
      }],
    },
    twitter: {
      card: "summary_large_image",
      title: renderedTitle,
      description,
      images: [socialImageUrl],
    },
  };
}
