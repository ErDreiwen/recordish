import {
  BAGUETTE_MAP,
  COMMUNITY_DISCORD,
  PORT_REPOSITORY,
} from "./links";

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <p>
        record-ish is unofficial · Original Record-able by Minewind&apos;s Jo
        Eusebe · Port maintained by ErDreiwen · MIT licensed
      </p>
      <nav aria-label="Project and social links">
        <a href={PORT_REPOSITORY} rel="noreferrer" target="_blank">
          GitHub
        </a>
        <a
          aria-label="Baguette? Open Pollen Bakery in Manchester on Google Maps"
          href={BAGUETTE_MAP}
          rel="noreferrer"
          target="_blank"
        >
          Baguette?
        </a>
        <a href={COMMUNITY_DISCORD} rel="noreferrer" target="_blank">
          Discord
        </a>
      </nav>
    </footer>
  );
}
