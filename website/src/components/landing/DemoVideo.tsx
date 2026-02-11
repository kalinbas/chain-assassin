import { Section } from '../layout/Section';

const base = import.meta.env.BASE_URL;

export function DemoVideo() {
  return (
    <Section id="demo" title="See It In Action" subtitle="Alpha gameplay footage — Chain Assassin CDMX">
      <div className="video-wrapper">
        <video controls preload="metadata" poster={`${base}media/poster.png`}>
          <source src={`${base}media/demo.mp4`} type="video/mp4" />
          Your browser does not support the video tag.
        </video>
      </div>
    </Section>
  );
}
