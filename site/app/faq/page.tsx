import type { Metadata } from "next";
import Link from "next/link";
import type { ReactNode } from "react";
import { WindowShell } from "../window-shell";

export const metadata: Metadata = {
  title: "FAQ",
  description:
    "Answers about the original Record-able Fabric mod and the unofficial Recordable Community Forge 1.8.9 port.",
};

type Faq = {
  question: string;
  answer: ReactNode;
};

const originalFaqs: Faq[] = [
  {
    question: "What is the original Record-able?",
    answer: (
      <p>
        Record-able is a client-side Fabric recording mod created by
        Minewind&apos;s Jo Eusebe. It captures Minecraft video and game audio,
        supports microphone audio, and includes overlays, a video collection,
        replays, and automatic clips.
      </p>
    ),
  },
  {
    question: "Is this the official Record-able website?",
    answer: (
      <p>
        No. This site belongs to Recordable Community, an unofficial Forge
        1.8.9 port. Use the{" "}
        <a
          href="https://modrinth.com/mod/record-able"
          rel="noreferrer"
          target="_blank"
        >
          original Modrinth page
        </a>{" "}
        for official releases and announcements.
      </p>
    ),
  },
  {
    question: "Which Minecraft versions and loaders does the original support?",
    answer: (
      <p>
        The original project is Fabric-only. Minecraft and Java requirements
        vary between its release families, so choose a matching file from the
        official Modrinth versions page instead of using this Forge download.
      </p>
    ),
  },
  {
    question: "Does the original mod require FFmpeg?",
    answer: (
      <p>
        Yes. FFmpeg turns captured frames and audio into playable video. It is
        not bundled inside the mod; Record-able asks before downloading and
        verifying it when setup is required.
      </p>
    ),
  },
  {
    question: "Does it replace OBS?",
    answer: (
      <p>
        It can handle Minecraft-only recording without a separate screen
        recorder. It does not replace OBS scenes, livestreaming, webcam
        layouts, alerts, or full desktop capture.
      </p>
    ),
  },
  {
    question: "What are automatic clips?",
    answer: (
      <p>
        They save noteworthy moments such as deaths, dimension changes,
        advancements, kills, and kill montages. A rolling buffer can include
        footage from before and after the trigger.
      </p>
    ),
  },
  {
    question: "Does it work with Replay Mod or Flashback?",
    answer: (
      <p>
        Current official builds include a compatibility bridge, but multiple
        recording mods can still compete for rendering or audio access. Visual
        or audio quirks may remain.
      </p>
    ),
  },
  {
    question: "Where should I report an original-mod problem?",
    answer: (
      <p>
        Use the support links on the original Modrinth page, especially the
        creator&apos;s Discord. A report prepared here does not automatically
        reach or represent the original maintainer.
      </p>
    ),
  },
];

