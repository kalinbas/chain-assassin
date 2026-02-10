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
