import type { Metadata, Viewport } from "next";
import { headers } from "next/headers";
import "./globals.css";

const SITE_NAME = "record-ish — A Record-able Port for Forge 1.8.9";
const SITE_DESCRIPTION =
  "record-ish is the unofficial Forge 1.8.9 port of Record-able, originally by Minewind's Jo Eusebe. No corporate waffle—just recording, clips, and a proper manual.";

function requestOrigin(
  host: string | null,
  forwardedProtocol: string | null,
): URL {
  const safeHost = host?.split(",")[0]?.trim() || "localhost";
  const protocol =
    forwardedProtocol?.split(",")[0]?.trim() ||
    (safeHost.startsWith("localhost") || safeHost.startsWith("127.0.0.1")
      ? "http"
      : "https");

  try {
    return new URL(`${protocol}://${safeHost}`);
  } catch {
    return new URL("http://localhost");
  }
}

export async function generateMetadata(): Promise<Metadata> {
  const requestHeaders = await headers();
  const origin = requestOrigin(
    requestHeaders.get("x-forwarded-host") ?? requestHeaders.get("host"),
    requestHeaders.get("x-forwarded-proto"),
  );
  const socialImage = new URL("/og-record-ish.png", origin).toString();

  return {
    metadataBase: origin,
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
      url: origin,
      images: [
        {
          url: socialImage,
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
      images: [socialImage],
    },
  };
}

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
