import { useState } from 'react';
import { ShareIcon } from '../icons/Icons';

export function ShareButton({ gameId }: { gameId: number }) {
  const [copied, setCopied] = useState(false);

  const handleShare = () => {
    const url = `${window.location.origin}/game/${gameId}`;
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
