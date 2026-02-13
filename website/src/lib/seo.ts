interface SeoOptions {
  title: string;
  description: string;
  path: string;
  imagePath?: string;
  type?: 'website' | 'article';
}

function absoluteUrl(path: string): string {
  if (typeof window === 'undefined') {
    return path;
  }
  return new URL(path, window.location.origin).toString();
}

function upsertMetaByName(name: string, content: string): void {
  const escaped = name.replace(/"/g, '\\"');
  let tag = document.head.querySelector(`meta[name="${escaped}"]`) as HTMLMetaElement | null;
  if (!tag) {
    tag = document.createElement('meta');
    tag.setAttribute('name', name);
    document.head.appendChild(tag);
  }
  tag.content = content;
}

function upsertMetaByProperty(property: string, content: string): void {
  const escaped = property.replace(/"/g, '\\"');
  let tag = document.head.querySelector(`meta[property="${escaped}"]`) as HTMLMetaElement | null;
  if (!tag) {
    tag = document.createElement('meta');
    tag.setAttribute('property', property);
    document.head.appendChild(tag);
  }
  tag.content = content;
}

function upsertCanonical(url: string): void {
  let link = document.head.querySelector('link[rel="canonical"]') as HTMLLinkElement | null;
  if (!link) {
    link = document.createElement('link');
    link.rel = 'canonical';
    document.head.appendChild(link);
  }
  link.href = url;
}

export function setPageSeo(options: SeoOptions): void {
  if (typeof document === 'undefined') {
    return;
  }

  const canonical = absoluteUrl(options.path);
  const image = absoluteUrl(options.imagePath ?? '/media/banner.png');
  const type = options.type ?? 'website';

  document.title = options.title;

  upsertMetaByName('description', options.description);
  upsertMetaByProperty('og:title', options.title);
  upsertMetaByProperty('og:description', options.description);
  upsertMetaByProperty('og:url', canonical);
  upsertMetaByProperty('og:type', type);
  upsertMetaByProperty('og:image', image);
  upsertMetaByName('twitter:card', 'summary_large_image');
  upsertMetaByName('twitter:title', options.title);
  upsertMetaByName('twitter:description', options.description);
  upsertMetaByName('twitter:image', image);
  upsertCanonical(canonical);
}

export function setStructuredData(id: string, json: Record<string, unknown>): void {
  if (typeof document === 'undefined') {
    return;
  }

  const selector = `script[type="application/ld+json"][data-seo-id="${id}"]`;
  let script = document.head.querySelector(selector) as HTMLScriptElement | null;
  if (!script) {
    script = document.createElement('script');
    script.type = 'application/ld+json';
    script.setAttribute('data-seo-id', id);
    document.head.appendChild(script);
  }
  script.textContent = JSON.stringify(json);
}

export function clearStructuredData(id: string): void {
  if (typeof document === 'undefined') {
    return;
  }

  const selector = `script[type="application/ld+json"][data-seo-id="${id}"]`;
  const script = document.head.querySelector(selector);
  if (script) {
    script.remove();
  }
}
