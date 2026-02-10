import { useState } from 'react';
import { Section } from '../layout/Section';

const FAQ_ITEMS = [
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
    a: '90% of all entry fees go to the prize pool. 1st place wins 40%, 2nd place wins 15%, and 3rd place wins 10%. The remaining 10% is the platform fee. Use the prize calculator above to see exact payouts for different game configurations.',
  },
  {
    q: 'What blockchain does it use?',
    a: 'Chain-Assassin runs on Base, an Ethereum Layer 2 blockchain. Base offers low transaction fees, fast confirmations, and the full security of Ethereum. All entry fees, prize distributions, and game records are on-chain.',
  },
  {
    q: 'What if a game is cancelled?',
    a: "If the minimum player count isn't reached before the game start time, the game is automatically cancelled and all entry fees are refunded to players' wallets. No risk of losing funds on cancelled games.",
  },
  {
    q: 'What are tactical items?',
    a: "Tactical items are one-use abilities that give you a strategic edge. Ping Target reveals your target's approximate zone. Ping Hunter shows how close your hunter is. Ghost Mode makes you invisible for 2 minutes. Decoy Ping sends a fake location to mislead your hunter. EMP Blast disables your target's map for 30 seconds.",
  },
  {
    q: 'Is GPS tracking required?',
    a: "Yes. GPS is used to verify you're inside the play zone and to track zone boundaries. If your GPS signal is lost for an extended period, you'll be disqualified to ensure fair play for all participants.",
  },
  {
    q: 'Can I spectate after elimination?',
    a: 'Yes! After being eliminated, you can switch to spectator mode. Watch the live leaderboard, follow the kill feed in real-time, and see how the remaining players battle it out for the prize pool.',
  },
  {
    q: 'How do I host a game?',
    a: 'Game hosting is coming soon. Hosts will be able to create games with custom locations, zone sizes, entry fees, player caps, durations, and zone shrink schedules. Follow our social channels for updates.',
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
