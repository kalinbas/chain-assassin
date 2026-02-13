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
import { trackEvent } from '../lib/analytics';
import { setPageSeo } from '../lib/seo';

export function LandingPage() {
  useEffect(() => {
    setPageSeo({
      title: 'Chain Assassin — Hunt or Be Hunted. On-Chain.',
      description: 'Real-world elimination game with GPS-tracked zones, QR kills, and ETH prizes on Base.',
      path: '/',
      type: 'website',
    });
    trackEvent('landing_view');
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
