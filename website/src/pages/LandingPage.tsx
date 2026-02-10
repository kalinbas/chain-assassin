import { Hero } from '../components/landing/Hero';
import { GamesGrid } from '../components/landing/GamesGrid';
import { PastGamesGrid } from '../components/landing/PastGamesGrid';
import { HowItWorks } from '../components/landing/HowItWorks';
import { Features } from '../components/landing/Features';
import { Screenshots } from '../components/landing/Screenshots';
import { DemoVideo } from '../components/landing/DemoVideo';
import { HostSection } from '../components/landing/HostSection';
import { FAQ } from '../components/landing/FAQ';

export function LandingPage() {
  return (
    <>
      <Hero />
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
