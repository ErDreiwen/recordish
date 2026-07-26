import type { Metadata, Viewport } from "next";
import { headers } from "next/headers";
import "./globals.css";

const SITE_NAME = "Recordable Community — Unofficial Forge 1.8.9 Port";
const SITE_DESCRIPTION =
  "The unofficial Forge 1.8.9 community port of Record-able, originally by Minewind's Jo Eusebe. No corporate waffle—just recording, clips, and a proper manual.";

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
  const socialImage = new URL("/og-manc.png", origin).toString();

  return {
    metadataBase: origin,
    title: {
      default: SITE_NAME,
      template: `%s · ${SITE_NAME}`,
    },
    description: SITE_DESCRIPTION,
    applicationName: "Recordable Community",
    authors: [
      { name: "Minewind's Jo Eusebe (original Record-able)" },
      { name: "ErDreiwen (community port)" },
    ],
    openGraph: {
      type: "website",
      title: SITE_NAME,
      description: SITE_DESCRIPTION,
      siteName: "Recordable Community",
      url: origin,
      images: [
        {
          url: socialImage,
          width: 1731,
          height: 909,
          alt: "Recordable Community, the unofficial Forge 1.8.9 port with no corporate waffle",
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
