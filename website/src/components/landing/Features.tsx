import { Section } from '../layout/Section';
import { CrosshairIcon, AlertIcon, BoltIcon } from '../icons/Icons';

const FEATURES = [
  {
    icon: CrosshairIcon,
    iconClass: 'feature-card__icon--danger',
    title: 'The Hunt',
    items: [
      'Each player is assigned a random target',
      'Scan their QR code to eliminate them',
      'Get a new target after each kill',
      'Your hunter is also looking for you',
    ],
  },
  {
    icon: AlertIcon,
    iconClass: 'feature-card__icon--warning',
    title: 'Shrinking Zone',
    items: [
      'Game zone starts large (2km radius)',
      'Zone shrinks in stages every 10 minutes',
      '60 seconds outside = elimination',
      'Final zone forces close-quarters action',
    ],
  },
  {
    icon: BoltIcon,
    iconClass: 'feature-card__icon--shield',
    title: 'Tactical Items',
    items: [
      <><strong>Ping Target</strong> — reveal their zone</>,
      <><strong>Ping Hunter</strong> — detect proximity</>,
      <><strong>Ghost Mode</strong> — invisible for 2 min</>,
      <><strong>Decoy Ping</strong> — fake location</>,
      <><strong>EMP Blast</strong> — disable target's map</>,
    ],
  },
];

export function Features() {
  return (
    <Section id="features" title="Game Mechanics" subtitle="Battle royale meets the real world">
      <div className="features-grid">
        {FEATURES.map((feature) => (
          <div key={feature.title} className="feature-card">
            <div className={`feature-card__icon ${feature.iconClass}`}>
              <feature.icon />
            </div>
            <h3 className="feature-card__title">{feature.title}</h3>
            <ul className="feature-card__list">
              {feature.items.map((item, i) => (
                <li key={i}>{item}</li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </Section>
  );
}
