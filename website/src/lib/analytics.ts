type AnalyticsValue = string | number | boolean;
type AnalyticsProps = Record<string, AnalyticsValue>;

declare global {
  interface Window {
    dataLayer?: Array<Record<string, unknown>>;
    gtag?: (...args: unknown[]) => void;
    plausible?: (event: string, options?: { props?: AnalyticsProps }) => void;
    posthog?: {
      capture: (event: string, properties?: AnalyticsProps) => void;
    };
  }
}

function cleanProps(props: AnalyticsProps): AnalyticsProps {
  const out: AnalyticsProps = {};
  for (const [key, value] of Object.entries(props)) {
    if (value !== undefined && value !== null) {
      out[key] = value;
    }
  }
  return out;
}

export function trackEvent(event: string, props: AnalyticsProps = {}): void {
  if (typeof window === 'undefined') {
    return;
  }

  const clean = cleanProps(props);

  try {
    window.gtag?.('event', event, clean);
  } catch {
    // ignore provider errors
  }

  try {
    window.plausible?.(event, { props: clean });
  } catch {
    // ignore provider errors
  }

  try {
    window.posthog?.capture(event, clean);
  } catch {
    // ignore provider errors
  }

  if (window.dataLayer) {
    window.dataLayer.push({ event, ...clean });
  }

  window.dispatchEvent(
    new CustomEvent('chainassassin:analytics', {
      detail: { event, props: clean, timestamp: Date.now() },
    }),
  );
}
