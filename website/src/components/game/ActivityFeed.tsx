import type { GameEvent } from '../../types/game';
import { EXPLORER_URL } from '../../config/constants';

export function ActivityFeed({ events }: { events: GameEvent[] }) {
  return (
    <div className="game-detail__activity">
      <h3 className="game-detail__section-title">Latest Activity</h3>
      <div className="game-detail__activity-list">
        {events.length === 0 && (
          <p style={{ color: 'var(--text-sec)', fontSize: '0.9rem' }}>No events yet.</p>
        )}
        {events.map((event, i) => (
          <div className="game-detail__activity-item" key={i}>
            <span className={`game-detail__activity-dot game-detail__activity-dot--${event.type}`} />
            <span className="game-detail__activity-text">{event.text}</span>
            <span className="game-detail__activity-time">{event.date}</span>
            <a
              href={`${EXPLORER_URL}/tx/${event.txHash}`}
              className="game-detail__activity-tx"
              target="_blank"
              rel="noopener"
            >
              tx &#8599;
            </a>
          </div>
        ))}
      </div>
    </div>
  );
}
