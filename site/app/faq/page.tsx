import type { Metadata } from "next";
import Link from "next/link";
import type { ReactNode } from "react";
import { WindowShell } from "../window-shell";

export const metadata: Metadata = {
  title: "FAQ",
  description:
    "Straight answers about the original Record-able Fabric mod and record-ish, its mouthy unofficial Forge 1.8.9 port.",
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
        Nope. This is the record-ish site, an unofficial Forge 1.8.9 port. Use
        the{" "}
        <a
          href="https://modrinth.com/mod/record-able"
          rel="noreferrer"
          target="_blank"
        >
          original Modrinth page
        </a>{" "}
        for official releases and announcements. Credit stays where it belongs.
      </p>
    ),
  },
  {
    question: "Which Minecraft versions and loaders does the original support?",
    answer: (
      <p>
        The original is Fabric-only. Minecraft and Java requirements vary
        between releases, so check the official Modrinth versions page. Do not
        guess, and do not use this Forge download in any Fabric profile.
      </p>
    ),
  },
  {
    question: "Does the original mod require FFmpeg?",
    answer: (
      <p>
        Yes. FFmpeg does the grunt work that turns frames and audio into a
        playable video. It is not bundled; Record-able asks before downloading
        and verifying it.
      </p>
    ),
  },
  {
    question: "Does it replace OBS?",
    answer: (
      <p>
        Not completely. It handles Minecraft-only recording without a separate
        screen recorder. OBS still wins for scenes, livestreaming, webcams,
        alerts, and full desktop capture. Horses for courses.
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
        Pick Original Record-able in the <Link href="/report">Report Center</Link>{" "}
        and it will point you at the official Discord and Modrinth page. This
        port does not accept or pretend it can fix upstream reports.
      </p>
    ),
  },
];

const communityFaqs: Faq[] = [
  {
    question: "What is record-ish?",
    answer: (
      <p>
        An unofficial, community-maintained Forge 1.8.9 port of Record-able. We
        keep the original attribution and MIT license and claim precisely none
        of the original author&apos;s credit.
      </p>
    ),
  },
  {
    question: "What does the port require?",
    answer: (
      <p>
        Minecraft 1.8.9, Forge 11.15.1.2318, and Java 8. Those versions. Do not
        freestyle it. Keep one Record-able or record-ish JAR in the profile.
      </p>
    ),
  },
  {
    question: "Does it work in Prism and Lunar Launcher?",
    answer: (
      <p>
        Prism works. In Lunar, use Vanilla/Forge with a Forge 1.8.9 profile.
        Branded Lunar/Ichor bins arbitrary external Forge JARs before Forge can
        load them.
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
        Use the guided <Link href="/report">Report Center</Link>. Quick report
        asks normal-human questions and opens a pre-filled GitHub issue. Full
        nerd report keeps the versions, environment, logs, copy, and download
        tools when proper evidence is needed.
      </p>
    ),
  },
  {
    question: "Does the Report Center upload my logs?",
    answer: (
      <p>
        No. We are mouthy, not nosy. The builder stays in your browser and
        uploads nothing. Redact usernames, server addresses, chat, file paths,
        tokens, and anything else private before sharing.
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
            <p className="issue-line">
              QUESTIONS / TWO PROJECTS / BEFORE ANYONE GETS LIVELY
            </p>
            <h1>Questions people keep asking.</h1>
            <p>
              Two projects, different requirements, different maintainers.
              Pick the one you actually installed and save everyone three
              comments of confusion.
            </p>
          </div>
          <Link className="button button-primary" href="/report">
            Something broken?
          </Link>
        </header>

        <nav className="scope-switcher" aria-label="FAQ groups">
          <a href="#original">
            <span>The original one</span>
            Record-able / Fabric
          </a>
          <a href="#community">
            <span>This scrappy port</span>
            record-ish / Forge 1.8.9
          </a>
        </nav>

        <div className="faq-columns">
          <FaqGroup
            id="original"
            label="01 / CREDIT WHERE IT IS DUE"
            title="Original Record-able"
            faqs={originalFaqs}
          />
          <FaqGroup
            id="community"
            label="02 / OUR BIT"
            title="record-ish"
            faqs={communityFaqs}
          />
        </div>

        <div className="docs-end">
          <Link className="button" href="/docs">
            Read the docs
          </Link>
          <Link className="button button-primary" href="/report">
            Report what is broken
          </Link>
        </div>
      </main>
    </WindowShell>
  );
}
