import { Section } from '../layout/Section';

export function DemoVideo() {
  return (
    <Section id="demo" title="See It In Action" subtitle="Alpha gameplay footage — Chain-Assassin CDMX">
      <div className="video-wrapper">
        <video controls preload="metadata" poster="/media/banner.png">
          <source src="/media/demo.mp4" type="video/mp4" />
          Your browser does not support the video tag.
        </video>
      </div>
    </Section>
  );
}
