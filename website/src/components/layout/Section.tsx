import type { ReactNode } from 'react';

interface SectionProps {
  id?: string;
  title: string;
  subtitle?: string;
  alt?: boolean;
  children: ReactNode;
  note?: string;
}

export function Section({ id, title, subtitle, alt, children, note }: SectionProps) {
  return (
    <section className={`section${alt ? ' section--alt' : ''}`} id={id}>
      <div className="container">
        <h2 className="section__title">{title}</h2>
        {subtitle && <p className="section__sub">{subtitle}</p>}
        {children}
        {note && <p className="section__note">{note}</p>}
      </div>
    </section>
  );
}
