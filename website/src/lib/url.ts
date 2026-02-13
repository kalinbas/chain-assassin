export function slugify(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

export function gameUrl(id: number, title: string): string {
  return `/game/${slugify(title)}-${id}`;
}

export function parseGameId(param: string): number {
  const match = param.match(/-(\d+)$/);
  return match ? Number(match[1]) : Number(param);
}

interface ShareTrackingParams {
  source?: string;
  medium?: string;
  campaign?: string;
}

export function gameShareUrl(id: number, title: string, tracking?: ShareTrackingParams): string {
  const path = gameUrl(id, title);

  if (typeof window === 'undefined') {
    return path;
  }

  const url = new URL(path, window.location.origin);
  if (tracking?.source) {
    url.searchParams.set('utm_source', tracking.source);
  }
  if (tracking?.medium) {
    url.searchParams.set('utm_medium', tracking.medium);
  }
  if (tracking?.campaign) {
    url.searchParams.set('utm_campaign', tracking.campaign);
  }

  return url.toString();
}
