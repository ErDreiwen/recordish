import type { Metadata, Viewport } from "next";
import "./globals.css";
import { PORT_REPOSITORY } from "./links";
import { RELEASE } from "./release";
import {
  absoluteUrl,
  SITE_DESCRIPTION as SEO_SITE_DESCRIPTION,
  SITE_NAME as SEO_SITE_NAME,
  SITE_ORIGIN as SEO_SITE_ORIGIN,
  SITE_STYLED_NAME,
} from "./site-config";


const SITE_NAME = "Recordish (record-ish) — A Record-able Port for Forge 1.8.9";
const SITE_DESCRIPTION =
  "Recordish is the unofficial Forge 1.8.9 port of Record-able, originally by Minewind's Jo Eusebe. Record Minecraft video, game audio, instant replays, and BedWars clips.";

const SITE_ORIGIN = new URL("https://recordish.kmsi.me");
const SOCIAL_IMAGE = new URL("/og-record-ish.png", SITE_ORIGIN).toString();
const stableDownloadUrl = RELEASE.downloadUrl.startsWith("/")
  ? absoluteUrl(RELEASE.downloadUrl)
  : RELEASE.downloadUrl;

const structuredData = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "WebSite",
      "@id": `${SEO_SITE_ORIGIN}/#website`,
      url: `${SEO_SITE_ORIGIN}/`,
      name: SEO_SITE_NAME,
      alternateName: SITE_STYLED_NAME,
      description: SEO_SITE_DESCRIPTION,
      inLanguage: "en",
      sameAs: [PORT_REPOSITORY],
    },
    {
      "@type": "SoftwareApplication",
      "@id": `${SEO_SITE_ORIGIN}/#software`,
      name: SEO_SITE_NAME,
      alternateName: SITE_STYLED_NAME,
      description: SEO_SITE_DESCRIPTION,
      url: `${SEO_SITE_ORIGIN}/`,
      downloadUrl: stableDownloadUrl,
      applicationCategory: "GameApplication",
      applicationSubCategory: "Minecraft recording mod",
      operatingSystem: "Windows, macOS, Linux",
      softwareVersion: RELEASE.version,
      softwareRequirements: `Minecraft ${RELEASE.minecraft}; Forge ${RELEASE.forge}; Java ${RELEASE.java}`,
      datePublished: RELEASE.publishedAt,
      license: `${PORT_REPOSITORY}/blob/main/LICENSE`,
      isBasedOn: "https://modrinth.com/mod/record-able",
      sameAs: [PORT_REPOSITORY],
      offers: { "@type": "Offer", price: "0", priceCurrency: "USD" },
    },
  ],
};


export const metadata: Metadata = {
  metadataBase: SITE_ORIGIN,
  title: {
    default: SITE_NAME,
    template: `%s · ${SITE_NAME}`,
  },
  description: SITE_DESCRIPTION,
  applicationName: "Recordish",
  authors: [
    { name: "Minewind's Jo Eusebe (original Record-able)" },
    { name: "ErDreiwen (record-ish community port)" },
  ],
  creator: "ErDreiwen",
  publisher: "Recordish community project",
  category: "Minecraft mod",
  verification: {
    google: "p-gLkl6sQ_89A8ucUoz8pGKsRV3o2B5v63EFLIV_KBA",
  },
  robots: {
    index: true,
    follow: true,
    googleBot: {
      index: true,
      follow: true,
      "max-image-preview": "large",
      "max-snippet": -1,
      "max-video-preview": -1,
    },
  },
  openGraph: {
    type: "website",
    title: SITE_NAME,
    description: SITE_DESCRIPTION,
    siteName: "Recordish",
    url: SITE_ORIGIN,
    images: [
      {
        url: SOCIAL_IMAGE,
        width: 1718,
        height: 916,
        alt: "Recordish, an unofficial Record-able port for Forge 1.8.9",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: SITE_NAME,
    description: SITE_DESCRIPTION,
    images: [SOCIAL_IMAGE],
  },
};

export const viewport: Viewport = {
  colorScheme: "light",
  themeColor: "#245aa8",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <head>
        <script
          dangerouslySetInnerHTML={{
            __html: JSON.stringify(structuredData).replace(/</g, "\\u003c"),
          }}
          type="application/ld+json"
        />
      </head>
      <body>{children}</body>
    </html>
  );
}
