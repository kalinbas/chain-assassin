import { useState } from 'react';
import { ShareIcon } from '../icons/Icons';
import { gameUrl } from '../../lib/url';

export function ShareButton({ gameId, title }: { gameId: number; title: string }) {
  const [copied, setCopied] = useState(false);

  const handleShare = () => {
    const url = `${window.location.origin}${gameUrl(gameId, title)}`;
    navigator.clipboard.writeText(url).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <button className="btn btn--outline game-detail__share" onClick={handleShare}>
      <ShareIcon />
      {copied ? 'Copied!' : 'Share Game'}
    </button>
  );
}
