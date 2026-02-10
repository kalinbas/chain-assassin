import { Section } from '../layout/Section';
import { useCarousel } from '../../hooks/useCarousel';

const SLIDES = [
  { src: '/media/screen-welcome.jpg', alt: 'Welcome screen', label: 'Welcome' },
  { src: '/media/screen-browser.jpg', alt: 'Game browser', label: 'Browse Games' },
  { src: '/media/screen-detail.jpg', alt: 'Game details', label: 'Game Details' },
  { src: '/media/screen-game.jpg', alt: 'Main game screen', label: 'In-Game HUD' },
  { src: '/media/screen-items.jpg', alt: 'Tactical items', label: 'Tactical Items' },
  { src: '/media/screen-spectator.jpg', alt: 'Spectator mode', label: 'Spectator Mode' },
];

export function Screenshots() {
  const { currentIndex, slidesPerView, maxIndex, goTo, next, prev, hoverHandlers, touchHandlers } = useCarousel(SLIDES.length);

  const slideWidthPct = 100 / slidesPerView;
  const gapPx = 24;
  const translateX = currentIndex * (slideWidthPct);

  return (
    <Section id="screenshots" title="The App" subtitle="Built for the hunt" alt>
      <div className="carousel" {...hoverHandlers} {...touchHandlers}>
        <div
          className="carousel__track"
          style={{
            transform: `translateX(calc(-${translateX}% - ${currentIndex * gapPx}px))`,
          }}
        >
          {SLIDES.map((slide) => (
            <div key={slide.label} className="carousel__slide">
              <div className="phone-frame">
                <img src={slide.src} alt={slide.alt} loading="lazy" />
              </div>
              <p className="carousel__label">{slide.label}</p>
            </div>
          ))}
        </div>
        <div className="carousel__dots">
          {Array.from({ length: maxIndex + 1 }, (_, i) => (
            <button
              key={i}
              className={`carousel__dot${i === currentIndex ? ' active' : ''}`}
              onClick={() => goTo(i)}
              aria-label={`Go to slide ${i + 1}`}
            />
          ))}
        </div>
        <button className="carousel__btn carousel__btn--prev" onClick={prev} aria-label="Previous">&#8249;</button>
        <button className="carousel__btn carousel__btn--next" onClick={next} aria-label="Next">&#8250;</button>
      </div>
    </Section>
  );
}
