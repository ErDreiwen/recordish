import type { Metadata, Viewport } from "next";
import "./globals.css";

const SITE_NAME = "record-ish — A Record-able Port for Forge 1.8.9";
const SITE_DESCRIPTION =
  "record-ish is the unofficial Forge 1.8.9 port of Record-able, originally by Minewind's Jo Eusebe. No corporate waffle—just recording, clips, and a proper manual.";

const SITE_ORIGIN = new URL("https://kmsi.me");
const SOCIAL_IMAGE = new URL("/og-record-ish.png", SITE_ORIGIN).toString();

export const metadata: Metadata = {
  metadataBase: SITE_ORIGIN,
  title: {
    default: SITE_NAME,
    template: `%s · ${SITE_NAME}`,
  },
  description: SITE_DESCRIPTION,
  applicationName: "record-ish",
  authors: [
    { name: "Minewind's Jo Eusebe (original Record-able)" },
    { name: "ErDreiwen (record-ish community port)" },
  ],
  openGraph: {
    type: "website",
    title: SITE_NAME,
    description: SITE_DESCRIPTION,
    siteName: "record-ish",
    url: SITE_ORIGIN,
    images: [
      {
        url: SOCIAL_IMAGE,
        width: 1718,
        height: 916,
        alt: "record-ish, an unofficial Record-able port for Forge 1.8.9",
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
      <body>{children}</body>
    </html>
  );
}
