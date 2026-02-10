import { Section } from '../layout/Section';
import { PinIcon, EthIcon, PlayersIcon, ClockIcon, TargetIcon } from '../icons/Icons';

const HOST_ITEMS = [
  { icon: PinIcon, title: 'Location & Zone', desc: 'Choose any city. Set the initial radius.' },
  { icon: EthIcon, title: 'Entry Fee', desc: '0.01 — 1.0 ETH per player' },
  { icon: PlayersIcon, title: 'Player Cap', desc: '10 — 200 players per game' },
  { icon: ClockIcon, title: 'Duration', desc: '30 — 120 minutes' },
  { icon: TargetIcon, title: 'Zone Schedule', desc: 'Configure shrink stages and timing' },
];

export function HostSection() {
  return (
    <Section id="host" title="Create Your Own Game" subtitle="Anyone can host a Chain-Assassin event">
      <div className="host-grid">
        <div className="host-info">
          <p className="host-info__desc">Pick a city, define the play zone, set the stakes. You control every parameter of the game.</p>
          <ul className="host-info__list">
            {HOST_ITEMS.map((item) => (
              <li key={item.title}>
                <item.icon width={24} height={24} />
                <div>
                  <strong>{item.title}</strong><br />
                  {item.desc}
                </div>
              </li>
            ))}
          </ul>
          <a href="#" className="btn btn--primary btn--lg">
            Host a Game <span className="badge badge--soon">Coming Soon</span>
          </a>
        </div>
      </div>
    </Section>
  );
}
