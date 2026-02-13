import { useState } from 'react';
import { ShareIcon } from '../icons/Icons';
import { trackEvent } from '../../lib/analytics';
import { gameShareUrl } from '../../lib/url';

export function ShareButton({ gameId, title }: { gameId: number; title: string }) {
  const [status, setStatus] = useState<'idle' | 'copied' | 'shared'>('idle');

  const resetStatusSoon = () => {
    window.setTimeout(() => setStatus('idle'), 1800);
  };

  const handleShare = async () => {
    const url = gameShareUrl(gameId, title, {
      source: 'web',
      medium: 'game_detail_share',
      campaign: 'spectator',
    });

    if (navigator.share) {
      try {
        await navigator.share({
          title: `${title} — Chain Assassin`,
          text: 'Watch this game on Chain Assassin.',
          url,
        });
        setStatus('shared');
        trackEvent('game_share', { game_id: gameId, method: 'web_share', from: 'game_detail' });
        resetStatusSoon();
        return;
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') {
          return;
        }
      }
    }

    if (navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(url);
        setStatus('copied');
        trackEvent('game_share', { game_id: gameId, method: 'clipboard', from: 'game_detail' });
        resetStatusSoon();
      } catch {
        // ignore clipboard errors
      }
    }
  };

  return (
    <button
      className="btn btn--outline game-detail__share"
      onClick={() => {
        void handleShare();
      }}
    >
      <ShareIcon />
      {status === 'copied' ? 'Copied' : status === 'shared' ? 'Shared' : 'Share Game'}
    </button>
  );
}
