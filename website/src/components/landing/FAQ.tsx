import { useState, type ReactNode } from 'react';
import { Section } from '../layout/Section';
import { EXPLORER_URL } from '../../config/constants';

const CONTRACT_ADDRESS = '0x0ABfD376Bd339A6dcd885F37aB0A9cE761c2F99e';

const FAQ_ITEMS: { q: string; a: ReactNode }[] = [
  {
    q: 'What is Chain-Assassin?',
    a: 'Chain-Assassin is a real-world elimination game where players hunt each other by scanning QR codes. Games take place in GPS-tracked zones that shrink over time, creating intense close-quarters action. Entry fees are paid in ETH on Base blockchain, and the prize pool is split among the top players.',
  },
  {
    q: 'How do kills work?',
    a: "Each player is assigned a random target. Find them in the real world and scan the QR code on their shirt or badge using the app. The kill is verified instantly. You then receive a new random target from the remaining alive players.",
  },
  {
    q: 'What happens when the zone shrinks?',
    a: "Like battle royale games, the play zone shrinks over time on a predefined schedule. If you're outside the zone for more than 60 seconds, you're eliminated. The shrinking zone forces players closer together, creating more encounters and faster-paced gameplay as the match progresses.",
  },
  {
    q: 'How are prizes distributed?',
    a: 'Each game has its own prize split configured by the host. The entry fees are divided between 1st, 2nd, and 3rd place, a kill bonus pool, a creator fee, and a platform fee — all defined as basis points (bps) that must total 100%. This means every game can have a unique payout structure. Check the game detail page to see the exact split before joining.',
  },
  {
    q: 'What blockchain does it use?',
    a: 'Chain-Assassin runs on Base, an Ethereum Layer 2 blockchain. Base offers low transaction fees, fast confirmations, and the full security of Ethereum. All entry fees, prize distributions, and game records are on-chain.',
  },
  {
    q: 'Where is the smart contract?',
    a: (<>The Chain-Assassin smart contract is deployed on Base Sepolia (testnet) at address <a href={`${EXPLORER_URL}/address/${CONTRACT_ADDRESS}`} target="_blank" rel="noopener noreferrer" className="faq-contract-link"><code>{CONTRACT_ADDRESS}</code></a>. The contract handles game creation, player registration, entry fee escrow, kill verification, prize distribution, and refunds for cancelled games — all fully on-chain and verifiable.</>),
  },
  {
    q: 'What if a game is cancelled?',
    a: "If the minimum player count isn't reached before the game start time, the game is automatically cancelled and all entry fees are refunded to players' wallets. No risk of losing funds on cancelled games.",
  },
  {
    q: 'What are tactical items?',
    a: "There are two tactical items you can use during a game, each with a 5-minute cooldown. Ping Target shows a 50m radius circle on the map where your target currently is — but the exact position within the circle is unknown. Ping Hunter does the same for whoever is hunting you. Use them strategically to close in on your target or evade your hunter.",
  },
  {
    q: 'Is GPS tracking required?',
    a: "Yes. GPS is used to verify you're inside the play zone and to track zone boundaries. If your GPS signal is lost for an extended period, you'll be disqualified to ensure fair play for all participants.",
  },
  {
    q: 'Can I spectate after elimination?',
    a: 'Yes! After being eliminated, you can switch to spectator mode. Watch the live leaderboard, follow the kill feed in real-time, and see how the remaining players battle it out for the prize pool. Important: please remove your QR codes from your shirt as soon as possible after elimination so you don\'t interfere with the running game.',
  },
  {
    q: 'How do I host a game?',
    a: 'Game creation is fully permissionless. Anyone can create a game by calling the createGame function on the smart contract. You set the location, zone size, entry fee, player limits, duration, zone shrink schedule, and prize split — all on-chain. No approval needed. Once created, the game appears on the website and players can register.',
  },
];

export function FAQ() {
  const [activeIndex, setActiveIndex] = useState<number | null>(null);

  const toggle = (index: number) => {
    setActiveIndex(activeIndex === index ? null : index);
  };

  return (
    <Section id="faq" title="FAQ" subtitle="Everything you need to know" alt>
      <div className="faq-list">
        {FAQ_ITEMS.map((item, i) => (
          <div key={i} className={`faq-item${activeIndex === i ? ' active' : ''}`}>
            <button
              className="faq-item__q"
              aria-expanded={activeIndex === i}
              onClick={() => toggle(i)}
            >
              {item.q}
              <span className="faq-item__icon">+</span>
            </button>
            <div className="faq-item__a">
              <p>{item.a}</p>
            </div>
          </div>
        ))}
      </div>
    </Section>
  );
}
