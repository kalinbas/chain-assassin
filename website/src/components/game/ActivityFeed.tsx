import type { GameEvent } from '../../types/game';

export function ActivityFeed({ events }: { events: GameEvent[] }) {
  const shortTx = (txHash: string) =>
    txHash.length > 14 ? `${txHash.slice(0, 8)}...${txHash.slice(-4)}` : txHash;

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
            {event.txHash && (
              <span className="game-detail__activity-tx" title={event.txHash}>
                tx {shortTx(event.txHash)}
              </span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