const communityFaqs: Faq[] = [
  {
    question: "What is Recordable Community?",
    answer: (
      <p>
        It is an unofficial, community-maintained Forge 1.8.9 port of
        Record-able. The original attribution and MIT license are retained; it
        is not an official release or endorsement from the original author.
      </p>
    ),
  },
  {
    question: "What does the port require?",
    answer: (
      <p>
        Minecraft 1.8.9, Forge 11.15.1.2318, and Java 8 for the game. Keep only
        one Record-able or Recordable Community JAR in the profile.
      </p>
    ),
  },
  {
    question: "Does it work in Prism and Lunar Launcher?",
    answer: (
      <p>
        Prism Launcher is supported. In Lunar, use the Vanilla/Forge option
        with a Forge 1.8.9 profile. The branded Lunar/Ichor runtime filters
        arbitrary external Forge JARs before Forge can load them.
      </p>
    ),
  },
  {
    question: "Why did recording start by itself?",
    answer: (
      <p>
        Auto-record is enabled by default and starts two seconds after joining
        a world. Disable Auto Record in settings if you prefer manual control.
      </p>
    ),
  },
  {
    question: "What are the default controls and output folders?",
    answer: (
      <p>
        Press <kbd>-</kbd> to record, <kbd>=</kbd> to pause, <kbd>F9</kbd> for
        settings, <kbd>F12</kbd> for the collection, and <kbd>V</kbd> for mic
        push-to-talk. Recordings go to <code>recordings</code>; automatic clips
        go to <code>recordings\recording_auto_clips</code>.
      </p>
    ),
  },
  {
    question: "How do I capture BedWars kills?",
    answer: (
      <p>
        Enable Auto-Clipping, On Player Kill, and Kill Montage. Direct melee
        hits, recent aimed swings, and locally fired arrows are supported, but
        server-side combat behavior can prevent perfect attribution.
      </p>
    ),
  },
  {
    question: "What if the FFmpeg install fails?",
    answer: (
      <p>
        Open settings, search for FFmpeg, and retry. You can also select an
        existing <code>ffmpeg.exe</code>, keep <code>ffprobe.exe</code> beside
        it, and run Test FFmpeg.
      </p>
    ),
  },
  {
    question: "What should I try for black video or missing audio?",
    answer: (
      <p>
        Run Capture Test first. For black video, temporarily disable shaders
        and OptiFine Fast Render and test windowed mode. For audio, verify
        Capture Audio and the selected devices, then restart after changing
        them.
      </p>
    ),
  },
  {
    question: "How do I report a port, UI, or website issue?",
    answer: (
      <p>
        Use the guided <Link href="/report">Report Center</Link>. It prepares a
        complete Markdown report that you can copy or download. Until the
        public fork exists, share it through the linked community channel.
      </p>
    ),
  },
  {
    question: "Does the Report Center upload my logs?",
    answer: (
      <p>
        No. The builder runs in your browser and does not submit or upload the
        text you enter. Redact usernames, server addresses, chat, file paths,
        tokens, and other private details before sharing the result.
      </p>
    ),
  },
];

function FaqGroup({
  id,
  label,
  title,
  faqs,
}: {
  id: string;
  label: string;
  title: string;
  faqs: Faq[];
}) {
  return (
    <section id={id} className="faq-group" aria-labelledby={`${id}-title`}>
      <p className="section-number">{label}</p>
      <h2 id={`${id}-title`}>{title}</h2>
      <div className="faq-list">
        {faqs.map((faq) => (
          <details className="faq-item" key={faq.question}>
            <summary>{faq.question}</summary>
            <div className="faq-answer">{faq.answer}</div>
          </details>
        ))}
      </div>
    </section>
  );
}

export default function FaqPage() {
  return (
    <WindowShell>
      <main id="main-content" className="faq-page">
        <header className="page-lead">
          <div>
            <p className="issue-line">HELP DESK / TWO PROJECTS</p>
            <h1>Frequently asked questions</h1>
            <p>
              Start with the project you actually installed. The original
              Fabric mod and this Forge port have different requirements and
              maintainers.
            </p>
          </div>
          <Link className="button button-primary" href="/report">
            Build a report
          </Link>
        </header>

        <nav className="scope-switcher" aria-label="FAQ groups">
          <a href="#original">
            <span>Original</span>
            Record-able / Fabric
          </a>
          <a href="#community">
            <span>This port</span>
            Recordable Community / Forge 1.8.9
          </a>
        </nav>

        <div className="faq-columns">
          <FaqGroup
            id="original"
            label="01 / OFFICIAL PROJECT"
            title="Original Record-able"
            faqs={originalFaqs}
          />
          <FaqGroup
            id="community"
            label="02 / UNOFFICIAL PORT"
            title="Recordable Community"
            faqs={communityFaqs}
          />
        </div>

        <div className="docs-end">
          <Link className="button" href="/docs">
            Read the docs
          </Link>
          <Link className="button button-primary" href="/report">
            Report a problem
          </Link>
        </div>
      </main>
    </WindowShell>
  );
}
