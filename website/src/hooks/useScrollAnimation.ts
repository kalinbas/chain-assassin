import { useEffect, useRef, useCallback } from 'react';

export function useScrollAnimation<T extends HTMLElement>() {
  const observerRef = useRef<IntersectionObserver | null>(null);

  useEffect(() => {
    observerRef.current = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            const el = entry.target as HTMLElement;
            const parent = el.parentElement;
            if (parent) {
              const siblings = Array.from(parent.children);
              const idx = siblings.indexOf(el);
              el.style.transitionDelay = `${idx * 0.08}s`;
            }
            el.classList.add('visible');
            observerRef.current?.unobserve(el);
          }
        });
      },
      { threshold: 0.15, rootMargin: '0px 0px -40px 0px' },
    );

    return () => observerRef.current?.disconnect();
  }, []);

  const ref = useCallback((node: T | null) => {
    if (node && observerRef.current) {
      node.classList.add('fade-in');
      observerRef.current.observe(node);
    }
  }, []);

  return ref;
}
