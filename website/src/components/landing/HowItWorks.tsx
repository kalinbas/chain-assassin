import { Section } from '../layout/Section';
import { WalletIcon, ClockIcon, QrIcon, CrosshairIcon, HeartIcon, TrophyIcon } from '../icons/Icons';

const STEPS = [
  { icon: WalletIcon, title: 'Create Wallet', desc: 'Generate a Base wallet in-app. Deposit ETH to enter games.' },
  { icon: ClockIcon, title: 'Join a Game', desc: 'Browse upcoming games by city. Pay the entry fee to register.' },
  { icon: QrIcon, title: 'Check In', desc: "Arrive at the game zone. Scan another player's QR to verify attendance." },
  { icon: CrosshairIcon, title: 'Hunt Your Target', desc: "Find and scan your target's QR code. Stay inside the shrinking zone." },
  { icon: HeartIcon, title: 'Stay Alive', desc: 'Scan nearby players every 10 min to prove you\'re not hiding. Miss it and you\'re out.' },
  { icon: TrophyIcon, title: 'Win ETH', desc: 'Last players standing split the prize pool. Claim winnings to your wallet.' },
];

export function HowItWorks() {
  return (
    <Section id="how-it-works" title="How It Works" subtitle="Six steps to the hunt" alt>
      <div className="steps">
        {STEPS.map((step, i) => (
          <div key={step.title} style={{ display: 'contents' }}>
            {i > 0 && <div className="step__connector" />}
            <div className="step">
              <div className="step__number">{i + 1}</div>
              <div className="step__icon">
                <step.icon width={48} height={48} />
              </div>
              <h3 className="step__title">{step.title}</h3>
              <p className="step__desc">{step.desc}</p>
            </div>
          </div>
        ))}
      </div>
    </Section>
  );
}
