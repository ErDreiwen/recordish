import type { Metadata, Viewport } from "next";
import { headers } from "next/headers";
import "./globals.css";

const SITE_NAME = "Record-able for Forge 1.8.9";
const SITE_DESCRIPTION =
  "Record Minecraft, save replays, and keep automatic clips with the Forge 1.8.9 port of Record-able.";

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
  const socialImage = new URL("/og.png", origin).toString();

  return {
    metadataBase: origin,
    title: {
      default: SITE_NAME,
      template: `%s · ${SITE_NAME}`,
    },
    description: SITE_DESCRIPTION,
    applicationName: "Record-able",
    authors: [{ name: "Record-able contributors" }],
    openGraph: {
      type: "website",
      title: SITE_NAME,
      description: SITE_DESCRIPTION,
      siteName: "Record-able",
      url: origin,
      images: [
        {
          url: socialImage,
          width: 1731,
          height: 909,
          alt: "Record-able for Forge 1.8.9",
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
