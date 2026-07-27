import type { MetadataRoute } from "next";
import { RELEASE } from "./release";
import { absoluteUrl } from "./site-config";

export const dynamic = "force-static";

const lastModified = new Date(RELEASE.publishedAt);

export default function sitemap(): MetadataRoute.Sitemap {
  return [
    {
      url: absoluteUrl("/"),
      lastModified,
      changeFrequency: "monthly",
      priority: 1,
    },
    {
      url: absoluteUrl("/download/"),
      lastModified,
      changeFrequency: "weekly",
      priority: 0.9,
    },
    {
      url: absoluteUrl("/docs/"),
      lastModified,
      changeFrequency: "monthly",
      priority: 0.8,
    },
    {
      url: absoluteUrl("/faq/"),
      lastModified,
      changeFrequency: "monthly",
      priority: 0.7,
    },
    {
      url: absoluteUrl("/report/"),
      lastModified,
      changeFrequency: "monthly",
      priority: 0.5,
    },
  ];
}
