import { useState, useEffect, useCallback, useRef } from 'react';

function getSlidesPerView(): number {
  if (typeof window === 'undefined') return 3;
  if (window.innerWidth <= 768) return 1;
  if (window.innerWidth <= 1024) return 2;
  return 3;
}

export function useCarousel(totalSlides: number) {
  const [currentIndex, setCurrentIndex] = useState(0);
  const [slidesPerView, setSlidesPerView] = useState(getSlidesPerView);
  const autoPlayRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const isPausedRef = useRef(false);
  const touchStartRef = useRef(0);

  const maxIndex = Math.max(0, totalSlides - slidesPerView);

  const goTo = useCallback((index: number) => {
    setCurrentIndex(Math.max(0, Math.min(index, maxIndex)));
  }, [maxIndex]);

  const next = useCallback(() => {
    setCurrentIndex((i) => (i >= maxIndex ? 0 : i + 1));
  }, [maxIndex]);

  const prev = useCallback(() => {
    setCurrentIndex((i) => (i <= 0 ? maxIndex : i - 1));
  }, [maxIndex]);

  // Autoplay
  const startAutoPlay = useCallback(() => {
    if (autoPlayRef.current) clearInterval(autoPlayRef.current);
    autoPlayRef.current = setInterval(() => {
      if (!isPausedRef.current) next();
    }, 4000);
  }, [next]);

  useEffect(() => {
    startAutoPlay();
    return () => {
      if (autoPlayRef.current) clearInterval(autoPlayRef.current);
    };
  }, [startAutoPlay]);

  // Resize handler
  useEffect(() => {
    const handleResize = () => {
      const newSpv = getSlidesPerView();
      setSlidesPerView(newSpv);
      setCurrentIndex((i) => Math.min(i, Math.max(0, totalSlides - newSpv)));
    };
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, [totalSlides]);

  const hoverHandlers = {
    onMouseEnter: () => { isPausedRef.current = true; },
    onMouseLeave: () => { isPausedRef.current = false; },
  };

  const touchHandlers = {
    onTouchStart: (e: React.TouchEvent) => {
      touchStartRef.current = e.touches[0].clientX;
    },
    onTouchEnd: (e: React.TouchEvent) => {
      const delta = e.changedTouches[0].clientX - touchStartRef.current;
      if (Math.abs(delta) > 50) {
        if (delta > 0) prev(); else next();
        startAutoPlay();
      }
    },
  };

  return { currentIndex, slidesPerView, maxIndex, goTo, next, prev, hoverHandlers, touchHandlers };
}
