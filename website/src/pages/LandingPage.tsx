import { useEffect } from 'react';
import { Hero } from '../components/landing/Hero';
import { ActiveGame } from '../components/landing/ActiveGame';
import { GamesGrid } from '../components/landing/GamesGrid';
import { PastGamesGrid } from '../components/landing/PastGamesGrid';
import { HowItWorks } from '../components/landing/HowItWorks';
import { Features } from '../components/landing/Features';
import { Screenshots } from '../components/landing/Screenshots';
import { DemoVideo } from '../components/landing/DemoVideo';
import { HostSection } from '../components/landing/HostSection';
import { FAQ } from '../components/landing/FAQ';

export function LandingPage() {
  useEffect(() => {
    document.title = 'Chain Assassin — Hunt or Be Hunted. On-Chain.';
  }, []);

  return (
    <>
      <Hero />
      <ActiveGame />
      <GamesGrid />
      <PastGamesGrid />
      <HowItWorks />
      <Features />
      <Screenshots />
      <DemoVideo />
      <HostSection />
      <FAQ />
    </>
  );
}
